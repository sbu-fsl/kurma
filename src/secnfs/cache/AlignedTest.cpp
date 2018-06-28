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
// Unittest for aligned operations on ProxyCache.

#include <unistd.h>

#include <glog/logging.h>
#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include <atomic>
#include <list>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <tuple>
#include <vector>

#include "ProxyCache.h"
#include "util/common.h"
#include "util/fileutil.h"
#include "util/hash.h"
#include "capi/cpputil.h"

using secnfs::util::CreateFile;
using secnfs::util::CreateOrUseDir;
using secnfs::util::DeleteDirRecursively;
using secnfs::util::GetFileSize;
using secnfs::util::FileExists;
using secnfs::util::PWrite;
using secnfs::capi::FillConstBuffer;
using secnfs::capi::ToSlice;

namespace secnfs {
namespace cache {
namespace test {

static const char* test_dir = "/tmp/proxycache-data";
static const char* meta_dir = "/tmp/proxycache-meta";
static const size_t cache_size_mb_per_shard = 2;
static const size_t cache_size_mb = cache_size_mb_per_shard << 4;

namespace {
  void DoParallel(int nthread, std::function<void(int)> worker) {
    std::list<std::thread> threads;
    for (int i = 0; i < 10; ++i) {
      threads.emplace_back(worker, i);
    }
    for (auto it = threads.begin(); it != threads.end(); ++it) {
      it->join();
    }
  }
}  // anonymous namespace

class AlignedProxyCacheTest : public ::testing::TestWithParam<int> {
 public:
  void SetUp() override {
    cache_ =
        new ProxyCache(test_dir, meta_dir, cache_size_mb, nullptr, GetParam());
    rand_eng_.seed(8887);
    EXPECT_EQ(0, CreateOrUseDir(test_dir));
    EXPECT_EQ(0, CreateOrUseDir(meta_dir));
  }

  void TearDown() override {
    if (cache_ != nullptr) delete cache_;
    EXPECT_EQ(0, DeleteDirRecursively(test_dir));
    EXPECT_EQ(0, DeleteDirRecursively(meta_dir));
  }

 protected:
  ssize_t InsertClean(const Slice& fh, size_t offset, size_t length) {
    std::string buf = GetRandomString(length);
    return cache_->InsertClean(fh, offset, length, buf.data());
  }

  ssize_t InsertDirty(const Slice& fh, size_t offset, size_t length,
                      int wb_seconds) {
    std::string buf = GetRandomString(length);
    return cache_->InsertDirty(fh, offset, length, buf.data(), wb_seconds);
  }

  void ExpectLookup(const Slice& fh, size_t offset, size_t length,
                    ssize_t expected_result, size_t exp_offset,
                    size_t exp_length) {
    char* buf = new char[length];
    size_t cached_offset = 0;
    size_t cached_length = 0;
    ssize_t res =
        cache_->Lookup(fh, offset, length, buf, &cached_offset, &cached_length);
    EXPECT_EQ(expected_result, res);
    EXPECT_EQ(exp_offset, cached_offset);
    EXPECT_EQ(exp_length, cached_length);
    delete[] buf;
  }

  const char* ExpectPoll(const Slice& fh, bool has_due, size_t exp_offset,
                         size_t exp_length, bool file_specific = false) {
    const_buffer_t file_handle = {0};
    if (file_specific) {
      FillConstBuffer(fh, &file_handle);
    }
    size_t offset = 0;
    size_t length = 0;
    const char *buf;
    bool res = cache_->PollWriteBack(&file_handle, &offset, &length, &buf);
    EXPECT_EQ(has_due, res);
    if (has_due) {
      EXPECT_EQ(exp_offset, offset);
      EXPECT_EQ(exp_length, length);
      EXPECT_EQ(fh, ToSlice(file_handle));
    }
    return buf;
  }

  void ExpectNoWriteBack() {
    ExpectPoll("", false, 0, 0);
  }

  void ExpectPollAndWriteBack(const Slice& fh, bool has_due, size_t exp_offset,
                              size_t exp_length, bool file_specific = false) {
    const char* buf =
        ExpectPoll(fh, has_due, exp_offset, exp_length, file_specific);
    cache_->MarkWriteBackDone(fh, exp_offset, exp_length, &buf);
  }

  std::string GetRandomString(uint64_t len) {
    std::string str(len, '0');
    std::lock_guard<std::mutex> lock(mutex_);
    for (char& c : str) {
      c = uni_dist_(rand_eng_);
    }
    return str;
  }

  void CreateFileExtent(const Slice& fh, size_t offset, size_t length) {
    std::string path = cache_->FileHandleToPath(fh);
    std::string buf = GetRandomString(length);
    if (!FileExists(path)) {
      EXPECT_EQ(0, CreateFile(path));
    }
    EXPECT_EQ(length, PWrite(path, offset, length, buf.data()));
  }

  void ExpectTotalWriteBackLength(size_t wb_length) {
    // poll all dirty extents
    size_t total_length = 0;
    const_buffer_t fh = {0};
    size_t offset = 0;
    size_t length = 0;
    const char *data;
    std::list<std::tuple<const_buffer_t, size_t, size_t, const char*>> wbs;
    while (cache_->PollWriteBack(&fh, &offset, &length, &data)) {
      VLOG(3) << "writeback : [" << offset << ", " << (offset + length) << ").";
      total_length += length;
      wbs.emplace_back(std::make_tuple(fh, offset, length, data));
    }

    EXPECT_EQ(wb_length, total_length);

    for (const auto& wb : wbs) {
      std::tie(fh, offset, length, data) = wb;
      cache_->MarkWriteBackDone(ToSlice(fh), offset, length, &data);
    }
  }

  ProxyCache* cache_ = nullptr;
  std::default_random_engine rand_eng_;
  std::uniform_int_distribution<char> uni_dist_;
  std::mutex mutex_;  // protect uni_dist_
};

TEST_P(AlignedProxyCacheTest, SingleUnalignedWrite) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 2, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS / 2);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS / 2, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, TwoConsecutiveWrites) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 4, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS / 2);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS / 2, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, TwoIdenticalWrites) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS / 4);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS / 4, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, OverlappingUnalignedWrites) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 2, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS / 2);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS / 2, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, SingleWriteAfterHole) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", BS / 4, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, BS / 4, BS / 4);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", BS / 4, BS / 4, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, WritesOutOfOrder) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", BS / 4, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 2, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS * 3 / 4);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS * 3 / 4, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, WritesOutOfOrder2) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 2, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 4, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS * 3 / 4, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, WritesInReverseOrder) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", BS * 3 / 4, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 2, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 4, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, LargeOverlappingWrites) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", BS, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS, 5 * BS, 0));
  const char* buf_a = ExpectPoll("aaa", true, BS, 5 * BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", BS, 5 * BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, DirtyExtentsInTheSameBlockAreMerged) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 2, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 2, BS / 2, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, WritebackBothCleanAndDirtyData) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertClean("aaa", 0, BS));
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 4, 0));
  EXPECT_EQ(0, InsertDirty("aaa", BS * 3 / 4, BS / 4, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, AdjacentDirtyAndCleanBlocks) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertClean("aaa", BS, BS));
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, UnalignedBlockAcrossBoundary) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertClean("aaa", 0, 2 * BS));
  EXPECT_EQ(0, InsertDirty("aaa", BS / 2, BS, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, 2 * BS);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, 2 * BS, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_P(AlignedProxyCacheTest, UnalignedDirtyMergeClean) {
  const size_t BS = GetParam();
  EXPECT_EQ(0, InsertDirty("aaa", 0, BS / 2, 0));
  const char* buf_a = ExpectPoll("aaa", true, 0, BS / 2);
  char* buf1 = (char*)malloc(BS / 2);
  memcpy(buf1, buf_a, BS / 2);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS / 2, &buf_a));

  EXPECT_EQ(BS / 4, cache_->Invalidate("aaa", BS / 4, BS / 4, true));

  EXPECT_EQ(0, InsertDirty("aaa", BS / 4, BS / 4, 0));
  buf_a = ExpectPoll("aaa", true, 0, BS / 2);
  EXPECT_EQ(0, memcmp(buf1, buf_a, BS / 4));
  EXPECT_NE(0, memcmp(buf1 + BS / 4, buf_a + BS / 4, BS / 4));
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, BS / 2, &buf_a));

  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
  free(buf1);
}

INSTANTIATE_TEST_CASE_P(Alignments, AlignedProxyCacheTest,
                        ::testing::Values(4096, 1048576));

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
