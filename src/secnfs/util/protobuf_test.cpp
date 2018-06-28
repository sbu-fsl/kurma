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

#include "proto/Util.pb.h"
#include "util/protobuf.h"

using secnfs::proto::Range;

namespace secnfs {
namespace util {
namespace test {

static const char* test_file = "/tmp/protobuf_test_file";

TEST(ProtobufTest, MessagesAreCorrectlySavedAndLoaded) {
  Range range;
  range.set_offset(123);
  range.set_length(456);
  EXPECT_GT(WriteMessageToFile(range, test_file), 0);

  Range copy;
  EXPECT_GT(ReadMessageFromFile(test_file, &copy), 0);
  EXPECT_EQ(range.offset(), copy.offset());
  EXPECT_EQ(range.length(), copy.length());
}

}  // namespace test
}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:sts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
