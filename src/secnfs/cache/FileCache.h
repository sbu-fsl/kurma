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
/*
 * A FileCache represents the proxy's cache of one NFS file. NFS files are
 * cached in the granularity of extents, which are continuous blocks in a file's
 * address space.  A FileCache manages all file extents that are cached on the
 * proxy and save them into one single file in the proxy's local file system.
 * The file can potentially has holes, which represents the portions of the file
 * that are not cached.
 */

#pragma once

#include <boost/icl/interval_map.hpp>

#include <atomic>
#include <list>
#include <mutex>
#include <string>
#include <tuple>

#include "base/AddressLock.h"
#include "cache/FileExtent.h"
#include "cache/DirtyExtent.h"
#include "capi/common.h"
#include "port/thread_annotations.h"
#include "util/slice.h"
#include "util/common.h"

using secnfs::util::Slice;

namespace secnfs {
namespace cache {

// FileCache is thread-safe; all accesses to its metadata is properly
// synchronized.  So concurrently writes to different parts of the cached file
// is okay.  However, the underlying cache file is not protected by locks.  So
// if the client is locking the file, external locks should be held.
class FileCache {
 public:
  FileCache(const Slice& handle, const std::string& cache_dir,
            const std::string& meta_dir, uint64_t remote_change_time,
            int alignment = 0);

  /*
   * Create the file of this FileCache.
   *
   * @return 0 on success, or a negative error number.
   */
  int Create() EXCLUDES(mutex_);

  // Load FileCache metadata from the cache file on the proxy's local fs.
  ssize_t Load() EXCLUDES(mutex_);

  ssize_t Lookup(size_t offset, size_t length, char* buf, size_t* cached_offset,
                 size_t* cache_length) EXCLUDES(mutex_);

  /**
   * Insert data of the specified file range into FileCache.
   *
   * @return The new size of cached data in this FileCache after this insertion.
   */
  ssize_t Insert(size_t offset, size_t length, const char* buf,
                 CacheState state) EXCLUDES(mutex_) {
    return InsertImpl(offset, length, buf, state, false);
  }

  /**
   * The same as Insert(), but it does not release the lock it acquired
   * internally. Should use Unlock() or FileCacheLockReleaser to release the
   * lock later.
   */
  ssize_t InsertAndLock(size_t offset, size_t length, const char* buf,
                        CacheState state) ACQUIRE(mutex_) {
    return InsertImpl(offset, length, buf, state, true);
  }

  void Lock() ACQUIRE(mutex_) {
    mutex_.lock();
  }

  void Unlock() RELEASE(mutex_) {
    mutex_.unlock();
  }

  /**
   * The caller should hold the mutex.
   */
  void PushDirtyExtent(DirtyExtent* de) GUARDED_BY(mutex_);

  /**
   * The caller should hold the mutex.
   *
   * @return The next DirtyExtent, or nullptr if not any.
   */
  DirtyExtent* PopDirtyExtent() GUARDED_BY(mutex_);

  /**
   * Read the dirty extent around the range of the "extent".  The real resultant
   * extent may change because of merging and spliting.
   *
   * The rule of the change is:
   * (1) the resultant @offset will be within the original extent;
   * (2) the resultant extent end (i.e., @offset + @length) will be larger than
   * or equal to the original end;
   * (3) the resultant @length will be bounded by MAX_LENGTH, or the block
   * boundary.
   *
   * Dirty data of the resultant extent will be read and the corresponding
   * address space will be locked in the AddressLock.  Note that the AddressLock
   * is a set of flags, and is different from the FileCache's mutex lock.
   *
   * @param[in] max_length The maximum length of the resultant dirty extent.
   * @param[in/out] extent The dirty extent to load. Its offset and length may
   *       change.  On success, this FileCache will take the ownership of this
   *       DirtyExtent.
   * @param[out] dirty_data Dirty data; the memory is allocated internally and
   *       need to be released by passing it to CleanseAndUnlockDirty().
   * @return The length of the @dirty_data buffer, or negative error number.
   */
  ssize_t ReadAndLockDirty(size_t max_length, DirtyExtent* extent,
                           const char** dirty_data) EXCLUDES(mutex_);

  /**
   * Cleanse a dirty extent when it has been written back successfully (@success
   * is true); or roll it back if the writeback is unsuccessful (@success is
   * false).
   *
   * @param offset Offset of the extent to be cleansed.
   * @param length Length of the extent to be cleansed.
   * @param dirty_data The dirty_data buffer returned by ReadAndLockDirty().
   * @param success Whether the written back is successful
   * @return A tuple of (1) the number of dirty bytes left in this FileCache,
   * and (2) the DirtyExtent cleansed and unlocked.
   */
  std::tuple<size_t, DirtyExtent*> CleanseAndUnlockDirty(size_t offset,
                                                         size_t length,
                                                         const char* dirty_data,
                                                         bool success = true)
      EXCLUDES(mutex_);

  int Commit(size_t offset, size_t length);

  /**
   * Invalidate the cache if there is any cached data in the specified range.
   *
   * Note that Invalidate() may cause problems for writeback of dirty extents.
   * For example, invalidate [1, 2) will break a dirty extent of [0, 3) into two
   * smaller dirty extents, but in DirtyExtentManager, there will be only one
   * dirty extent [0, 3), which will locate [0, 1) but not [2, 3).  However it
   * is a not problem for now as we don't allow file holes.
   *
   * @param[in] offset Offset of the range.
   * @param[in] length Length of the range.
   * @param[in] deleted Whether the data of the range is deleted or just
   * invalidated.  Invalidation will fail if deleted is false and there is dirty
   * data in the rnage.
   * @return The data size of this FileCache after the invalidation.
   */
  ssize_t Invalidate(size_t offset, size_t length, bool deleted)
      EXCLUDES(mutex_) {
    return InvalidateImpl(offset, length, deleted, false);
  }

  /**
   * The same as Invalidate(), but hold the mutex lock if the return value is
   * not negative.
   *
   * @return A tuple of (1) the new file cache size after the invalidation or a
   * negative error code, (2) whether the FileCache's mutex lock is held.
   */
  ssize_t InvalidateAndLock(size_t offset, size_t length, bool deleted)
      ACQUIRE(mutex_) {
    return InvalidateImpl(offset, length, deleted, true);
  }

  /*
   * Clear the FileCache so that it can be safely destroyed. Whether the
   * underlying cache will be deleted depends on the @exiting argument and the
   * "deleted_" flag.
   *
   * @param[in] exiting is true means the FileCache is being cleared because of
   * a normal exit of the cache; @exiting is false means the FileCache is being
   * cleared because of cache eviction or cache invalidation.
   */
  int Clear(bool exiting) EXCLUDES(mutex_);

  size_t Size() EXCLUDES(mutex_);
  size_t DirtyDataSize() EXCLUDES(mutex_);

  const std::string& handle() const { return handle_; }
  std::string file_path() const { return cache_dir_ + "/" + file_name_; }
  std::string meta_path() const { return meta_dir_ + "/" + file_name_; }

  uint64_t remote_change_time() const { return remote_change_time_; }
  bool HasTimedOut(uint64_t t) const { return remote_change_time_ < t; }

 protected:
  ssize_t InsertImpl(size_t offset, size_t length, const char* buf,
                     CacheState state, bool hold_lock);

  ssize_t InvalidateImpl(size_t offset, size_t length, bool deleted,
                         bool hold_lock);

  /**
   * Insert file extent.
   *
   * @return The new size of cached data in this FileCache after this insertion.
   */
  size_t InsertExtent(size_t offset, size_t length, CacheState state)
      GUARDED_BY(mutex_);

  void RepeatUntilSuccess(std::function<bool()> action) EXCLUDES(mutex_);

  // Match [offset, offset + length) against cached extents and return cached
  // range of the requested byte range. Partial match supported for the first
  // cached range that overlaps with the specified range.
  //
  // If there is any match, it also try to lock the matched range.
  //
  // @returns true if the whole operation is successful: (1) no match found, or
  // (2) a matched range is found and successfully locked.
  bool LookupAndLockRange(size_t offset, size_t length, size_t* cached_offset,
                          size_t* cached_length) GUARDED_BY(mutex_);

  /**
   * Iterate over each extent that overlaps with the specified range.
   */
  void ForEachExtent(size_t offset, size_t length,
                     std::function<void(size_t lower, size_t upper,
                                        const FileExtent& ext)> processor)
      GUARDED_BY(mutex_) {
    size_t end = offset + length;
    auto it = cached_extents_.find(secnfs::util::make_interval(offset, length));
    while (it != cached_extents_.end() && it->first.lower() < end) {
      processor(it->first.lower(), it->first.upper(), it->second);
      ++it;
    }
  }

  /**
   * Count the total length of extents of the specified state with the range
   * [offset, offset + length).
   */
  size_t CountOverlapping(size_t offset, size_t length, CacheState cstate);

  FileCache(const FileCache&) = delete;
  void operator=(const FileCache&) = delete;

 private:
  const std::string handle_;
  const std::string file_name_;
  const std::string& cache_dir_;
  const std::string& meta_dir_;

  // Should be const after one the FileCache is loaded or created.
  uint64_t remote_change_time_;

  std::mutex mutex_;
  boost::icl::interval_map<size_t, FileExtent> cached_extents_
      GUARDED_BY(mutex_);

  // range lock of the data file's address space
  secnfs::base::AddressLock address_lock_ GUARDED_BY(mutex_);

  // The total size of all cached extents in this file. Because the cached file
  // can have holes, "size_ <= file_size".
  size_t size_ GUARDED_BY(mutex_) = 0;

  // Record the size of outstanding dirty dirty in this FileCache.
  size_t dirty_data_size_ GUARDED_BY(mutex_) = 0;

  std::list<DirtyExtent*> unpolled_dirty_extents_ GUARDED_BY(mutex_);

  // Locked (polled) dirty extents.
  std::list<DirtyExtent*> locked_dirty_extents_ GUARDED_BY(mutex_);

  const int alignment_;

  friend class FileCacheTest;
};


class FileCacheLockReleaser {
 public:
  explicit FileCacheLockReleaser(FileCache* file_cache)
      : file_cache_(file_cache) {}

  FileCacheLockReleaser(const FileCacheLockReleaser& r) = delete;
  FileCacheLockReleaser& operator=(const FileCacheLockReleaser& r) = delete;

  ~FileCacheLockReleaser() {
    if (file_cache_ != nullptr) {
      file_cache_->Unlock();
    }
  }

 private:
  FileCache* file_cache_;
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
