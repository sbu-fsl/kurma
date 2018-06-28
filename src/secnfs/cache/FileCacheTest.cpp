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
#include <boost/filesystem.hpp>
#include <gtest/gtest.h>
#include <glog/logging.h>

#include <string.h>
#include <string>

#include "cache/FileCache.h"
#include "cache/DirtyExtent.h"
#include "util/fileutil.h"
#include "util/random.h"
#include "util/testutil.h"
#include "util/common.h"

// NOLINTNEXTLINE
using namespace secnfs::util;

namespace secnfs {
namespace cache {
namespace test {

static const size_t cache_size_mb = 2 * 16;
static const std::string kFileDir = "/tmp/cache-file-dir";
static const std::string kMetaDir = "/tmp/cache-meta-dir";

class FileCacheTest : public ::testing::TestWithParam<CacheState> {
 public:
  FileCacheTest()
      : file_cache_("handle000", kFileDir, kMetaDir, 0),
        rnd_(8887) {}

  void SetUp() override {
    CreateDirRecursively("/tmp/cache-file-dir");
    CreateDirRecursively("/tmp/cache-meta-dir");
    if (!FileExists(file_cache_.file_path())) {
      EXPECT_EQ(0, file_cache_.Create());
    }
  }

  void TearDown() override {
    if (FileExists(file_cache_.file_path())) {
      DeleteFile(file_cache_.file_path());
    }
  }

 protected:
  ssize_t InsertExtent(size_t offset, size_t length) {
    std::string buf;
    RandomString(&rnd_, length, &buf);
    return file_cache_.Insert(offset, length, buf.data(), GetParam());
  }

  void ExpectLookup(size_t offset, size_t length, ssize_t expected_result,
                    size_t exp_offset, size_t exp_length) {
    char* buf = new char[length];
    size_t cached_offset = 0;
    size_t cached_length = 0;
    ssize_t res =
        file_cache_.Lookup(offset, length, buf, &cached_offset, &cached_length);
    EXPECT_EQ(expected_result, res);
    EXPECT_EQ(exp_offset, cached_offset);
    EXPECT_EQ(exp_length, cached_length);
    delete[] buf;
  }

  void ExpectDirty(size_t offset, size_t length, size_t max_length,
                   size_t exp_offset, size_t exp_length) {
    VLOG(5) << "query: [" << offset << ", " << (offset + length) << "); "
            << "max_length: " << max_length << "; expecting: [" << exp_offset
            << ", " << (exp_offset + exp_length) << ").";
    DirtyExtent de(nullptr, &file_cache_, offset, length, 0);
    const char *buf;
    ssize_t ret = file_cache_.ReadAndLockDirty(max_length, &de, &buf);
    EXPECT_EQ(exp_offset, de.offset());
    EXPECT_EQ(exp_length, de.length());
    EXPECT_EQ(exp_length, ret);

    if (ret > 0) {
      file_cache_.CleanseAndUnlockDirty(exp_offset, exp_length, buf);
    }
  }

  FileCache file_cache_;
  secnfs::util::Random rnd_;
};

TEST_P(FileCacheTest, EmptyFileCacheHasCorrectState) {
  EXPECT_EQ(0, file_cache_.Size());
  ExpectLookup(0, 1_b, 0, 0, 0);
}

TEST_P(FileCacheTest, InsertWorks) {
  EXPECT_EQ(1_b, InsertExtent(0, 1_b));
  EXPECT_EQ(1_b, file_cache_.Size());
  EXPECT_EQ(1_b, GetFileSize(file_cache_.file_path()));
}

TEST_P(FileCacheTest, ClearOnExitDoesNotDeleteFile) {
  EXPECT_EQ(1_b, InsertExtent(0, 1_b));
  EXPECT_EQ(0, file_cache_.Clear(true));
  EXPECT_TRUE(FileExists(file_cache_.file_path()));
  EXPECT_EQ(1_b, GetFileSize(file_cache_.file_path()));
}

TEST_P(FileCacheTest, ClearOnEvictionDeleteFile) {
  EXPECT_EQ(1_b, InsertExtent(0, 1_b));
  EXPECT_TRUE(FileExists(file_cache_.file_path()));
  EXPECT_EQ(0, file_cache_.Clear(false));
  EXPECT_FALSE(FileExists(file_cache_.file_path()));
}

TEST_P(FileCacheTest, SimpleLookupWorks) {
  EXPECT_EQ(2_b, InsertExtent(1_b, 2_b));
  ExpectLookup(0, 2_b * 2, MIDDLE_MATCH, 1_b, 2_b);
  ExpectLookup(2_b, 2_b, FRONT_MATCH, 2_b, 1_b);
  ExpectLookup(0, 2_b, BACK_MATCH, 1_b, 1_b);
  ExpectLookup(1_b, 2_b, FULL_MATCH, 1_b, 2_b);
}

// 4KB block map:
// +---+---+---+---+---+---+
// |   |xxx|xxx|   |xxx|xxx|
// +---+---+---+---+---+---+
TEST_P(FileCacheTest, MatchOverTwoDisjointExtents) {
  EXPECT_EQ(2_b, InsertExtent(1_b, 2_b));
  EXPECT_EQ(4_b, InsertExtent(4_b, 2_b));
  ExpectLookup(0, 4_b, MIDDLE_MATCH, 1_b, 2_b);
  ExpectLookup(1_b, 4_b, FRONT_MATCH, 1_b, 2_b);
  ExpectLookup(2_b, 4_b, FRONT_MATCH, 2_b, 1_b);
}

TEST_P(FileCacheTest, InsertOverlappedExtents) {
  EXPECT_EQ(2_b, InsertExtent(0_b, 2_b));
  EXPECT_EQ(3_b, InsertExtent(1_b, 2_b));
  ExpectLookup(0, 4_b, FRONT_MATCH, 0, 3_b);
}

// 4KB block map:
// +---+---+---+---+---+---+
// |   |xxx|   |   |xxx|   |
// +---+---+---+---+---+---+
TEST_P(FileCacheTest, MetadataIsCorrectlyLoaded) {
  EXPECT_EQ(1_b, InsertExtent(1_b, 1_b));
  EXPECT_EQ(2_b, InsertExtent(4_b, 1_b));
  EXPECT_EQ(0, file_cache_.Commit(0, 5_b));
  EXPECT_EQ(0, file_cache_.Clear(true));
  FileCache other(file_cache_.handle(), kFileDir, kMetaDir, 0);
  EXPECT_EQ(2_b, other.Load());
  ExpectLookup(1_b, 1_b, 3, 1_b, 1_b);
  ExpectLookup(2_b, 2_b, 0, 0, 0);
  ExpectLookup(4_b, 1_b, 3, 4_b, 1_b);
  ExpectLookup(5_b, 1_b, 0, 0, 0);
}

// 4KB block map:
// +---+---+---+---+---+---+
// |   |xxx|xxx|   |xxx|xxx|
// +---+---+---+---+---+---+
TEST_P(FileCacheTest, LookupCanMatchTheFirstMiddleExtent) {
  EXPECT_EQ(2_b, InsertExtent(1_b, 2_b));
  EXPECT_EQ(4_b, InsertExtent(4_b, 2_b));
  ExpectLookup(0, 7_b, MIDDLE_MATCH, 1_b, 2_b);
}

// 4KB block map: [1, 4) + [5, 7)
// +---+---+---+---+---+---+---+
// |   |xxx|xxx|xxx|   |xxx|xxx|
// +---+---+---+---+---+---+---+
TEST_P(FileCacheTest, ReadAndLockDirtyBasics) {
  EXPECT_EQ(3_b, InsertExtent(1_b, 3_b));  // [1, 4)
  EXPECT_EQ(5_b, InsertExtent(5_b, 2_b));  // [5, 7)
  if (GetParam() == CacheState::DIRTY) {
    ExpectDirty(0, 1_b, 1_b, 0, 0);
    // [1, 2) -> [1, 2), max_length enfored
    ExpectDirty(1_b, 1_b, 1_b, 1_b, 1_b);
    // [1, 2) -> [0, 0), already cleansed
    ExpectDirty(1_b, 1_b, 2_b, 1_b, 0);
    // [1, 3) -> [2, 4), stop at the clean block
    ExpectDirty(1_b, 2_b, 5_b, 2_b, 2_b);
    // [6, 8) -> [6, 7), read the last block
    ExpectDirty(6_b, 2_b, 2_b, 6_b, 1_b);
  } else {
    ExpectDirty(0, 1_b, 1_b, 0, 0);
    ExpectDirty(1_b, 1_b, 1_b, 1_b, 0);
    ExpectDirty(1_b, 1_b, 1_b, 1_b, 0);
    ExpectDirty(1_b, 2_b, 2_b, 1_b, 0);
    ExpectDirty(6_b, 2_b, 2_b, 6_b, 0);
  }
}

TEST_P(FileCacheTest, InvalidateDirtyData) {
  EXPECT_EQ(3_b, InsertExtent(0, 3_b));  // [0, 3)
  if (GetParam() == CacheState::DIRTY) {
    EXPECT_EQ(3_b, file_cache_.DirtyDataSize());
  } else {
    ExpectDirty(0, 3_b, 3_b, 0, 0);
  }
  file_cache_.Invalidate(2_b, 1_b, true);
  if (GetParam() == CacheState::DIRTY) {
    EXPECT_EQ(2_b, file_cache_.DirtyDataSize());
  } else {
    ExpectDirty(0, 3_b, 3_b, 0, 0);
  }
}

INSTANTIATE_TEST_CASE_P(CleanAndDirty, FileCacheTest,
                        ::testing::Values(CacheState::CLEAN,
                                          CacheState::DIRTY));

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
