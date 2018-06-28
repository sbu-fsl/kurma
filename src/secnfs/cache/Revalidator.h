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
// A Revalidator tracks remote changes to files, so that cache of the files
// changed remotely will be invalidate.

#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

#include "port/thread_annotations.h"
#include "util/hash.h"
#include "util/slice.h"

namespace secnfs {
namespace cache {

class Revalidator {
 public:
  Revalidator() {}
  Revalidator(const Revalidator& r) = delete;
  void operator=(const Revalidator& r) = delete;

  uint64_t GetRemoteChangeTime(const util::Slice& fh) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = remote_changes_.find(fh);
    if (it == remote_changes_.end()) {
      return 0;
    } else {
      return it->second.time;
    }
  }

  void AddRemoteChangeTime(const util::Slice&fh, uint64_t time_us) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::string key = fh.ToString();
    auto it = remote_changes_.find(fh);
    if (it != remote_changes_.end()) {
      RemoteChange& rc = it->second;
      rc.time = time_us;
      rc.count += 1;
    } else {
      char* buf = new char[fh.size()];
      memcpy(buf, fh.data(), fh.size());
      RemoteChange rc;
      rc.time = time_us;
      rc.count = 1;
      remote_changes_.insert(std::make_pair(util::Slice(buf, fh.size()), rc));
    }
  }

  void DeleteRemoteChangeTime(const util::Slice& fh) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = remote_changes_.find(fh);
    if (it != remote_changes_.end() && --(it->second.count) <= 0) {
      const char* buf = it->first.data();
      remote_changes_.erase(it);
      delete[] buf;
    }
  }

 private:
  struct RemoteChange {
    uint64_t time = 0;
    int count = 0;
  };

  std::mutex mutex_;
  std::unordered_map<util::Slice, RemoteChange> remote_changes_
      GUARDED_BY(mutex_);
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
