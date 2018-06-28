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
 * BlockMap is used to maintain non-overlapping intervals.
 */

#pragma once

#include <google/protobuf/repeated_field.h>
using google::protobuf::RepeatedPtrField;
using google::protobuf::internal::RepeatedPtrIterator;

#include <mutex>
#include <deque>
using std::deque;

#include "proto/Util.pb.h"
#include "port/thread_annotations.h"

using secnfs::proto::Range;

namespace secnfs {
namespace util {

class BlockMap {
 public:
  BlockMap() {}
  ~BlockMap() {}
  // Try to insert a segment. If overlapping with existing segments, only
  // insert leading non-overlapping part.
  // Return inserted length. 0 indicates no space to insert.
  uint64_t try_insert(uint64_t offset, uint64_t length) EXCLUDES(mutex_);
  // Push back without search (assume no overlap).
  void push_back(uint64_t offset, uint64_t length) EXCLUDES(mutex_);
  // Reverse operation of try_insert (assume inserted previously)
  void remove_match(uint64_t offset, uint64_t length) EXCLUDES(mutex_);
  // Remove segments that overlap with [offset, offset + length).
  // May cut existing segment if partially overlapping.
  // Return number of affected holes
  size_t remove_overlap(uint64_t offset, uint64_t length) EXCLUDES(mutex_);
  // Find segment that contains the offset or after the offset.
  void find_next(uint64_t offset, uint64_t *nxt_offset, uint64_t *nxt_length)
      EXCLUDES(mutex_);
  // Dump to protobuf.
  void dump_to_pb(RepeatedPtrField<Range> *ranges) EXCLUDES(mutex_);
  // Load from protobuf.
  void load_from_pb(const RepeatedPtrField<Range> &ranges) EXCLUDES(mutex_);
  void clear() EXCLUDES(mutex_) {
    std::lock_guard<std::mutex> lock(mutex_);
    segs_.clear();
  }
  bool empty() EXCLUDES(mutex_) {
    std::lock_guard<std::mutex> lock(mutex_);
    return segs_.empty();
  }
  void print();

 private:
  // Return false if overlap.
  bool valid(deque<Range>::iterator pos) REQUIRES(mutex_);

  deque<Range> segs_ GUARDED_BY(mutex_);
  std::mutex mutex_; /* protect segs */
};

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
