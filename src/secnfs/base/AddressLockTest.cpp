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
// Unittest for AddressLock.

#include <string.h>

#include <gtest/gtest.h>
#include <gmock/gmock.h>
#include <glog/logging.h>

#include "base/AddressLock.h"

namespace secnfs {
namespace base {
namespace test {

class LockValueTest : public ::testing::Test {
 protected:
  bool IsCompatible(unsigned short a, unsigned short b) const {
    LockValue lva(a);
    LockValue lvb(b);
    return lva.IsCompatible(lvb);
  }
};

TEST_F(LockValueTest, Validity) {
  EXPECT_TRUE(LockValue::IsValid(0));
  EXPECT_TRUE(LockValue::IsValid(1));
  EXPECT_TRUE(LockValue::IsValid(2));
  EXPECT_FALSE(LockValue::IsValid(3));
  EXPECT_TRUE(LockValue::IsValid(4));
  EXPECT_FALSE(LockValue::IsValid(5));
}

TEST_F(LockValueTest, Construction) {
  LockValue value;
  EXPECT_TRUE(value.IsUnlocked());
  EXPECT_FALSE(value.IsLocked());
  EXPECT_FALSE(value.IsReadLocked());
  EXPECT_FALSE(value.IsWriteLocked());

  LockValue read(false);
  EXPECT_TRUE(read.IsReadLocked());
  EXPECT_FALSE(read.IsUnlocked());
  EXPECT_FALSE(read.IsWriteLocked());

  LockValue write(true);
  EXPECT_TRUE(write.IsWriteLocked());
  EXPECT_FALSE(write.IsUnlocked());
  EXPECT_FALSE(write.IsReadLocked());
}

TEST_F(LockValueTest, Compatibility) {
  EXPECT_TRUE(IsCompatible(0, 0));
  EXPECT_TRUE(IsCompatible(0, 1));
  EXPECT_TRUE(IsCompatible(0, 2));
  EXPECT_TRUE(IsCompatible(2, 2));
  EXPECT_FALSE(IsCompatible(1, 1));
  EXPECT_FALSE(IsCompatible(2, 1));
}

class AddressLockTest : public ::testing::Test {
 protected:
  AddressLock lock_;
};

TEST_F(AddressLockTest, InitialStates) {
  EXPECT_FALSE(lock_.IsLocked(0, 4));
  EXPECT_FALSE(lock_.IsWriteLocked(0, 4));
  EXPECT_TRUE(lock_.TryReadLock(0, 4));
  EXPECT_TRUE(lock_.TryWriteLock(4, 4));
}

TEST_F(AddressLockTest, ReadLocksAreCompatible) {
  EXPECT_TRUE(lock_.TryReadLock(0, 4));
  EXPECT_TRUE(lock_.TryReadLock(0, 4));
  EXPECT_TRUE(lock_.TryReadLock(2, 4));
  lock_.ReadUnlock(0, 4);
  lock_.ReadUnlock(0, 4);
  EXPECT_TRUE(lock_.IsLocked(0, 4));
  EXPECT_TRUE(lock_.IsLocked(4, 4));
  lock_.ReadUnlock(2, 4);
  EXPECT_FALSE(lock_.IsLocked(0, 8));
}

TEST_F(AddressLockTest, ReadLocksBlockWriteLocks) {
  EXPECT_TRUE(lock_.TryReadLock(4, 4));
  EXPECT_FALSE(lock_.TryWriteLock(2, 4));
  EXPECT_FALSE(lock_.TryWriteLock(6, 4));
  lock_.ReadUnlock(4, 4);
  EXPECT_TRUE(lock_.TryWriteLock(2, 4));
}

TEST_F(AddressLockTest, WriteLocksBlockAnyLocks) {
  EXPECT_TRUE(lock_.TryWriteLock(4, 4));
  EXPECT_FALSE(lock_.TryReadLock(2, 4));
  EXPECT_FALSE(lock_.TryWriteLock(6, 4));
  lock_.WriteUnlock(4, 4);
  EXPECT_TRUE(lock_.TryReadLock(2, 4));
  EXPECT_TRUE(lock_.TryWriteLock(6, 4));
}

}  // namespace test
}  // namespace base
}  // namespace secnfs

// vim:sw=2:sts=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
