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
#include "DirtyExtentManager.h"

#include "cache/FileCache.h"

namespace secnfs {
namespace cache {

DirtyExtent* DirtyExtentManager::PopDirtyExtentOfFile(FileCache* file_cache) {
  DirtyExtent* de = file_cache->PopDirtyExtent();
  if (de == nullptr) {
    return nullptr;
  }
  // Remove the DirtyExtent from DirtyExtentManager's list.
  de->Unlink();
  return de;
}

bool DirtyExtentManager::PollDirtyData(const_buffer_t* handle, size_t* offset,
                                       size_t* length, size_t max_length,
                                       const char** dirty_data) {
  DirtyExtent* de = nullptr;
  FileCache* file_cache = nullptr;

  // We are interested in dirty extents of the specified file.
  if (handle->data != nullptr) {
    file_cache = GetFileCache(cache_, secnfs::capi::ToSlice(*handle));
    if (file_cache == nullptr) {
      return false;
    }
  }

  while (true) {
    de = TakeDirtyExtent(file_cache);
    if (de == nullptr) {
      // no dirty extent
      *length = 0;
      *offset = 0;
      return false;
    } else if (de->IsEmpty()) {
      DestoryExtent(de);
    } else {
      file_cache = de->file_cache();
      if (file_cache->ReadAndLockDirty(max_length, de, dirty_data) > 0) {
        *offset = de->offset();
        *length = de->length();
        break;
      }
      // No dirty data (i.e., empty dirty extent):
      CHECK(de->IsEmpty()) << "DirtyExtent should be empty";
      DestoryExtent(de);
    }
  }

  if (handle->data == nullptr) {
    // handle is an output argument and should be pointed to the file of the
    // polled dirty extent.
    secnfs::capi::FillConstBuffer(file_cache->handle(), handle);
  }

  return true;
}


DirtyExtent* DirtyExtentManager::TakeDirtyExtent(FileCache* file_cache) {
  DirtyExtent* de = nullptr;
  if (file_cache != nullptr) {
    file_cache->Lock();
    std::lock_guard<std::mutex> lock(mutex_);
    de = PopDirtyExtentOfFile(file_cache);
    file_cache->Unlock();
    return de;
  }

  while (true) {
    {
      // Peek what is the FileCache of the next DirtyExtent.
      std::lock_guard<std::mutex> lock(mutex_);
      if (unpolled_extents_.empty() || !unpolled_extents_.front().IsDue()) {
        VLOG(5) << "nothing to write back";
        return nullptr;
      }
      file_cache = unpolled_extents_.front().file_cache();
    }

    file_cache->Lock();
    FileCacheLockReleaser releaser(file_cache);
    std::lock_guard<std::mutex> lock(mutex_);
    if (unpolled_extents_.empty() || !unpolled_extents_.front().IsDue()) {
      // The dirty extent is gone! Somebody took it during the short time
      // window we released DirtyExtentManager::mutex_.
      return nullptr;
    }

    // Check if the next DirtyExtent belongs to the FileCache we peeked.
    // If the FileCache is not the one we peeked, try again.
    de = &(unpolled_extents_.front());
    if (de->file_cache() != file_cache) {
      continue;
    }

    DirtyExtent* de2 = PopDirtyExtentOfFile(file_cache);
    // They should be the same object, i.e., the pointers are the same.
    assert(de == de2);
    break;
  }

  // Check if the file is still in the cache.  If the file has been invalidated
  // (deleted from cache), no more writeback is necessary.
  //
  // Note that it is possible that the file has been invalidated and
  // recreated later.  In that case, there will be a different instance of
  // FileCache that manages the same file.
  if (GetFileCache(cache_, de->file_cache()->handle()) != de->file_cache()) {
    // The file has been deleted from cache, therefore no more writeback is
    // necessary.
    de->Clear();
  }

  return de;
}

void DirtyExtentManager::DestoryExtent(DirtyExtent* de) {
  VLOG(5) << "releasing cache handle";
  cache_->Release(de->cache_handle_);
  delete de;
}

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
