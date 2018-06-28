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
// A FileExtent describes an extent in the address space of a FileCache.

#pragma once

#include <glog/logging.h>
#include <boost/icl/interval_set.hpp>

namespace secnfs {
namespace cache {

enum class CacheState : unsigned char {
  HOLE = 0,
  CLEAN = 1,
  DIRTY = 2,
  CLEANSED = 3,
};

class FileExtent {
 public:
  FileExtent() : state_(CacheState::HOLE) {}
  explicit FileExtent(CacheState cstate) : state_(cstate) {}

  // Overwrite the state of current extent by other's state
  FileExtent& operator+=(const FileExtent& other) {
    if (state_ == CacheState::CLEAN) {
      if (other.state_ == CacheState::CLEAN) {
        state_ = CacheState::CLEAN;
      } else if (other.state_ == CacheState::DIRTY) {
        state_ = CacheState::DIRTY;
      } else if (other.state_ == CacheState::CLEANSED) {
        state_ = CacheState::CLEAN;
      }
    } else if (state_ == CacheState::DIRTY) {
      if (other.state_ == CacheState::CLEANSED) {
        state_ = CacheState::CLEAN;
      } else if (other.state_ == CacheState::CLEAN) {
        LOG(ERROR) << "Could not overwrite dirty data before written back";
      }
    } else {
      LOG(FATAL) << "A cleansed extent cannot be a target of merging";
    }
    return *this;
  }

  CacheState state() const { return state_; }
  void set_state(CacheState s) { state_ = s; }

  bool operator==(const FileExtent& other) const {
    return state_ == other.state_;
  }

 private:
  CacheState state_;  // dirty, or clean, etc.
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:sts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
