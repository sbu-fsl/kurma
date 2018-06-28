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
// A simple lock manager of a file's address space.

#pragma once

#include <glog/logging.h>
#include <boost/icl/interval_map.hpp>
#include <mutex>
#include <utility>

#include "util/iclutil.h"

namespace secnfs {
namespace base {

class LockValue {
 public:
  LockValue() : value_(0) {}
  explicit LockValue(bool is_write) : value_(is_write ? 1 : 2) {}
  explicit LockValue(uint16_t val) : value_(val) {
    CHECK(IsValid(value_));
  }

  LockValue& operator+=(const LockValue& lv) {
    CHECK(IsCompatible(lv)) << "lock conflicts of " << value_ << " and "
                            << lv.value_;
    value_ += lv.value_;
    return *this;
  }

  LockValue& operator-=(const LockValue& lv) {
    if (lv.IsWriteLocked()) {
      CHECK(IsWriteLocked());
    } else if (lv.IsReadLocked()) {
      CHECK(IsReadLocked());
    }
    CHECK_GE(value_, lv.value_);
    value_ -= lv.value_;
    return *this;
  }

  bool operator==(const LockValue& lv) const { return value_ == lv.value_; }

  void ReadLock() {
    CHECK(!IsWriteLocked());
    value_ += 2;
  }
  void WriteLock() {
    CHECK(IsUnlocked());
    value_ = 1;
  }

  bool IsUnlocked() const { return value_ == 0; }
  bool IsLocked() const { return value_ > 0; }
  bool IsReadLocked() const { return (value_ >= 2) && ((value_ & 0x1) == 0); }
  bool IsWriteLocked() const { return value_ == 1; }
  bool IsCompatible(const LockValue& lv) const {
    return !((IsWriteLocked() && lv.IsLocked()) ||
             (IsLocked() && lv.IsWriteLocked()));
  }

  static bool IsValid(uint16_t val) {
    return val <= 1 || (val & 0x1) == 0;
  }

 private:
  // The least significant bit (LSB) of the "uint16_t" is for write locks,
  // and the rest bits are for read locks.
  uint16_t value_ = 0;
};

// AddressLock is NOT thread-safe.  External synchronization is required.
// TODO(mchen): add support of truncate
class AddressLock {
 public:
  typedef boost::icl::discrete_interval<size_t>::type RangeT;
  typedef std::pair<RangeT, LockValue> ValueT;

  // Return true if any byte within [offset, offset + length) is locked.
  bool IsLocked(size_t offset, size_t length) const {
    auto range = secnfs::util::make_interval(offset, length);
    auto first = space_.find(range);
    for (auto it = first;
         it != space_.end() && it->first.lower() < range.upper(); ++it) {
      if (it->second.IsLocked()) {
        return true;
      }
    }
    return false;
  }

  // Return true if any byte within [offset, offset + length) is write-locked.
  bool IsWriteLocked(size_t offset, size_t length) const {
    auto range = secnfs::util::make_interval(offset, length);
    auto first = space_.find(range);
    for (auto it = first;
         it != space_.end() && it->first.lower() < range.upper(); ++it) {
      if (it->second.IsWriteLocked()) {
        return true;
      }
    }
    return false;
  }

  bool TryWriteLock(size_t offset, size_t length) {
    if (IsLocked(offset, length)) {
      return false;
    }
    space_.add(WriteLockRange(offset, length));
    return true;
  }

  bool TryReadLock(size_t offset, size_t length) {
    if (IsWriteLocked(offset, length)) {
      return false;
    }
    space_.add(ReadLockRange(offset, length));
    return true;
  }

  void WriteUnlock(size_t offset, size_t length) {
    space_.subtract(WriteLockRange(offset, length));
  }

  void ReadUnlock(size_t offset, size_t length) {
    space_.subtract(ReadLockRange(offset, length));
  }

  static ValueT ReadLockRange(size_t offset, size_t length) {
    return make_pair(secnfs::util::make_interval(offset, length),
                     LockValue(false));
  }
  static ValueT WriteLockRange(size_t offset, size_t length) {
    return make_pair(secnfs::util::make_interval(offset, length),
                     LockValue(true));
  }

 private:
  boost::icl::interval_map<size_t, LockValue> space_;
};

class AddressLockReleaser {
 public:
  // NOLINTNEXTLINE
  AddressLockReleaser(AddressLock& lock, std::mutex& mutex, size_t offset,
                      size_t length, bool isread)
      : lock_(lock),
        mutex_(mutex),
        offset_(offset),
        length_(length),
        isread_(isread) {}

  ~AddressLockReleaser() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (isread_) {
      lock_.ReadUnlock(offset_, length_);
    } else {
      lock_.WriteUnlock(offset_, length_);
    }
  }

 private:
  AddressLock& lock_;
  std::mutex& mutex_;
  size_t offset_;
  size_t length_;
  bool isread_;
};

}  // namespace base
}  // namespace secnfs

// vim:sw=2:sts=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
