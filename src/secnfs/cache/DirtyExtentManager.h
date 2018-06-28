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
// Dirty extents that need to be written back.

#pragma once

#include <glog/logging.h>

#include <algorithm>
#include <chrono>
#include <list>
#include <mutex>
#include <string>
#include <tuple>
#include <vector>

#include "cache/DirtyExtent.h"
#include "cache/FileCache.h"
#include "capi/cpputil.h"
#include "util/cache_interface.h"
#include "util/common.h"
#include "util/hash.h"
#include "util/slice.h"

namespace secnfs {
namespace cache {

inline FileCache* GetFileCache(secnfs::util::CacheInterface* cache,
                               const secnfs::util::Slice& key) {
  return reinterpret_cast<FileCache*>(cache->LookupValue(key));
}

/**
 * Manage all dirty extents.
 *
 * DirtyExtentManager is thread-safe.  DirtyExtentManager may manipulate dirty
 * extents' FileCache, which should be protected by its own lock.  To avoid
 * deadline between DirtyExtentManager's lock and FileCache's lock, the
 * FileCache's lock should always be acquired before the DirtyExtentManager's
 * lock.
 */
class DirtyExtentManager {
 public:
  explicit DirtyExtentManager(secnfs::util::CacheInterface* cache)
      : cache_(cache) {}
  ~DirtyExtentManager() {}

  /**
   * Add a new dirty extent.
   *
   * REQUIRES: the caller hold the lock of FileCache that contains the dirty
   * extent.
   */
  void InsertDirtyExtent(DirtyExtent* de) EXCLUDES(mutex_) {
    // Schedule the writeback of this dirty cache by inserting "de" into
    // the unpolled_extents_ in the correct order.
    std::lock_guard<std::mutex> lock(mutex_);
    de->file_cache()->PushDirtyExtent(de);
    auto pos = std::find_if(
        unpolled_extents_.rbegin(), unpolled_extents_.rend(),
        [&de](const DirtyExtent& ext) { return ext <= *de; });
    unpolled_extents_.insert(pos.base(), *de);
  }

  /**
   * Cleanse the dirty extent [offset, offset + length) and free the dirty_data
   * buffer.
   *
   * @param fh File handle.
   * @param offset Offset of the @dirty_data extent w.
   * @param length Length of the @dirty_data buffer that has been successfully
   *        written back.  A length of zero indicates error, and the dirty
   *        extent will be marked as still dirty.  Partial writeback is not
   *        supported, therefore, @length should be either 0 or the length of
   *        the dirty buffer.
   * @param [in/out] dirty_data Buffer of the dirty data returned by
   *        PollDirtyData(), which now should have been written back.  It will
   *        be freed and set to nullptr on success, otherwise, it is not
   *        changed.
   * @return Whether the operation is successful.
   */
  bool CleanseDirtyExtent(FileCache* file_cache, size_t offset, size_t length,
                          const char** dirty_data) EXCLUDES(mutex_) {
    size_t dirty_left = 0;
    DirtyExtent* de = nullptr;

    std::tie(dirty_left, de) = file_cache->CleanseAndUnlockDirty(
        offset, length, *dirty_data, length != 0);

    if (length == 0) {  // writeback failed
      file_cache->Lock();
      FileCacheLockReleaser releaser(file_cache);
      InsertDirtyExtent(de);  // for retry
    } else if (length != de->length()) {
      LOG(FATAL) << "partial writeback not supported: " << length
                 << " out of " << de->length() << " bytes are written back";
    } else {
      // Now that the writeback is successful, we mark the written data as
      // clean, and then release the refcount of the cache entry we hold in
      // ExtentToWriteBack.
      DestoryExtent(de);
    }
    *dirty_data = nullptr;
    return true;
  }

  bool PollDirtyData(const_buffer_t* handle, size_t* offset, size_t* length,
                     size_t max_length, const char** dirty_data)
      EXCLUDES(mutex_);

  /**
   * Pop a dirty extent of the specified file regardless if it is due or not.
   * It will remove the dirty extent out of both FileCache's and
   * DirtyExtentManager's lists.
   *
   * REQUIRES: the caller hold the lock of @file_cache
   *
   * @return The first dirty extent if any, otherwise nullptr.
   */
  DirtyExtent* PopDirtyExtentOfFile(FileCache* file_cache) EXCLUDES(mutex_);

  /**
   * Remove all dirty extents belongs to @file.  The caller should hold the
   * mutex of @file before make this call.
   */
  void DeleteDirtyExtentsOfFile(FileCache* file) EXCLUDES(mutex_) {
    std::lock_guard<std::mutex> lock(mutex_);
    DirtyExtent* de = nullptr;
    while ((de = PopDirtyExtentOfFile(file)) != nullptr) {
      DestoryExtent(de);
    }
  }

 private:
  void DestoryExtent(DirtyExtent* de);

  /**
   * Take next dirty extent to be written back.  If @file_cache is not null,
   * only take the dirty extent from it; otherwise, take a dirty extent from the
   * global list.
   *
   * In case that @file_cache is not null, its lock will be taken and released
   * internally.
   *
   * REQUIRES: The caller should hold the DirtyExtentManager's lock.
   */
  DirtyExtent* TakeDirtyExtent(FileCache* file_cache) EXCLUDES(mutex_);

 private:
  secnfs::util::CacheInterface* const cache_;

  std::mutex mutex_;  // protects all the following class members

  // Extents that need to be written back and have not been polled. They are
  // sorted increasingly by their write-back deadlines.
  secnfs::util::IntrusiveList<DirtyExtent, &DirtyExtent::list_hook_>
      unpolled_extents_ GUARDED_BY(mutex_);

  DISALLOW_COPY_AND_ASSIGN(DirtyExtentManager);
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
