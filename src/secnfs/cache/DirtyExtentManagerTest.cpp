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
#include <vector>

#include "cache/DirtyExtentManager.h"
#include "capi/cpputil.h"
#include "util/common.h"
#include "util/fileutil.h"

using secnfs::capi::FillConstBuffer;
using secnfs::util::CacheHandle;
using secnfs::util::CreateDirRecursively;
using secnfs::util::DeleteDirRecursively;
using secnfs::util::IsDirectory;

namespace secnfs {
namespace cache {
namespace test {

static const std::string kFileDir = "/tmp/DirtyExtentTest/cache-file-dir";
static const std::string kMetaDir = "/tmp/DirtyExtentTest/cache-meta-dir";

class DirtyExtentManagerTest : public ::testing::Test {
 public:
  void SetUp() override {
    if (IsDirectory(kFileDir)) DeleteDirRecursively(kFileDir);
    if (IsDirectory(kMetaDir)) DeleteDirRecursively(kMetaDir);
    EXPECT_EQ(0, CreateDirRecursively(kFileDir));
    EXPECT_EQ(0, CreateDirRecursively(kMetaDir));
    cache_ = secnfs::util::NewCache(16 << 20);  // 64MB
    file_cache_ = new FileCache("test_file_handle", kFileDir, kMetaDir, 0);
    dem_ = new DirtyExtentManager(cache_);
    ASSERT_EQ(0, file_cache_->Create());
    VLOG(3) << "testing directories created";
  }

  void TearDown() override {
    delete cache_;
    delete dem_;
    delete file_cache_;
  }

 protected:
  void InsertDirty(size_t offset, size_t length, int sec = 0) {
    char* buf = new char[length];
    file_cache_->Insert(offset, length, buf, CacheState::DIRTY);
    CacheHandle* handle = cache_->Insert(
        file_cache_->handle(), file_cache_, file_cache_->Size(),
        [](const Slice& key, void* value, bool exiting) {
          auto fc = reinterpret_cast<FileCache*>(value);
          fc->Clear(exiting);
        });
    DirtyExtent* de = new DirtyExtent(handle, file_cache_, offset, length, sec);
    file_cache_->Lock();
    FileCacheLockReleaser releaser(file_cache_);
    dem_->InsertDirtyExtent(de);
  }

  void ExpectDirtyAndCleanse(size_t max_length, size_t exp_offset,
                             size_t exp_length) {
    Cleanse(exp_offset, exp_length,
            ExpectDirty(max_length, exp_offset, exp_length));
  }

  const char* ExpectPoll(const char* fh, size_t max_length, bool has,
                         size_t exp_offset, size_t exp_length) {
    const_buffer_t file_handle = {0};
    if (fh != nullptr) {
      FillConstBuffer(fh, &file_handle);
    }
    const char* buf = nullptr;
    size_t offset;
    size_t length;
    EXPECT_EQ(has, dem_->PollDirtyData(&file_handle, &offset, &length,
                                       max_length, &buf));
    EXPECT_EQ(exp_offset, offset);
    EXPECT_EQ(exp_length, length);
    return buf;
  }

  const char* ExpectDirty(size_t max_length, size_t exp_offset,
                          size_t exp_length) {
    return ExpectPoll(nullptr, max_length, true, exp_offset, exp_length);
  }

  void Cleanse(size_t offset, size_t length, const char* buf) {
    dem_->CleanseDirtyExtent(file_cache_, offset, length, &buf);
  }

  void ExpectNoDirtyExtent() {
    static const size_t MAX_LENGTH = (1 << 20);
    ExpectPoll(nullptr, MAX_LENGTH, false, 0, 0);
  }

  secnfs::util::CacheInterface* cache_;
  FileCache* file_cache_;
  DirtyExtentManager* dem_;
};

TEST_F(DirtyExtentManagerTest, TestReverseIterator) {
  std::vector<int> numbers = {1, 2, 3, 5};
  auto pos = std::find_if(numbers.rbegin(), numbers.rend(),
                          [](int v) { return v <= 4; });
  VLOG(3) << "position v = " << *pos << "; base v = " << *(pos.base());
  numbers.insert(pos.base(), 4);
  for (size_t i = 0; i < numbers.size(); ++i) {
    EXPECT_EQ(i + 1, numbers[i]);
  }
}

TEST_F(DirtyExtentManagerTest, Basics) {
  InsertDirty(0, 1_b, -3);
  InsertDirty(2_b, 2_b, -2);
  InsertDirty(6_b, 2_b, -1);
  ExpectDirtyAndCleanse(2_b, 0, 1_b);
  ExpectDirtyAndCleanse(2_b, 2_b, 2_b);
  ExpectDirtyAndCleanse(2_b, 6_b, 2_b);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, BasicMergeWorks) {
  InsertDirty(0, 1_b);
  InsertDirty(0, 2_b);
  ExpectDirtyAndCleanse(2_b, 0, 2_b);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, DisjointExtentsShouldNotBeMerged) {
  InsertDirty(0, 2_b);
  InsertDirty(3_b, 2_b);
  ExpectDirtyAndCleanse(10_b, 0, 2_b);
  ExpectDirtyAndCleanse(10_b, 3_b, 2_b);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, PolledDirtyDataNotPolledAgain) {
  InsertDirty(0, 2_b);
  InsertDirty(1_b, 2_b);
  const char* buf1 = ExpectDirty(2_b, 0, 2_b);
  const char* buf2 = ExpectDirty(2_b, 2_b, 1_b);
  Cleanse(0, 2_b, buf1);
  Cleanse(2_b, 1_b, buf2);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, OnlyMergingFollowingDirtyExtent) {
  InsertDirty(3_b, 2_b);
  InsertDirty(1_b, 2_b);
  ExpectDirtyAndCleanse(10_b, 3_b, 2_b);
  ExpectDirtyAndCleanse(10_b, 1_b, 2_b);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, MergingCanBreakLargeDirtyExtent) {
  InsertDirty(1_b, 1_b);
  InsertDirty(0, 4_b);
  ExpectDirtyAndCleanse(10_b, 1_b, 3_b);
  ExpectDirtyAndCleanse(10_b, 0_b, 1_b);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, MergeOfManyDirtyExtents) {
  for (int i = 0; i < 10; ++i) {
    InsertDirty(1_b * i, 2_b);
  }
  ExpectDirtyAndCleanse(20_b, 0, 11_b);
  ExpectNoDirtyExtent();
}

TEST_F(DirtyExtentManagerTest, DirtyExtentOfFileCanBePolledBeforeDue) {
  InsertDirty(0, 1_b, 1);
  ExpectNoDirtyExtent();
  const char* buf = ExpectPoll("test_file_handle", 10_b, true, 0, 1_b);
  Cleanse(0, 1_b, buf);
}

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
