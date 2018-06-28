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
#include "FileCache.h"

#include <boost/icl/interval_set.hpp>
#include <glog/logging.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "proto/CacheJournal.pb.h"
#include "util/base64.h"
#include "util/fileutil.h"
#include "util/protobuf.h"

using boost::icl::interval_set;
using secnfs::base::AddressLockReleaser;
using secnfs::proto::FileCacheMeta;
using secnfs::proto::FileExtentMeta;
using secnfs::util::BinaryToBase64;
using secnfs::util::Slice;
using secnfs::util::make_interval;
using secnfs::util::IsAligned;
using secnfs::util::AlignDown;
using secnfs::util::AlignUp;

namespace secnfs {
namespace cache {

namespace {
bool ValidateMetadata(const FileCacheMeta& meta, const std::string& path) {
  // 1. inspect extents (file_extents) from the file
  std::vector<std::pair<size_t, size_t>> file_extents;
  int res = secnfs::util::GetFileExtents(path, &file_extents);
  if (res < 0) {
    LOG(ERROR) << "could not inspect extents of cache file";
    return false;
  }
  // 2. build extents (meta_extents) from the FileCacheMeta; touching extents
  // with different cache states will be merged
  interval_set<size_t> meta_extents;
  for (const auto& ext : meta.extents()) {
    meta_extents.add(make_interval(ext.offset(), ext.length()));
  }
  // 3. compare the two extents
  if (file_extents.size() != meta_extents.iterative_size()) {
    return false;
  }
  using Interval = boost::icl::interval<size_t>::type;
  return std::equal(meta_extents.begin(), meta_extents.end(),
                    file_extents.begin(),
                    [](const Interval& x, const std::pair<size_t, size_t>& y) {
    return x.lower() == y.first && x.upper() == y.second;
  });
}
}  // anonymous namespace

FileCache::FileCache(const Slice& handle, const std::string& cache_dir,
                     const std::string& meta_dir, uint64_t remote_change_time,
                     int alignment)
    : handle_(handle.data(), handle.size()),
      file_name_(BinaryToBase64(handle_)),
      cache_dir_(cache_dir),
      meta_dir_(meta_dir),
      remote_change_time_(remote_change_time),
      alignment_(alignment) {}

int FileCache::Create() {
  std::lock_guard<std::mutex> lock(mutex_);
  VLOG(1) << "creating " << file_path();
  return secnfs::util::CreateFile(file_path());
}

ssize_t FileCache::Load() {
  FileCacheMeta meta;
  CHECK_GT(secnfs::util::ReadMessageFromFile(meta_path(), &meta), 0);
  CHECK_EQ(handle_, meta.file_handle());
  CHECK(ValidateMetadata(meta, file_path()));

  std::lock_guard<std::mutex> lock(mutex_);
  remote_change_time_ = meta.remote_change_time();
  for (const auto& ext : meta.extents()) {
    InsertExtent(ext.offset(), ext.length(), CacheState(ext.cache_state()));
  }
  CHECK_EQ(size_, boost::icl::length(cached_extents_))
      << "file cache size mismatch: " << file_name_;
  return size_;
}

ssize_t FileCache::ReadAndLockDirty(size_t max_length, DirtyExtent* extent,
                                    const char** dirty_data) {
  VLOG(3) << "read dirty data inside [" << extent->offset() << ", +"
          << extent->length() << ")";
  RepeatUntilSuccess([this, extent, max_length]() {
    auto it = cached_extents_.find(extent->interval());
    size_t end = extent->end();
    // find the first dirty extent within [offset, offset + length)
    while (true) {
      if (it == cached_extents_.end() || it->first.lower() >= end) {
        VLOG(3) << "no dirty data within extent [" << extent->offset() << ", "
                << extent->end() << ").";
        extent->Clear();  // no need of write-back
        return true;
      }
      if (it->second.state() == CacheState::DIRTY) {
        break;
      }
      ++it;
    }

    extent->set_offset(std::max(extent->offset(), it->first.lower()));
    extent->set_length(
        std::min(max_length, it->first.upper() - extent->offset()));

    for (auto de : locked_dirty_extents_) {
      if (extent->Trim(de->offset(), de->length())) {
        // become empty now
        return true;
      }
    }

    if (address_lock_.TryReadLock(extent->offset(), extent->length())) {
      locked_dirty_extents_.push_back(extent);
      return true;
    }

    return false;
  });

  if (extent->IsEmpty()) {
    VLOG(5) << "no dirty data at " << extent->offset() << " in " << file_name_;
    return 0;
  }

  size_t offset = extent->offset();
  size_t length = extent->length();
  VLOG(3) << "dirty extent at " << offset << " with " << length << " bytes";
  char* buf = new char[length];
  if (buf == nullptr) {
    AddressLockReleaser r(address_lock_, mutex_, offset, length, false);
    return -ENOMEM;
  }

  ssize_t ret = secnfs::util::PRead(file_path(), offset, length, buf);
  if (ret < 0) {
    AddressLockReleaser r(address_lock_, mutex_, offset, length, false);
    return ret;
  }
  CHECK_EQ(ret, length) << "partial write-back not supported";

  *dirty_data = buf;
  return length;
}

size_t FileCache::CountOverlapping(size_t offset, size_t length,
                                   CacheState cstate) {
  size_t ol = 0;
  ForEachExtent(offset, length, [offset, length, &ol, cstate](
                                    size_t l, size_t u, const FileExtent& ext) {
    if (ext.state() == cstate) {
      ol += std::min(u, offset + length) - std::max(l, offset);
    }
  });
  return ol;
}

std::tuple<size_t, DirtyExtent*> FileCache::CleanseAndUnlockDirty(
    size_t offset, size_t length, const char* buf, bool success) {
  delete[] buf;
  size_t dirty_left = 0;
  DirtyExtent* de = nullptr;
  RepeatUntilSuccess([this, offset, length, success, &dirty_left, &de]() {
    if (success) {
      FileExtent cleansed_ext(CacheState::CLEANSED);
      cached_extents_.add(
          std::make_pair(make_interval(offset, length), cleansed_ext));
      CHECK_GE(dirty_data_size_, length);
      dirty_data_size_ -= length;
    }
    auto it = std::find_if(
        locked_dirty_extents_.begin(), locked_dirty_extents_.end(),
        [&offset](DirtyExtent* ext) { return ext->offset() == offset; });
    CHECK(it != locked_dirty_extents_.end());
    de = *it;
    locked_dirty_extents_.erase(it);

    address_lock_.ReadUnlock(de->offset(), de->length());
    dirty_left = dirty_data_size_;
    return true;
  });
  VLOG(2) << "left dirty " << dirty_left << " after cleansing " << length
          << " bytes at " << offset;
  return std::make_tuple(dirty_left, de);
}

bool FileCache::LookupAndLockRange(size_t offset, size_t length,
                                   size_t* cached_offset,
                                   size_t* cached_length) {
  DCHECK_NE(0, length) << "zero length not allowed";
  *cached_length = 0;
  auto target = make_interval(offset, length);
  auto first = cached_extents_.find(target);  // first matched extent
  if (first == cached_extents_.end()) {
    return true;
  }
  size_t end = offset + length;

  // Not compile. Probably a compiler bug.
  /**
  using FExtent = boost::icl::interval_map<size_t, FileExtent>::value_type;
  auto it = std::adjacent_find(first, cached_extents_.end(),
                               [end](const FExtent& cur, const FExtent& next) {
    return (cur.first.upper() < next.first.lower() ||
            cur.first.upper() >= end);
  });
  auto last = (it == cached_extents_.end()) ? first : it;
  **/

  // Find the last extent that (1) overlaps with [offset, offset + length), and
  // (2) is continuous to the first matched extent.
  auto last = first;  // last matched extent
  auto it = last;
  for (it++;
       it != cached_extents_.end() &&
           it->first.lower() == last->first.upper() && it->first.lower() < end;
       ++it) {
    last = it;
  }

  if (first->first.lower() <= offset) {  // match at the front
    *cached_offset = offset;
    *cached_length = std::min(length, last->first.upper() - offset);
  } else {
    // match at the back or in the middle
    // ignore those extent in the middle of the requested range
    if (last->first.upper() >= end) {
      // match at the back
      *cached_offset = first->first.lower();
      *cached_length = end - *cached_offset;
    } else {
      // match in the middle
      *cached_offset = first->first.lower();
      *cached_length = last->first.upper() - *cached_offset;
    }
  }

  return address_lock_.TryReadLock(*cached_offset, *cached_length);
}

ssize_t FileCache::Lookup(size_t offset, size_t length, char* buf,
                          size_t* cached_offset, size_t* cached_length) {
  RepeatUntilSuccess([this, offset, length, cached_offset, cached_length]() {
    return LookupAndLockRange(offset, length, cached_offset, cached_length);
  });

  int match_result = 0;
  if (*cached_length == 0) {
    VLOG(3) << "[" << offset << ", " << (offset + length) << ") not cached.";
    return NOT_FOUND;
  } else if (*cached_offset == offset) {
    match_result = *cached_length == length ? FULL_MATCH : FRONT_MATCH;
  } else if (*cached_offset + *cached_length == offset + length) {
    match_result = BACK_MATCH;
  } else {
    CHECK((*cached_offset + *cached_length) < (offset + length));
    match_result = MIDDLE_MATCH;
  }

  if (buf != nullptr) {
    ssize_t ret =
        secnfs::util::PRead(file_path(), *cached_offset, *cached_length,
                            buf + (*cached_offset - offset));
    if (ret < 0) {
      LOG(ERROR) << "cannot read range [" << offset << ", " << (offset + length)
                 << ") from " << file_name_;
      return ret;
    }
    *cached_length = std::min(*cached_length, static_cast<size_t>(ret));
  }
  std::lock_guard<std::mutex> lock(mutex_);
  address_lock_.ReadUnlock(*cached_offset, *cached_length);

  return match_result;
}

ssize_t FileCache::InsertImpl(size_t offset, size_t length, const char* buf,
                              CacheState state, bool hold_lock) {
  RepeatUntilSuccess([this, offset, length]() {
    return address_lock_.TryWriteLock(offset, length);
  });
  ssize_t ret = secnfs::util::PWriteSync(file_path(), offset, length, buf);
  if (ret < 0) {
    LOG(ERROR) << "Cannot insert into " << file_name_;
    AddressLockReleaser r(address_lock_, mutex_, offset, length, false);
  } else {
    CHECK_EQ(ret, length) << "only part of the buf is written";
    mutex_.lock();
    FileCacheLockReleaser releaser(hold_lock ? nullptr : this);
    address_lock_.WriteUnlock(offset, length);
    ret = InsertExtent(offset, length, state);
  }
  return ret;
}

void FileCache::RepeatUntilSuccess(std::function<bool()> action) {
  useconds_t wait = 1000;  // 1ms
  while (true) {
    mutex_.lock();
    if (action()) {
      mutex_.unlock();
      break;
    }
    mutex_.unlock();
    usleep(wait);
    // exponential backoff to 2 second
    if (wait < 2048000) wait <<= 1;
  }
}

size_t FileCache::InsertExtent(size_t offset, size_t length, CacheState state) {
  FileExtent ext(state);
  size_t aoff = AlignDown(offset, alignment_);
  size_t alen = AlignUp(offset + length, alignment_) - aoff;
  auto range = make_interval(aoff, alen);

  // boost::icl::length(cached_extents_) is an O(n) operation, so we update
  // size_ by counting the extra range introduced by this insertion. This way,
  // the amortized complexity is lower.
  size_t overlap = 0;
  size_t dirty_overlap = 0;
  size_t omin = offset;           // the min offset of overlapping data
  size_t omax = offset + length;  // the max offset of overlapping data
  ForEachExtent(aoff, alen, [&overlap, &dirty_overlap, &omin, &omax, range,
                             state](size_t l, size_t u, const FileExtent& ext) {
    omin = std::min(omin, l);
    omax = std::max(omax, u);
    size_t ol = std::min(u, range.upper()) - std::max(l, range.lower());
    overlap += ol;
    if (state == CacheState::DIRTY && ext.state() == CacheState::DIRTY) {
      dirty_overlap += ol;
    }
  });

  if (alignment_ > 0) {
    if (omin > aoff) {
      aoff = omin;
    }
    if (omax < aoff + alen) {
      alen = omax - aoff;
      range = make_interval(aoff, alen);
    }
    VLOG(5) << "dirty overlap: " << dirty_overlap
            << "; dirty size: " << dirty_data_size_ << "; length : " << length
            << "; overlap: " << overlap << "; size: " << size_
            << "; omin: " << omin << "; omax: " << omax;
  }
  size_ += alen - overlap;
  if (state == CacheState::DIRTY) {
    dirty_data_size_ += (alen - dirty_overlap);
  }

  VLOG(2) << "dirty data size " << dirty_data_size_ << " after inserting "
          << alen << " bytes at " << aoff << " with alignment " << alignment_;
  cached_extents_.add(std::make_pair(range, ext));
  DCHECK_EQ(size_, boost::icl::length(cached_extents_)) << "size mismatch";
  return size_;
}

void FileCache::PushDirtyExtent(DirtyExtent* de) {
  auto pos = std::find_if(
      unpolled_dirty_extents_.rbegin(), unpolled_dirty_extents_.rend(),
      [&de](const DirtyExtent* ext) { return *ext <= *de; });
  unpolled_dirty_extents_.insert(pos.base(), de);
}

DirtyExtent* FileCache::PopDirtyExtent() {
  DirtyExtent* de = nullptr;
  if (!unpolled_dirty_extents_.empty()) {
    de = unpolled_dirty_extents_.front();
    unpolled_dirty_extents_.pop_front();
  }
  return de;
}

int FileCache::Commit(size_t offset, size_t length) {
  // Right now, we sync the whole file, instead of just the requested range.
  // Linux supports range synchronization using sync_file_range(2), but it does
  // not synchronize any file metadata and is thus very dangerous. Especially,
  // when the underlying file system is a copy-on-write file system.
  int res = secnfs::util::SyncFileData(file_path());
  CHECK_EQ(0, res) << "Could not sync cache file " << file_name_ << ": "
                   << strerror(-res);
  // Commit cache metadata:
  FileCacheMeta meta;
  meta.set_file_handle(handle_);
  meta.set_newly_created(false);
  meta.set_remote_change_time(remote_change_time_);
  {
    std::lock_guard<std::mutex> lock(mutex_);
    for (const auto& kv : cached_extents_) {
      FileExtentMeta* ext_meta = meta.add_extents();
      ext_meta->set_offset(lower(kv.first));
      ext_meta->set_length(upper(kv.first) - lower(kv.first));
      ext_meta->set_cache_state(static_cast<unsigned char>(kv.second.state()));
    }
  }
  int ret = secnfs::util::WriteMessageToFile(meta, meta_path());
  return ret < 0 ? ret : 0;
}

ssize_t FileCache::InvalidateImpl(size_t offset, size_t length, bool deleted,
                                  bool hold_lock) {
  RepeatUntilSuccess([this, offset, length]() {
    return address_lock_.TryWriteLock(offset, length);
  });

  auto range = make_interval(offset, length);
  size_t overlap = 0;
  size_t dirty_size = 0;
  size_t cached_file_length = 0;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    ForEachExtent(offset, length,
                  [range, &overlap, &dirty_size](size_t l, size_t u,
                                                const FileExtent& ext) {
      size_t sz = std::min(u, range.upper()) - std::max(l, range.lower());
      overlap += sz;
      if (ext.state() == CacheState::DIRTY) {
        dirty_size += sz;
      }
    });
    if (!cached_extents_.empty()) {
      cached_file_length = cached_extents_.rbegin()->first.upper();
    }
  }

  if (!deleted && dirty_size > 0) {
    LOG(ERROR) << "could not invalidate dirty cache within [" << offset << ", "
               << (offset + length) << ") of " << handle_;
    AddressLockReleaser r(address_lock_, mutex_, offset, length, false);
    return -1;
  }

  if (overlap > 0) {
    // We could not punch holes that is beyond the file size.
    size_t effective_length = std::min(length, cached_file_length - offset);
    ssize_t ret =
        secnfs::util::PunchHole(file_path(), offset, effective_length);
    if (ret < 0) {
      LOG(ERROR) << "could not punch hole at [" << offset << ", "
                 << (offset + length) << ") of " << handle_;
      AddressLockReleaser r(address_lock_, mutex_, offset, length, false);
      return ret;
    }
  }

  mutex_.lock();
  FileCacheLockReleaser releaser(hold_lock ? nullptr : this);
  address_lock_.WriteUnlock(offset, length);
  if (overlap > 0) {
    cached_extents_.erase(range);
  }
  size_ -= overlap;
  dirty_data_size_ -= dirty_size;
  CHECK_GE(size_, 0);
  VLOG(5) << "dirty_data_size is " << dirty_data_size_ << " after invalidating "
          << dirty_size << " dirty bytes within [" << offset << ", "
          << (offset + length) << "); total overlap bytes is " << overlap;

  return size_;
}

int FileCache::Clear(bool exiting) {
  int ret = 0;
  if (exiting) {
    // We are exiting with not-written-back dirty data, so we have to commit
    // the dirty data and metadata so that we can recover using "Load" upon
    // restart.
    LOG(WARNING) << "There are still dirty data: " << dirty_data_size_;
    ret = Commit(0, 0);
    VLOG(1) << "FileCache " << file_name_ << " flushed.";
  } else {
    // If not exiting, then it is either being evicted or being deleted.
    ret = secnfs::util::DeleteFile(file_path());
    CHECK_EQ(ret, 0) << "Could not remove cache file: " << file_name_;
    VLOG(1) << "==pcache== FileCache file deleted";
    if (secnfs::util::FileExists(meta_path())) {
      // the meta file might have not been created yet
      ret = secnfs::util::DeleteFile(meta_path());
      CHECK_EQ(ret, 0) << "Could not remove meta file: " << file_name_;
    }
    VLOG(1) << "Cache entry " << handle_ << " deleted";
  }
  return ret;
}

size_t FileCache::Size() {
  std::lock_guard<std::mutex> lock(mutex_);
  return size_;
}

size_t FileCache::DirtyDataSize() {
  std::lock_guard<std::mutex> lock(mutex_);
  return dirty_data_size_;
}

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
