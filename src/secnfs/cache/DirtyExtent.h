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
#include <memory>
#include <mutex>
#include <string>
#include <tuple>
#include <unordered_map>
#include <vector>

#include "capi/cpputil.h"
#include "util/common.h"
#include "util/base64.h"
#include "util/cache_interface.h"
#include "util/hash.h"
#include "util/iclutil.h"
#include "util/intrusive_list.h"
#include "util/slice.h"

namespace secnfs {
namespace cache {

typedef std::chrono::time_point<std::chrono::steady_clock> steady_time_t;

inline steady_time_t GetTime(int seconds) {
  return seconds >= 0 ? (std::chrono::steady_clock::now() +
                         std::chrono::seconds(seconds))
                      : (std::chrono::steady_clock::now() -
                         std::chrono::seconds(-seconds));
}

class FileCache;

struct DirtyExtent {
  // We hold a ref of CacheHandle so that the FileCache's deleter won't be
  // called when it is evicted from the cache. We will release this
  // CacheHandle after all dirty has been written back. Then, it will be safe
  // to delete the FileCache.
  secnfs::util::CacheHandle* const cache_handle_;
  FileCache* const file_cache_;
  const steady_time_t deadline_;
  size_t offset_;
  size_t length_;
  secnfs::util::IntrusiveListHook list_hook_;

  DirtyExtent(secnfs::util::CacheHandle* cache_handle, FileCache* file_cache,
              size_t offset, size_t length, int writeback_seconds)
      : DirtyExtent(cache_handle, file_cache, offset, length,
                    GetTime(writeback_seconds)) {}

  DirtyExtent(secnfs::util::CacheHandle* cache_handle, FileCache* file_cache,
              size_t offset, size_t length, steady_time_t deadline)
      : cache_handle_(cache_handle),
        file_cache_(file_cache),
        deadline_(deadline),
        offset_(offset),
        length_(length) {}

  /*
   * Trim any part that overlaps the provided extent [offset, offset + length).
   * When this extent is broken into multiple pieces because of the trimming, it
   * will be changed to the first piece, and other pieces will be discarded.
   *
   * @param offset target extent's offset
   * @param length target extent's length
   * @return whether this extent is empty after this trimming.
   */
  bool Trim(size_t offset, size_t length) {
    size_t end_ = offset_ + length_;
    size_t end = offset + length;
    if (offset >= end_ || end <= offset_) {
      // no overlapping
      return IsEmpty();
    }
    if (offset <= offset_ && end >= end_) {
      length_ = 0;
    } else if (offset > offset_) {
      length_ = offset - offset_;
    } else {
      offset_ = end;
      length_ = end_ - offset_;
    }
    return IsEmpty();
  }

  /**
   * Clip the extent to the overlapped size with the specified range.
   *
   * @return Whether the extent is empty after this clipping.
   */
  bool Clip(size_t offset, size_t length) {
    if (Overlaps(offset, length)) {
      size_t o = std::max<size_t>(offset, offset_);
      size_t e = std::min<size_t>(offset + length, offset_ + length_);
      CHECK(o <= e);
      set_offset(o);
      set_length(e - o);
    }
    return IsEmpty();
  }

  bool Clip(boost::icl::interval<size_t>::type it) {
    return Clip(it.lower(), it.lower() + it.upper());
  }

  bool Contains(size_t pos) const {
    return pos >= offset_ && pos < (offset_ + length_);
  }

  bool Overlaps(size_t offset, size_t length) const {
    return Contains(offset) || Contains(offset + length);
  }

  void Unlink() {
    return list_hook_.unlink();
  }

  bool IsDue() const { return deadline_ <= std::chrono::steady_clock::now(); }
  bool IsEmpty() const { return length_ == 0; }

  void Clear() { length_ = 0; }

  bool operator<(const DirtyExtent& ext) const {
    return deadline_ < ext.deadline_;
  }

  bool operator<=(const DirtyExtent& ext) const {
    return deadline_ <= ext.deadline_;
  }

  size_t offset() const { return offset_; }
  void set_offset(size_t o) { offset_ = o; }
  size_t length() const { return length_; }
  void set_length(size_t l) { length_ = l; }
  size_t end() const { return offset_ + length_; }

  FileCache* file_cache() { return file_cache_; }

  boost::icl::interval<size_t>::type interval() const {
    return secnfs::util::make_interval(offset_, length_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DirtyExtent);
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
