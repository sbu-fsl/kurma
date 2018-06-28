// Copyright (c) 2013-2018 Ming Chen
// Copyright (c) 2016-2016 Praveen Kumar Morampudi
// Copyright (c) 2016-2016 Harshkumar Patel
// Copyright (c) 2017-2017 Rushabh Shah
// Copyright (c) 2013-2014 Arun Olappamanna Vasudevan 
// Copyright (c) 2013-2014 Kelong Wang
// Copyright (c) 2013-2018 Erez Zadok
// Copyright (c) 2013-2018 Stony Brook University
// Copyright (c) 2013-2018 The Research Foundation for SUNY
// This file is released under the GPL.
// A ProxyCache is a cache of NFS file content on the local storage of the
// proxy.  ProxyCache groups block caches of the same file into a FileCache.
// Therefore, most cache operations have two steps: one to find the FileCache of
// the interesting file, and another to operate on the FileCache.
//
// The FileCaches are put into a util::CacheInterface, which is thread-safe with
// internal synchronization. Each FileCache is also thread-safe. However, two
// thread-safe operations cannot be simply concatenated into one thread-safe
// operation.  We need to play carefully with the cache handle of
// util::CacheInterface to avoid any races.  Keeping a refcount of an cache
// handle prevents its entry being deleted.
//
// We also need to ensure dirty caches are written back in time.
//
// The API of ProxyCache is very close to ProxyCache's C API, which is
// documented in capi/proxy_cache.h.

#pragma once

#include <gflags/gflags.h>
#include <glog/logging.h>

#include <chrono>
#include <list>
#include <mutex>
#include <string>
#include <tuple>
#include <vector>
#include <memory>

#include "util/common.h"
#include "cache/FileCache.h"
#include "cache/DirtyExtentManager.h"
#include "cache/Revalidator.h"
#include "util/base64.h"
#include "util/cache_interface.h"
#include "util/slice.h"

// 16MB
#define MAX_EXTENT_SIZE 16777216

DECLARE_int32(proxy_cache_capacity_mb);

namespace secnfs {
namespace cache {

typedef std::chrono::time_point<std::chrono::steady_clock> steady_time_t;

class DirtyExtentManager;

// ProxyCache is thread-safe.
class ProxyCache {
 public:
  ProxyCache(const char* cache_dir, const char* cache_meta_dir,
             size_t cache_size_mb, WriteBackFuncPtr writeback = nullptr,
             int alignment = 0);
  ~ProxyCache();

  int Load();

  /**
   * Insert clean data into the cache
   *
   * @return 0 on success, otherwise a negative error code.
   */
  int InsertClean(const Slice& fh, size_t offset, size_t length,
                  const char* buf);

  /**
   * Insert dirty data.
   *
   * @return 0 on success, otherwise a negative error code.
   */
  int InsertDirty(const Slice& fh, size_t offset, size_t length,
                  const char* buf, int writeback_seconds);

  /**
   * Lookup the specified range [offset, offset + length) in the cache.
   *
   * @return the cache_lookup_result.
   */
  int Lookup(const Slice& fh, size_t offset, size_t length, char* buf,
             size_t* cached_offset, size_t* cached_length);

  /**
   * Check if the specified file has any dirty data in the cache.
   *
   * @return true if the file is dirty.
   */
  bool IsFileDirty(const Slice& fh);

  /**
   * Revalidate the cache of a file. It should be called when opening a file.
   */
  void Revalidate(const Slice& fh, uint64_t timestamp_us);

  /**
   * Should be called when closing a file.
   */
  void Close(const Slice& fh);

  int Commit(const Slice& fh, size_t offset, size_t length);

  void Delete(const Slice& fh);

  /**
   * Invalidate the cache of a File range.
   *
   * @param[in] fh File handle of the file.
   * @param[in] offset Offset of the range.
   * @param[in] length Length of the range.
   * @param[in] deleted Whether the data of the range is deleted or just
   *       invalidated.  Invalidation will fail if deleted is false and there is
   *       dirty data in the rnage.
   * @return The data size of this FileCache after the invalidation.
   */
  ssize_t Invalidate(const Slice& fh, size_t offset, size_t length,
                     bool deleted);

  std::string FileHandleToPath(const Slice& filehandle) const {
    std::string filename;
    secnfs::util::BinaryToBase64(filehandle, &filename);
    return cache_directory_ +  "/" + filename;
  }

  std::string PathToFileHandle(const Slice& path) const {
    secnfs::util::Slice spath(path);
    // remove the leading directory
    spath.remove_prefix(cache_directory_.length() + 1);
    std::string filehandle;
    secnfs::util::Base64ToBinary(spath, &filehandle);
    return filehandle;
  }

  const std::string& cache_directory() const { return cache_directory_; }
  const std::string& cache_meta_dir() const { return cache_meta_dir_; }

  /**
   * Check if there is dirty data need to be written back now.  If yes, load the
   * dirty data and be prepared for writeback by holding read locks to the dirty
   * extent so that it won't be changed in the middle of the writeback.
   *
   * @param file_handle [out] The handle of file the dirty data belong to.
   * @param offset [out] Offset of the dirty data in the file's address space.
   * @param length [out] Lenght of the dirty data.
   * @param dirty_data [out] pointer to the dirty data to be written back.
   * @see @{DirtyExtentManager}.
   * @return Whether there is dirty data to be written back, or a negative error
   * code.
   */
  bool PollWriteBack(const_buffer_t* file_handle, size_t* offset,
                     size_t* length, const char** dirty_data) {
    return dirty_extents_.PollDirtyData(
        file_handle, offset, length,
        std::max<size_t>(MAX_EXTENT_SIZE, 2 * alignment_), dirty_data);
  }

  FileCache* FindFileCache(const Slice& fh) {
    util::CacheHandle* ch = cached_files_->Lookup(fh);
    util::CacheHandleReleaser releaser(cached_files_, ch);
    return ch == nullptr
               ? nullptr
               : reinterpret_cast<FileCache*>(cached_files_->Value(ch));
  }

  int MarkWriteBackDone(const Slice& fh, size_t offset, size_t length,
                        const char** dirty_data);

  void Destroy() {}

 private:
  /**
   * Look up FileCache, create a new one if not found in the cache.
   *
   * @param[in] fh File handle of the FileCache.
   * @return A tuple of (1) the found or created FileCache, and (2) the
   * CacheHandle of the found FileCache, or null if not found.
   */
  std::tuple<FileCache*, secnfs::util::CacheHandle*> FindOrCreateFileCache(
      const Slice& fh);

  /**
   * Insert clean/dirty cache into FileCache.
   *
   * @return A tuple of (1) new size of the FileCache, (2) the FileCache of the
   * file handle, and (3) CacheHandle.
   */
  ssize_t InsertImpl(const Slice& fh, size_t offset, size_t length,
                     const char* buf, CacheState cstate, int wb_seconds);

  /**
   * Helper of invalidate().  The caller should hold a reference to file_cache
   * to prevent it from being deleted.
   */
  ssize_t InvalidateImpl(const Slice& fh, FileCache* file_cache, size_t offset,
                         size_t length, bool deleted);

 private:
  // the directory of cached files
  const std::string cache_directory_;

  // the directory of the metadata associated with cached files
  const std::string cache_meta_dir_;

  const size_t cache_capacity_bytes_ = 0;
  secnfs::util::CacheInterface* cached_files_;

  DirtyExtentManager dirty_extents_;

  Revalidator revalidator_;

  const int alignment_;

  // TODO(mchen): add journaling of cache data
  DISALLOW_COPY_AND_ASSIGN(ProxyCache);
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
