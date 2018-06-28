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
#include <boost/icl/interval_set.hpp>
#include <boost/icl/interval_map.hpp>
#include <gtest/gtest.h>
#include <glog/logging.h>

#include "util/common.h"
#include "util/iclutil.h"
#include "util/testutil.h"

namespace secnfs {
namespace util {
namespace test {

class IntervalSetTest : public ::testing::Test {
 protected:
  // NOTE: for interval_map, insert is different from add
  void InsertInterval(size_t lower, size_t upper) {
    extents_.insert(make_interval2(lower, upper));
  }

  void SubtractInterval(size_t lower, size_t upper) {
    extents_.subtract(make_interval2(lower, upper));
  }

  boost::icl::interval_set<size_t>::iterator Find(size_t lower, size_t upper) {
    return boost::icl::find(extents_, make_interval2(lower, upper));
  }

  boost::icl::interval_set<size_t> extents_;
};

TEST_F(IntervalSetTest, TouchingIntervalsAreJoined) {
  InsertInterval(0, 1_b);
  InsertInterval(1_b, 2_b);
  InsertInterval(10_b, 20_b);

  int i = 0;
  size_t ext_upper[2] = {2_b, 20_b};
  for (auto const& ext : extents_) {
    size_t val = ext_upper[i++];
    EXPECT_EQ(val, upper(ext));
  }
}

TEST_F(IntervalSetTest, FindFirstAndLastOverlaps) {
  InsertInterval(0, 1_b);
  InsertInterval(1_b, 2_b);
  InsertInterval(10_b, 20_b);

  auto it = Find(1_b, 10_b);
  auto overlap = make_interval2(1_b, 10_b) & (*it);
  EXPECT_EQ(1_b, lower(overlap));
  EXPECT_EQ(2_b, upper(overlap));
}

TEST_F(IntervalSetTest, FindOverlapAtBack) {
  InsertInterval(1_b, 1_b + 2_b);
  auto it = Find(0, 2_b);
  auto overlap = make_interval2(0, 2_b) & (*it);
  EXPECT_EQ(1_b, lower(overlap));
  EXPECT_EQ(2_b, upper(overlap));
}

TEST_F(IntervalSetTest, NonexistingRangeAreNotFound) {
  InsertInterval(0, 1_b);
  InsertInterval(2_b, 1_b + 2_b);

  auto it = Find(1_b, 2_b);
  EXPECT_EQ(extents_.end(), it);
}

TEST_F(IntervalSetTest, FindWorksWithRightOpenRanges) {
  InsertInterval(1, 2);
  auto it = Find(0, 1);
  EXPECT_EQ(extents_.end(), it);
}

TEST_F(IntervalSetTest, SubtractCanBreakRanges) {
  InsertInterval(1, 4);
  EXPECT_EQ(1, extents_.iterative_size());
  SubtractInterval(2, 3);
  EXPECT_EQ(2, extents_.iterative_size());
  SubtractInterval(1, 2);
  EXPECT_EQ(1, extents_.iterative_size());
  SubtractInterval(3, 4);
  EXPECT_EQ(0, extents_.iterative_size());
}

// Test using boost::icl::interval_map to manage FileCache's address space.
class IntervalMapTest : public ::testing::Test {
 protected:
  void Insert(size_t lower, size_t upper, int value) {
    // Note that boost::icl::interval_map::add() and
    // boost::icl::interval_map::insert() have different semantics.
    extents_.add(std::make_pair(
        boost::icl::interval<size_t>::right_open(lower, upper), value));
  }
  boost::icl::interval_map<size_t, int> extents_;
};

TEST_F(IntervalMapTest, TouchingIntervalsAreJoinedIFFTheValuesAreSame) {
  // joined if the values are the same
  Insert(1, 2, 1);
  EXPECT_EQ(1, boost::icl::length(extents_));
  Insert(2, 3, 1);
  auto it = extents_.begin();
  EXPECT_EQ(3, upper(it->first));
  EXPECT_EQ(1, extents_.iterative_size());
  EXPECT_EQ(2, boost::icl::length(extents_));

  // not joined if the values are different
  Insert(3, 4, 2);
  EXPECT_EQ(2, extents_.iterative_size());
  it = extents_.begin();
  EXPECT_EQ(3, upper(it->first));
  EXPECT_EQ(3, lower((++it)->first));
  EXPECT_EQ(4, upper(it->first));
}

TEST_F(IntervalMapTest, IntervalCanBeSplitUponIntersection) {
  Insert(1, 3, 1);
  Insert(2, 4, 2);
  EXPECT_EQ(3, extents_.iterative_size());

  auto it = extents_.begin();
  EXPECT_EQ(2, upper(it->first));
  EXPECT_EQ(1, it->second);

  ++it;
  EXPECT_EQ(3, upper(it->first));
  EXPECT_EQ(1 + 2, it->second);

  ++it;
  EXPECT_EQ(4, upper(it->first));
  EXPECT_EQ(2, it->second);
}

TEST_F(IntervalMapTest, IntervalCanBeSplitUponOverlaps) {
  Insert(1, 4, 1);
  Insert(2, 3, 1);
  EXPECT_EQ(3, extents_.iterative_size());

  auto it = extents_.begin();
  EXPECT_EQ(2, upper(it->first));
  EXPECT_EQ(1, it->second);

  ++it;
  EXPECT_EQ(3, upper(it->first));
  EXPECT_EQ(1 + 1, it->second);

  ++it;
  EXPECT_EQ(4, upper(it->first));
  EXPECT_EQ(1, it->second);
}

TEST_F(IntervalMapTest, RangeSearchWillFindAllOverlaps) {
  // 1 2 3 4 5 6 7 8 9
  // X X   X X   X X
  Insert(1, 3, 1);
  Insert(4, 6, 1);
  Insert(7, 9, 1);

  // Match at both ends of the target range [2, 8).
  auto it = extents_.find(make_interval2(2, 8));
  EXPECT_EQ(1, lower(it->first));
  ++it;
  EXPECT_EQ(4, lower(it->first));
  ++it;
  EXPECT_EQ(7, lower(it->first));

  // Match in the middle of the target range [3, 7).
  it = extents_.find(make_interval2(3, 7));
  EXPECT_EQ(4, lower(it->first));

  // Empty range return end().
  it = extents_.find(make_interval2(3, 4));
  EXPECT_EQ(extents_.end(), it);
}

TEST_F(IntervalMapTest, ErasureCanBreakIntervals) {
  Insert(0, 3, 1);
  EXPECT_EQ(1, extents_.iterative_size());
  extents_.erase(make_interval2(1, 2));
  EXPECT_EQ(2, extents_.iterative_size());

  auto it = extents_.begin();
  EXPECT_EQ(0, lower(it->first));
  EXPECT_EQ(1, upper(it->first));
  ++it;
  EXPECT_EQ(2, lower(it->first));
  EXPECT_EQ(3, upper(it->first));
}

TEST_F(IntervalMapTest, OverlappedExtentsWithSameValueWontBreak) {
  Insert(0, 5, 1);
  EXPECT_EQ(1, extents_.iterative_size());
  // 0 + 1 == 1
  Insert(1, 2, 0);
  EXPECT_EQ(1, extents_.iterative_size());
  Insert(1, 2, 1);
  EXPECT_EQ(3, extents_.iterative_size());
}

}  // namespace test
}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
