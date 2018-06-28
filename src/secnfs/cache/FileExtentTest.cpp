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
#include <boost/icl/interval_map.hpp>
#include <gtest/gtest.h>

#include "FileExtent.h"

namespace secnfs {
namespace cache {
namespace test {

// Test using boost::icl::interval_map to manage FileCache's address space.
class ExtentMapTest : public ::testing::Test {
 protected:
  void Insert(size_t lower, size_t upper, CacheState state) {
    FileExtent ext;
    ext.set_state(state);
    extents_ += std::make_pair(
        boost::icl::interval<size_t>::right_open(lower, upper), ext);
  }
  boost::icl::interval_map<size_t, FileExtent> extents_;
};

TEST_F(ExtentMapTest, TouchingIntervalsAreJoinedIFFTheValuesAreSame) {
  // joined if the values are the same
  Insert(1, 2, CacheState::CLEAN);
  Insert(2, 3, CacheState::CLEAN);
  auto it = extents_.begin();
  EXPECT_EQ(3, upper(it->first));
  EXPECT_EQ(1, extents_.iterative_size());
  EXPECT_EQ(CacheState::CLEAN, it->second.state());

  // not joined if the values are different
  Insert(3, 4, CacheState::DIRTY);
  EXPECT_EQ(2, extents_.iterative_size());
  it = extents_.begin();
  EXPECT_EQ(3, upper(it->first));
  EXPECT_EQ(3, lower((++it)->first));
  EXPECT_EQ(4, upper(it->first));
  EXPECT_EQ(CacheState::DIRTY, it->second.state());
}

TEST_F(ExtentMapTest, CleanExtentCanBeSplitByDirtyExtent) {
  Insert(1, 4, CacheState::CLEAN);
  Insert(2, 3, CacheState::DIRTY);

  EXPECT_EQ(3, extents_.iterative_size());
  auto it = extents_.begin();
  EXPECT_EQ(CacheState::CLEAN, it->second.state());
  EXPECT_EQ(2, upper(it->first));

  ++it;
  EXPECT_EQ(CacheState::DIRTY, it->second.state());
  EXPECT_EQ(3, upper(it->first));

  ++it;
  EXPECT_EQ(CacheState::CLEAN, it->second.state());
  EXPECT_EQ(4, upper(it->first));
}

TEST_F(ExtentMapTest, DirtyExtentStayDirtyWithoutCleanse) {
  Insert(1, 5, CacheState::DIRTY);
  Insert(2, 3, CacheState::CLEAN);
  Insert(3, 4, CacheState::DIRTY);

  EXPECT_EQ(1, extents_.iterative_size());
  auto it = extents_.begin();
  EXPECT_EQ(CacheState::DIRTY, it->second.state());
  EXPECT_EQ(1, lower(it->first));
  EXPECT_EQ(5, upper(it->first));
}

TEST_F(ExtentMapTest, DirtyExtentCanBeCleansed) {
  Insert(1, 4, CacheState::DIRTY);
  Insert(2, 3, CacheState::CLEANSED);

  EXPECT_EQ(3, extents_.iterative_size());
  auto it = extents_.begin();
  EXPECT_EQ(CacheState::DIRTY, it->second.state());
  EXPECT_EQ(2, upper(it->first));

  ++it;
  EXPECT_EQ(CacheState::CLEAN, it->second.state());
  EXPECT_EQ(3, upper(it->first));

  ++it;
  EXPECT_EQ(CacheState::DIRTY, it->second.state());
  EXPECT_EQ(4, upper(it->first));
}

TEST_F(ExtentMapTest, CleansedExtentCanMergeWithTouchingCleanExtent) {
  Insert(1, 2, CacheState::DIRTY);
  Insert(2, 3, CacheState::CLEAN);
  EXPECT_EQ(2, extents_.iterative_size());

  Insert(1, 2, CacheState::CLEANSED);
  EXPECT_EQ(1, extents_.iterative_size());
  auto it = extents_.begin();
  EXPECT_EQ(1, lower(it->first));
  EXPECT_EQ(3, upper(it->first));
}

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
