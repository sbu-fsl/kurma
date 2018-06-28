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
#include <string.h>

#include <gtest/gtest.h>
#include <gmock/gmock.h>
#include <glog/logging.h>

#include "base/PathMapping.h"
#include "util/fileutil.h"
#include "util/random.h"
#include "util/testutil.h"
#include "util/common.h"

using secnfs::util::Slice;

namespace secnfs {
namespace base {
namespace test {

class PathMappingTest : public ::testing::Test {
 protected:
  void SetUp() override {
    mapping_.Insert("fh_home", "/home");
    mapping_.Insert("fh_mchen", "/home/mchen");
    mapping_.Insert("fh_xxxx", "/home/mchen/xxxx");
  }

  bool HasPath(Slice fh, Slice path) const {
    const auto& paths = mapping_.HandleToPaths(fh);
    return paths.find(path.rtrim('/').ToString()) != paths.end();
  }

  void ExpectPathAndHandle(Slice fh, Slice path) const {
    EXPECT_EQ(fh, mapping_.PathToHandle(path));
    EXPECT_TRUE(HasPath(fh, path));
  }

  PathMapping mapping_;
};

TEST_F(PathMappingTest, TestInitializedCorrectly) {
  ExpectPathAndHandle("fh_home", "/home");
  ExpectPathAndHandle("fh_home", "/home/");
  ExpectPathAndHandle("fh_mchen", "/home/mchen");
  ExpectPathAndHandle("fh_xxxx", "/home/mchen/xxxx");
}

TEST_F(PathMappingTest, InsertAtWorks) {
  EXPECT_EQ("/home/arun", mapping_.InsertAt("fh_home", "fh_arun", "arun"));
  EXPECT_EQ("/home/kelong",
            mapping_.InsertAt("fh_home", "fh_kelong", "kelong"));
  ExpectPathAndHandle("fh_arun", "/home/arun");
  ExpectPathAndHandle("fh_kelong", "/home/kelong/");
  // duplicate insert does not count
  EXPECT_EQ("/home/arun", mapping_.InsertAt("fh_home", "fh_arun", "arun"));
  EXPECT_EQ(1, mapping_.HandleToPaths("fh_arun").size());
}

TEST_F(PathMappingTest, HardLinkWorks) {
  mapping_.Insert("fh_xxxx", "/home/mchen/xhardlink");
  ExpectPathAndHandle("fh_xxxx", "/home/mchen/xhardlink");
  EXPECT_THAT(mapping_.HandleToPaths("fh_xxxx"),
              ::testing::UnorderedElementsAre("/home/mchen/xhardlink",
                                              "/home/mchen/xxxx"));
  // unlink one
  mapping_.Delete("fh_mchen", "xxxx");
  ExpectPathAndHandle("fh_xxxx", "/home/mchen/xhardlink");
  EXPECT_THAT(mapping_.HandleToPaths("fh_xxxx"),
              ::testing::UnorderedElementsAre("/home/mchen/xhardlink"));

  // unlink all
  mapping_.Delete("fh_mchen", "xhardlink/");
  EXPECT_TRUE(mapping_.HandleToPaths("fh_xxxx").empty());
}

TEST_F(PathMappingTest, DeleteWorks) {
  // delete file
  mapping_.Delete("fh_mchen", "xxxx");
  EXPECT_TRUE(mapping_.PathToHandle("/home/mchen/xxxx").empty());
  ExpectPathAndHandle("fh_mchen", "/home/mchen");

  // delete directory
  mapping_.Delete("fh_home", "mchen/");
  EXPECT_TRUE(mapping_.PathToHandle("/home/mchen/xxxx").empty());
  EXPECT_TRUE(mapping_.PathToHandle("/home/mchen").empty());
}

}  // namespace test
}  // namespace base
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
