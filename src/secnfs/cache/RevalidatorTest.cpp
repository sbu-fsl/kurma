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
#include <gtest/gtest.h>

#include "cache/Revalidator.h"
#include "util/slice.h"

namespace secnfs {
namespace cache {
namespace test {

class RevalidatorTest : public ::testing::Test {
 public:
  void SetUp() override {
  }

  void TearDown() override {
  }

  void ExpectGet(const util::Slice& fh, uint64_t time) {
    EXPECT_EQ(time, revalidator_.GetRemoteChangeTime(fh));
  }

  void Add(const util::Slice& fh, uint64_t time) {
    revalidator_.AddRemoteChangeTime(fh, time);
  }

  void Delete(const util::Slice& fh) {
    revalidator_.DeleteRemoteChangeTime(fh);
  }

 protected:
  Revalidator revalidator_;
};

TEST_F(RevalidatorTest, EmptyRevalidatorReturnsZero) {
  ExpectGet("aaa", 0);
  ExpectGet("bbb", 0);
}

TEST_F(RevalidatorTest, DeleteNonExistingEntriesIsOk) {
  Add("aaa", 100);
  Delete("bbb");
  Delete("aaa");
  ExpectGet("aaa", 0);
  Delete("aaa");
}

TEST_F(RevalidatorTest, GetWhatIsPut) {
  Add("aaa", 123);
  ExpectGet("aaa", 123);
  Add("bbb", 234);
  ExpectGet("aaa", 123);
  ExpectGet("bbb", 234);
}

TEST_F(RevalidatorTest, LargeValueCanOverrideSmallValues) {
  Add("aaa", 100);
  ExpectGet("aaa", 100);
  Add("aaa", 200);
  ExpectGet("aaa", 200);
  Add("aaa", 300);
  ExpectGet("aaa", 300);

  Delete("aaa");
  Delete("aaa");
  ExpectGet("aaa", 300);
  Delete("aaa");
  ExpectGet("aaa", 0);
}

TEST_F(RevalidatorTest, MixedPutAndDeletions) {
  Add("aaa", 100);
  Add("bbb", 200);
  ExpectGet("bbb", 200);
  ExpectGet("aaa", 100);

  Add("aaa", 200);
  Add("bbb", 300);
  ExpectGet("aaa", 200);
  ExpectGet("bbb", 300);

  Delete("bbb");
  Delete("aaa");
  ExpectGet("aaa", 200);
  ExpectGet("bbb", 300);

  Add("aaa", 300);
  Delete("bbb");
  ExpectGet("aaa", 300);
  ExpectGet("bbb", 0);

  Delete("aaa");
  Delete("aaa");
  ExpectGet("aaa", 0);
}

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
