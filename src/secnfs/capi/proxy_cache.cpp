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
// Implementation of ProxyCache's C interface to NFS-Ganesha.

#include <glog/logging.h>

#include "capi/proxy_cache.h"

#include <memory>
#include <string>
#include "cache/ProxyCache.h"
#include "cache/FileCache.h"
#include "capi/cpputil.h"
#include "util/hash.h"
#include "util/slice.h"

using secnfs::cache::ProxyCache;
using secnfs::cache::FileCache;
using secnfs::util::GetSliceHash;

ProxyCache* cache;

int init_proxy_cache(const char* cache_dir, const char* meta_dir,
                     WriteBackFuncPtr writeback, int alignment) {
  VLOG(1) << "==capi== init_proxy_cache " << cache_dir << " " << meta_dir;
  cache = new ProxyCache(cache_dir, meta_dir, FLAGS_proxy_cache_capacity_mb,
                         writeback, alignment);
  return cache->Load();
}

int lookup_cache(const const_buffer_t* handle,
                 size_t offset,
                 size_t length,
                 char* buf,
                 size_t* cached_offset,
                 size_t* cached_length) {
  // assert offset and length are block-aligned
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  int ret = cache->Lookup(fh, offset, length, buf, cached_offset, cached_length);
  VLOG(1) << "==capi== lookup_cache " << GetSliceHash(fh) << " " << offset
          << " " << length << ": " << ret;
  return ret;
}

bool is_file_dirty(const const_buffer_t* handle) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  return cache->IsFileDirty(fh);
}

int insert_cache(const const_buffer_t* handle,
                 size_t offset,
                 size_t length,
                 const char* buf,
                 int writeback_seconds) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  VLOG(1) << "==capi== insert_cache " << GetSliceHash(fh) << " "
          << offset << " " << length;
  int ret;
  if (writeback_seconds < 0) {
    ret = cache->InsertClean(fh, offset, length, buf);
  } else {
    ret = cache->InsertDirty(fh, offset, length, buf, writeback_seconds);
  }
  return ret;
}

int commit_cache(const const_buffer_t* handle,
                 size_t offset,
                 size_t length) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  VLOG(1) << "==capi== commit_cache " << GetSliceHash(fh) << " "
          << offset << " " << length;
  return cache->Commit(fh, offset, length);
}

int delete_cache(const const_buffer_t* handle) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  VLOG(1) << "==capi== delete_cache " << GetSliceHash(fh);
  cache->Delete(fh);
  // We always return 0 to indicate success even when we are invalidating a file
  // that is not cached.
  return 0;
}

int invalidate_cache(const const_buffer_t* handle,
                     size_t offset,
                     size_t length,
                     bool deleted) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  VLOG(1) << "==capi== invalidate_cache " << GetSliceHash(fh) << " "
          << offset << " " << length << " " << deleted;
  ssize_t ret = cache->Invalidate(fh, offset, length, deleted);
  return ret < 0 ? ret : 0;
}

void open_and_revalidate_cache(const const_buffer_t* handle, uint64_t time_us) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  cache->Revalidate(fh, time_us);
}

void close_cache(const const_buffer_t* handle) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  cache->Close(fh);
}

bool poll_writeback_cache(const_buffer_t* handle,
                         size_t* offset,
                         size_t* length,
                         const char** dirty_data) {
  bool res = cache->PollWriteBack(handle, offset, length, dirty_data);
  if (res) {
    VLOG(1) << "==capi== poll_writeback_cache "
            << GetSliceHash(Slice(handle->data, handle->size)) << " " << *offset
            << " " << *length;
  } else {
    VLOG(1) << "==capi== poll_writeback_cache empty";
  }
  return res;
}

int mark_writeback_done(const const_buffer_t* handle,
                        size_t offset,
                        size_t length,
                        const char** dirty_data) {
  secnfs::util::Slice fh = secnfs::capi::ToSlice(*handle);
  VLOG(1) << "==capi== mark_writeback_done " << GetSliceHash(fh) << " "
          << offset << " " << length;
  return cache->MarkWriteBackDone(fh, offset, length, dirty_data);
}

void destroy_proxy_cache() {
  // We call Destroy() first before we destruct ProxyCache so that the
  // ProxyCaches know it is being destructed because of not cache eviction but a
  // normal exit.
  VLOG(1) << "==capi== destroy_proxy_cache";
  cache->Destroy();
  delete cache;
}

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
