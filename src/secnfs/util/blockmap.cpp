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
#include "util/blockmap.h"

// NOLINTNEXTLINE
#include <iostream>
#include <deque>
#include <algorithm>
using std::deque;

namespace secnfs {
namespace util {

static bool cmp_offset(const Range &a, const uint64_t &b) {
  return a.offset() < b;
}

uint64_t BlockMap::try_insert(uint64_t offset, uint64_t length) {
  assert(length > 0);
  deque<Range>::iterator pos, prev;
  Range seg;

  seg.set_offset(offset);
  seg.set_length(length);

  std::lock_guard<std::mutex> lock(mutex_);
  if (segs_.empty()) {
    segs_.push_back(seg);
    return length;
  }

  pos = std::lower_bound(segs_.begin(), segs_.end(), offset, cmp_offset);

  if (pos > segs_.begin()) {
    prev = pos - 1;
    if (prev->offset() + prev->length() > offset) return 0;
  }

  if (pos != segs_.end()) {
    /* pos points to next segment of our seg if inserted */
    if (pos->offset() == offset) return 0;
    if (offset + length > pos->offset()) {
      length = pos->offset() - offset;
      seg.set_length(length);
    }
  }

  pos = segs_.insert(pos, seg);
  assert(valid(pos));

  return length;
}

void BlockMap::remove_match(uint64_t offset, uint64_t length) {
  deque<Range>::iterator pos;

  std::lock_guard<std::mutex> lock(mutex_);
  pos = std::lower_bound(segs_.begin(), segs_.end(), offset, cmp_offset);

  assert(pos != segs_.end());
  assert(pos->length() == length);

  segs_.erase(pos);
}

void BlockMap::push_back(uint64_t offset, uint64_t length) {
  Range seg;

  seg.set_offset(offset);
  seg.set_length(length);

  std::lock_guard<std::mutex> lock(mutex_);
  segs_.push_back(seg);
  auto pos = --segs_.end();
  assert(valid(pos));
}

size_t BlockMap::remove_overlap(uint64_t offset, uint64_t length) {
  deque<Range>::iterator pos;
  size_t affected = 0;

  if (!length) return affected;

  std::lock_guard<std::mutex> lock(mutex_);
  if (segs_.empty()) return affected;
  pos = std::lower_bound(segs_.begin(), segs_.end(), offset, cmp_offset);

  // should check previous segment whose offset is smaller
  // but length may be large
  if (pos != segs_.begin()) pos--;

  uint64_t right = offset + length;
  uint64_t pos_right;
  while (pos != segs_.end() && pos->offset() < right) {
    pos_right = pos->offset() + pos->length();
    // segment above is located at pos
    //   -------     -->
    // -----------        ----------
    if (pos->offset() >= offset && pos_right <= right) {
      pos = segs_.erase(pos);
      affected++;
      continue;
    }
    // --------      -->   ---
    //   ---------            ---------
    if (pos->offset() < offset && pos_right > offset && pos_right <= right) {
      pos->set_length(offset - pos->offset());
      pos++;
      affected++;
      continue;
    }
    // -----------    --> ---     ---
    //    -----
    if (pos->offset() < offset && pos_right > right) {
      pos->set_length(offset - pos->offset());
      Range new_seg;
      new_seg.set_offset(right);
      new_seg.set_length(pos_right - right);
      segs_.insert(++pos, new_seg);
      affected++;
      break;
    }
    //     --------  -->            ---
    // ---------           ---------
    if (pos_right > right) {
      pos->set_offset(right);
      pos->set_length(pos_right - right);
      affected++;
      break;
    }
    pos++;
  }

  return affected;
}

void BlockMap::find_next(uint64_t offset, uint64_t *nxt_offset,
                         uint64_t *nxt_length) {
  deque<Range>::iterator it, prev;

  *nxt_offset = 0;
  *nxt_length = 0;

  std::lock_guard<std::mutex> lock(mutex_);
  if (segs_.empty()) return;

  it = std::lower_bound(segs_.begin(), segs_.end(), offset, cmp_offset);
  if (it != segs_.begin()) {
    prev = it - 1;
    if (offset < prev->offset() + prev->length()) {
      *nxt_offset = prev->offset();
      *nxt_length = prev->length();
      return;
    }
  }
  if (it != segs_.end()) {
    *nxt_offset = it->offset();
    *nxt_length = it->length();
  }
}

bool BlockMap::valid(deque<Range>::iterator pos) {
  deque<Range>::iterator prev, next;

  if (pos != segs_.begin()) {
    prev = pos - 1;
    if (prev->offset() + prev->length() > pos->offset()) return false;
  }
  if (pos != segs_.end() - 1) {
    next = pos + 1;
    if (pos->offset() + pos->length() > next->offset()) return false;
  }

  return true;
}

void BlockMap::dump_to_pb(RepeatedPtrField<Range> *ranges) {
  deque<Range>::iterator it;
  Range *seg;

  ranges->Clear();
  for (it = segs_.begin(); it < segs_.end(); ++it) {
    seg = ranges->Add();
    seg->set_offset(it->offset());
    seg->set_length(it->length());
  }
}

void BlockMap::load_from_pb(const RepeatedPtrField<Range> &ranges) {
  Range seg;
  RepeatedPtrIterator<const Range> it;

  segs_.clear();
  for (it = ranges.begin(); it < ranges.end(); ++it) {
    seg.set_offset(it->offset());
    seg.set_length(it->length());
    segs_.push_back(seg);
  }
}

void BlockMap::print() {
  deque<Range>::iterator it;
  std::lock_guard<std::mutex> lock(mutex_);

  std::cout << "Segments(" << segs_.size() << "):" << std::endl;
  for (it = segs_.begin(); it < segs_.end(); ++it)
    std::cout << it - segs_.begin() << ": " << it->offset() << " ("
              << it->length() << ")" << std::endl;

  std::cout << std::endl;
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
