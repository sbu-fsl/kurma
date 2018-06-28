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

#include "util/blockmap.h"

namespace secnfs {
namespace util {
namespace test {

TEST(BlockMap, RangeLock) {
  BlockMap bm;
  EXPECT_EQ(2, bm.try_insert(0, 2));
  EXPECT_EQ(3, bm.try_insert(4, 3));
  EXPECT_EQ(0, bm.try_insert(5, 3));
  EXPECT_EQ(1, bm.try_insert(3, 9));
  EXPECT_EQ(0, bm.try_insert(3, 9));
  bm.remove_match(0, 2);
  EXPECT_EQ(3, bm.try_insert(0, 9));
  EXPECT_EQ(9, bm.try_insert(10, 9));
  EXPECT_EQ(0, bm.try_insert(12, 9));
  bm.remove_match(10, 9);
  EXPECT_EQ(9, bm.try_insert(12, 9));
  EXPECT_EQ(2, bm.try_insert(10, 9));
  EXPECT_EQ(1, bm.try_insert(8, 1));

  BlockMap bm2;
  EXPECT_EQ(8192, bm2.try_insert(4096, 8192));
  bm2.remove_match(4096, 8192);
  EXPECT_EQ(8192, bm2.try_insert(0, 8192));
  EXPECT_EQ(8192, bm2.try_insert(8192, 8192));
}

TEST(BlockMap, Holes) {
  BlockMap holes;
  uint64_t off, len;
  holes.push_back(0, 2);
  holes.push_back(3, 2);
  holes.push_back(8, 3);

  holes.find_next(0, &off, &len);
  EXPECT_EQ(0, off);
  EXPECT_EQ(2, len);
  holes.find_next(1, &off, &len);
  EXPECT_EQ(0, off);
  EXPECT_EQ(2, len);
  holes.find_next(2, &off, &len);
  EXPECT_EQ(3, off);
  EXPECT_EQ(2, len);
  holes.find_next(7, &off, &len);
  EXPECT_EQ(8, off);
  EXPECT_EQ(3, len);
  holes.find_next(11, &off, &len);
  EXPECT_EQ(0, off);
  EXPECT_EQ(0, len);

  holes.remove_overlap(1, 9);
  holes.find_next(8, &off, &len);
  EXPECT_EQ(10, off);
  EXPECT_EQ(1, len);

  BlockMap holes2;
  holes2.remove_overlap(0, 100);
  holes2.push_back(0, 100);
  holes2.remove_overlap(0, 50);
  holes2.find_next(0, &off, &len);
  EXPECT_EQ(50, off);
  EXPECT_EQ(50, len);

  BlockMap holes3;
  holes3.push_back(0, 100);
  holes3.remove_overlap(25, 50);
  holes3.find_next(0, &off, &len);
  EXPECT_EQ(0, off);
  EXPECT_EQ(25, len);
  holes3.find_next(50, &off, &len);
  EXPECT_EQ(75, off);
  EXPECT_EQ(25, len);

  BlockMap holes4;
  holes4.push_back(0, 50);
  holes4.remove_overlap(100, 50);
  holes4.find_next(0, &off, &len);
  EXPECT_EQ(0, off);
  EXPECT_EQ(50, len);
}

}  // namespace test
}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
