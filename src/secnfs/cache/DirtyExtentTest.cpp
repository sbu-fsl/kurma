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
#include <glog/logging.h>
#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include <random>
#include <string>

#include "cache/DirtyExtent.h"
#include "util/common.h"
#include "util/fileutil.h"

using secnfs::util::CacheHandle;
using secnfs::util::CreateDirRecursively;
using secnfs::util::DeleteDirRecursively;
using secnfs::util::IsDirectory;

namespace secnfs {
namespace cache {
namespace test {

class DirtyExtentTest : public ::testing::Test {
};

TEST_F(DirtyExtentTest, Basics) {
  DirtyExtent de(nullptr, nullptr, 0, 4096, 0);
  EXPECT_TRUE(de.IsDue());
  EXPECT_FALSE(de.IsEmpty());

  EXPECT_FALSE(de.Trim(1024, 8192));
  EXPECT_TRUE(de.Trim(0, 8192));
}

TEST_F(DirtyExtentTest, AllSortsOfTrims) {
  DirtyExtent de(nullptr, nullptr, 1_b, 10_b, 1);
  EXPECT_FALSE(de.IsDue());

  EXPECT_FALSE(de.Trim(0, 1024));
  EXPECT_FALSE(de.Trim(0, 1_b));
  EXPECT_EQ(1_b, de.offset_);

  EXPECT_FALSE(de.Trim(0, 2_b));
  EXPECT_EQ(2_b, de.offset_);
  EXPECT_EQ(9_b, de.length_);

  EXPECT_FALSE(de.Trim(2_b, 1_b));
  EXPECT_EQ(3_b, de.offset_);
  EXPECT_EQ(8_b, de.length_);

  EXPECT_FALSE(de.Trim(10_b, 1_b));
  EXPECT_EQ(3_b, de.offset_);
  EXPECT_EQ(7_b, de.length_);

  EXPECT_TRUE(de.Trim(2_b, 9_b));
  EXPECT_EQ(0, de.length_);

  DirtyExtent de2(nullptr, nullptr, 1_b, 3_b, 1);
  // de2.Trim(2_b, 1_b);
  EXPECT_TRUE(de2.Trim(0, 5_b));
}

TEST_F(DirtyExtentTest, EarlierIsSmaller) {
  DirtyExtent de1(nullptr, nullptr, 0, 1_b, 0);
  DirtyExtent de2(nullptr, nullptr, 1_b, 2_b, 0);
  EXPECT_TRUE(de1 <= de2);
  EXPECT_FALSE(de2 < de1);

  DirtyExtent de3(nullptr, nullptr, 0, 1_b, 1);
  DirtyExtent de4(nullptr, nullptr, 1_b, 2_b, 2);
  EXPECT_TRUE(de3 < de4);
}

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
