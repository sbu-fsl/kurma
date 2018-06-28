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
#include "ProxyCache.h"

#include <limits>
#include <string>
#include <vector>

#include "WriteBackManager.hpp"
#include "util/common.h"
#include "util/fileutil.h"

DEFINE_int32(proxy_cache_capacity_mb, (16*1024),
             "the capacity of proxy's local cache");

using secnfs::util::CacheHandle;
using secnfs::util::CacheHandleReleaser;
using secnfs::util::NewCache;
using secnfs::util::IsAligned;
using secnfs::util::AlignDown;
using secnfs::util::AlignUp;

namespace secnfs {
namespace cache {

namespace {

// TODO(mchen): accelerate write-back in case of evicting dirty extents.
void ClearFileCache(const Slice& key, void* value, bool exiting) {
  auto file_cache = reinterpret_cast<FileCache*>(value);
  int res = file_cache->Clear(exiting);
  CHECK_EQ(0, res) << "ClearFileCache failed: " << strerror(-res);
  delete file_cache;
}

inline size_t MsDiff(steady_time_t a, steady_time_t b) {
  return std::chrono::duration_cast<std::chrono::milliseconds>(a - b).count();
}

}  // anonymous namespace

ProxyCache::ProxyCache(const char* cache_dir, const char* cache_meta_dir,
                       size_t cache_size_mb, WriteBackFuncPtr writeback,
                       int alignment)
    : cache_directory_(cache_dir),
      cache_meta_dir_(cache_meta_dir),
      cache_capacity_bytes_(cache_size_mb << 20),
      cached_files_(NewCache(cache_capacity_bytes_)),
      dirty_extents_(cached_files_),
      alignment_(alignment) {
  WriteBackManager::Init(*this, writeback);
  (WriteBackManager::GetInstance()).Start();
}

int ProxyCache::Load() {
  std::vector<std::string> files;
  int res = 0;
  if ((res = secnfs::util::ListDirectory(cache_directory_, &files)) < 0) {
    LOG(ERROR) << "could not load proxy cache from " << cache_directory_;
    return res;
  }

  for (const auto& fname : files) {
    std::string fh;
    secnfs::util::Base64ToBinary(fname, &fh);
    auto file_cache =
        new FileCache(fh, cache_directory_, cache_meta_dir_, 0, alignment_);
    ssize_t fsize = file_cache->Load();
    if (fsize < 0) {
      LOG(ERROR) << "fail to load proxy cache file: " << fname << ". Ignored.";
      continue;
    }
    auto cache_handle = cached_files_->Insert(
        fh, file_cache, file_cache->Size(), &ClearFileCache);
    cached_files_->Release(cache_handle);
  }

  // TODO(mchen): recover dirty data if any, and update dirty_bytes_.
  return 0;
}

ProxyCache::~ProxyCache() {
  WriteBackManager::DeleteInstance();
  delete cached_files_;
}

std::tuple<FileCache*, CacheHandle*> ProxyCache::FindOrCreateFileCache(
    const Slice& fh) {
  CacheHandle* handle = nullptr;
  FileCache* file_cache = nullptr;

  while (true) {
    handle = cached_files_->Lookup(fh);
    if (handle != nullptr) {
      break;
    }
    // Try create a new FileCache as it is not found.  However, there is a race
    // condition between the lookup and the creation.  There may be multiple
    // threads creating the same file at the same time.  Only one will succeed
    // and other threads will fail with EEXIST.  In case of failure, we retry
    // lookup until we find it in the cache.
    uint64_t rct = revalidator_.GetRemoteChangeTime(fh);
    file_cache =
        new FileCache(fh, cache_directory_, cache_meta_dir_, rct, alignment_);
    int ret = file_cache->Create();
    if (ret == 0) {
      VLOG(5) << "new FileCache created for " << fh;
      break;
    } else if (ret == -EEXIST) {  // fail because of race condition
      delete file_cache;
      file_cache = nullptr;
      // retry after sleep until it is found in cache
      usleep(10000);  // 10ms (file creation and a file insertion)
    } else {
      LOG(FATAL) << "error in creating FileCache " << fh << ": "
                 << strerror(-ret);
      break;
    }
  }

  if (handle != nullptr) {
    file_cache = reinterpret_cast<FileCache*>(cached_files_->Value(handle));
  }

  return std::make_tuple(file_cache, handle);
}

ssize_t ProxyCache::InsertImpl(const Slice& fh, size_t offset, size_t length,
                               const char* buf, CacheState cstate,
                               int wb_seconds) {
  VLOG(2) << __PRETTY_FUNCTION__ << " fh: " << BinaryToBase64(fh);
  CacheHandle* handle = nullptr;
  FileCache* file_cache = nullptr;

  std::tie(file_cache, handle) = FindOrCreateFileCache(fh);

  // The insertion can be either an insertion of a new FileCache into
  // cached_files_, or an update of the cache "charge" of an existing
  // FileCache in cached_files_.
  //
  // In the update case, we need to keep a reference of the handle until the
  // end of the update so that the FileCache won't be deleted in the middle of
  // updating the "charge" of an existing FileCache. Otherwise, something
  // dangerous like the following can happen:
  //
  //        Thread-1     Thread-2
  //  t1:   Lookup()
  //  t2:                Delete()
  //  t3:   Update()
  CacheHandleReleaser releaser(cached_files_, handle);

  ssize_t size = file_cache->InsertAndLock(offset, length, buf, cstate);
  if (size < 0) {
    LOG(ERROR) << "could not insert " << fh
               << " into file cache: " << strerror(-size);
    if (handle == nullptr) {
      // delete the newly created file_cache
      file_cache->Clear(false);
      delete file_cache;
    }
    return size;
  }

  CacheHandle* new_handle = nullptr;
  if (handle != nullptr) {
    bool succeed = cached_files_->Update(fh, handle, size);
    assert(succeed);
    new_handle = handle;
  } else {
    // This is actually a *UPDATE* operation if there is an old cache entry
    // associated with "fh" in the cache.
    new_handle = cached_files_->Insert(fh, file_cache, size, &ClearFileCache);
  }

  if (cstate == CacheState::DIRTY) {
    size_t aoff = AlignDown(offset, alignment_);
    size_t alen = AlignUp(offset + length, alignment_) - aoff;
    DirtyExtent* de =
        new DirtyExtent(new_handle, file_cache, aoff, alen, wb_seconds);
    dirty_extents_.InsertDirtyExtent(de);
  } else {
    cached_files_->Release(new_handle);
  }

  file_cache->Unlock();

  return 0;
}

int ProxyCache::InsertClean(const Slice& fh, size_t offset, size_t length,
                            const char* buf) {
  return InsertImpl(fh, offset, length, buf, CacheState::CLEAN, 0);
}

int ProxyCache::InsertDirty(const Slice& fh, size_t offset, size_t length,
                            const char* buf, int writeback_seconds) {
  return InsertImpl(fh, offset, length, buf, CacheState::DIRTY,
                             writeback_seconds);
}

int ProxyCache::Lookup(const Slice& fh, size_t offset, size_t length, char* buf,
                       size_t* cached_offset, size_t* cached_length) {
  VLOG(2) << __PRETTY_FUNCTION__ << " fh: " << BinaryToBase64(fh);
  CacheHandle* handle = cached_files_->Lookup(fh);
  if (handle == nullptr) {
    LOG(INFO) << "file handle not found: " << fh;
    return 0;
  }

  secnfs::util::CacheHandleReleaser releaser(cached_files_, handle);

  auto file_cache = reinterpret_cast<FileCache*>(cached_files_->Value(handle));
  if (length == 0) {  // a special lookup just to check if the file is cached
    *cached_offset = 0;
    *cached_length = file_cache->Size();
    VLOG(3) << (*cached_length >> 10) << " KB of " << fh << " is cached";
    return 3;
  }

  return file_cache->Lookup(offset, length, buf, cached_offset, cached_length);
}

bool ProxyCache::IsFileDirty(const Slice& fh) {
  VLOG(2) << __PRETTY_FUNCTION__ << " fh: " << BinaryToBase64(fh);
  CacheHandle* handle = cached_files_->Lookup(fh);
  if (handle == nullptr) {
    return false;
  }

  secnfs::util::CacheHandleReleaser releaser(cached_files_, handle);
  auto file_cache = reinterpret_cast<FileCache*>(cached_files_->Value(handle));
  size_t dirty_size = file_cache->DirtyDataSize();
  VLOG(2) << __PRETTY_FUNCTION__ << " dirty data size: " << dirty_size;
  if (dirty_size > 0) {
          VLOG(2) << __PRETTY_FUNCTION__ << " dirty data size: " << dirty_size << " DIRTY";
          return true;
  } else {
          VLOG(2) << __PRETTY_FUNCTION__ << " dirty data size: " << dirty_size << " NOT DIRTY";
          return false;
  }
  // return dirty_size > 0;
}

int ProxyCache::MarkWriteBackDone(const Slice& fh, size_t offset, size_t length,
                                  const char** dirty_data) {
  FileCache* file_cache = FindFileCache(fh);
  CHECK_NOTNULL(file_cache);
  dirty_extents_.CleanseDirtyExtent(file_cache, offset, length, dirty_data);
  return 0;  // FIXME
}

void ProxyCache::Delete(const Slice& fh) {
  VLOG(2) << __PRETTY_FUNCTION__ << " fh: " << BinaryToBase64(fh);
  Invalidate(fh, 0, std::numeric_limits<size_t>::max(), true);
}

void ProxyCache::Revalidate(const Slice& fh, uint64_t timestamp_us) {
  revalidator_.AddRemoteChangeTime(fh, timestamp_us);
  CacheHandle* handle = cached_files_->Lookup(fh);
  if (handle == nullptr) {
    VLOG(3) << "nothing to revalidate";
    return;
  }
  CacheHandleReleaser releaser(cached_files_, handle);
  FileCache* file_cache =
      reinterpret_cast<FileCache*>(cached_files_->Value(handle));
  if (file_cache->HasTimedOut(timestamp_us)) {
    ssize_t ret = InvalidateImpl(fh, file_cache, 0,
                                 std::numeric_limits<size_t>::max(), false);
    if (ret < 0) {
      LOG(ERROR) << "could not invalidate because of dirty cache";
    }
  }
}

ssize_t ProxyCache::Invalidate(const Slice& fh, size_t offset, size_t length,
                               bool deleted) {
  CacheHandle* handle = cached_files_->Lookup(fh);
  if (handle == nullptr) {
    VLOG(3) << "nothing to invalidate";
    return 0;
  }
  CacheHandleReleaser releaser(cached_files_, handle);
  FileCache* file_cache =
      reinterpret_cast<FileCache*>(cached_files_->Value(handle));

  return InvalidateImpl(fh, file_cache, offset, length, deleted);
}

ssize_t ProxyCache::InvalidateImpl(const Slice& fh, FileCache* file_cache,
                                   size_t offset, size_t length, bool deleted) {
  ssize_t size = file_cache->InvalidateAndLock(offset, length, deleted);
  if (size < 0) {
    LOG(ERROR) << "could not invalidate [" << offset << ", "
               << (offset + length) << ") of " << fh << ": "
               << strerror(-size);
    return size;
  }

  FileCacheLockReleaser releaser(file_cache);
  if (size == 0) {
    VLOG(3) << "invalidate file cache " << fh;
    cached_files_->Erase(fh);
    dirty_extents_.DeleteDirtyExtentsOfFile(file_cache);
  } else {
    CacheHandle* new_handle =
        cached_files_->Insert(fh, file_cache, size, &ClearFileCache);
    cached_files_->Release(new_handle);
  }

  return size;
}

void ProxyCache::Close(const Slice& fh) {
  revalidator_.DeleteRemoteChangeTime(fh);
}

int ProxyCache::Commit(const Slice& fh, size_t offset, size_t length) {
  VLOG(2) << __PRETTY_FUNCTION__ << " fh: " << BinaryToBase64(fh);
  CacheHandle* const handle = cached_files_->Lookup(fh);
  if (handle == nullptr) {
    VLOG(3) << "nothing to commit for files not in cache: " << fh;
    return 0;
  }

  CacheHandleReleaser releaser(cached_files_, handle);
  auto file_cache = reinterpret_cast<FileCache*>(cached_files_->Value(handle));
  return file_cache->Commit(offset, length);
}

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
