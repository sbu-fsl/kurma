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

class ProxyCacheTest : public ::testing::Test {
 public:
  void SetUp() override {
    cache_ = new ProxyCache(test_dir, meta_dir, cache_size_mb);
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

TEST_F(ProxyCacheTest, FileHandleAndPathAreConvertedCorrectly) {
  for (int i = 0; i < 100; ++i) {
    std::string fh = GetRandomString(12);
    EXPECT_EQ(12, fh.length());
    std::string path = cache_->FileHandleToPath(fh);
    std::string fh2 = cache_->PathToFileHandle(path);
    EXPECT_EQ(fh, fh2);
  }
}

TEST_F(ProxyCacheTest, BasicInsertAndLookupWorks) {
  EXPECT_EQ(0, InsertClean("aaa", 0, 1_b));
  ExpectLookup("aaa", 0, 1_b, 3, 0, 1_b);
}

TEST_F(ProxyCacheTest, LookupOfNonexistContentReturnsNotFound) {
  ExpectLookup("aaa", 0, 1_b, 0, 0, 0);
  EXPECT_EQ(0, InsertClean("aaa", 0, 1_b));
  ExpectLookup("aaa", 1_b, 1_b, 0, 0, 0);
  ExpectLookup("bbb", 0, 1_b, 0, 0, 0);
  ExpectLookup("bbb", 1_b, 1_b, 0, 0, 0);
}

TEST_F(ProxyCacheTest, LoadExistingCacheFileCorrectly) {
  // Pre-create some cache files.
  auto tmpcache = new ProxyCache(test_dir, meta_dir, cache_size_mb);
  // create cache file "aaa"
  std::string buf = GetRandomString(1_b);
  tmpcache->InsertClean("aaa", 0, 1_b, buf.data());
  tmpcache->InsertClean("aaa", 2_b, 1_b, buf.data());
  tmpcache->Commit("aaa", 0, 4_b);

  // create cache file "bbb"
  buf = GetRandomString(2_b);
  tmpcache->InsertClean("bbb", 1_b, 2_b, buf.data());
  tmpcache->Commit("bbb", 0, 4_b);
  delete tmpcache;

  {
    // Nothing in cache before we load.
    SCOPED_TRACE("before load");
    ExpectLookup("aaa", 0, 1_b, 0, 0, 0);
    ExpectLookup("aaa", 1_b, 1_b, 0, 0, 0);
    ExpectLookup("aaa", 2_b, 1_b, 0, 0, 0);
    ExpectLookup("bbb", 0, 1_b, 0, 0, 0);
    ExpectLookup("bbb", 1_b, 1_b, 0, 0, 0);
  }
  EXPECT_EQ(0, cache_->Load());
  {
    // Now local cache files are load.
    SCOPED_TRACE("after load");
    ExpectLookup("aaa", 0, 1_b, 3, 0, 1_b);
    ExpectLookup("aaa", 1_b, 1_b, 0, 0, 0);
    ExpectLookup("aaa", 2_b, 1_b, 3, 2_b, 1_b);
    ExpectLookup("bbb", 0, 1_b, 0, 0, 0);
    ExpectLookup("bbb", 1_b, 1_b, 3, 1_b, 1_b);
  }
}

TEST_F(ProxyCacheTest, InvalidatedCacheAreNotFound) {
  EXPECT_EQ(0, InsertClean("aaa", 0, 1_b));
  EXPECT_EQ(0, InsertClean("bbb", 1_b, 1_b));
  EXPECT_EQ(0, InsertClean("ccc", 2_b, 1_b));
  {
    SCOPED_TRACE("before invalidating");
    ExpectLookup("aaa", 0, 1_b, 3, 0, 1_b);
    ExpectLookup("bbb", 1_b, 1_b, 3, 1_b, 1_b);
    ExpectLookup("ccc", 2_b, 1_b, 3, 2_b, 1_b);
  }
  {
    SCOPED_TRACE("after 'aaa' is invalidated");
    cache_->Delete("aaa");
    ExpectLookup("aaa", 0, 1_b, 0, 0, 0);
    ExpectLookup("bbb", 1_b, 1_b, 3, 1_b, 1_b);
  }
  {
    SCOPED_TRACE("after 'bbb' is invalidated");
    cache_->Delete("bbb");
    ExpectLookup("bbb", 1_b, 1_b, 0, 0, 0);
    ExpectLookup("ccc", 2_b, 1_b, 3, 2_b, 1_b);
  }
  {
    SCOPED_TRACE("after 'ccc' is invalidated");
    cache_->Delete("ccc");
    ExpectLookup("ccc", 2_b, 1_b, 0, 0, 0);
  }
}

TEST_F(ProxyCacheTest, CleanExitDoesNotRemoveCacheFiles) {
  EXPECT_EQ(0, InsertClean("aaa", 0, 1_b));
  ExpectLookup("aaa", 0, 1_b, 3, 0, 1_b);
  std::string fpath = cache_->FileHandleToPath("aaa");
  cache_->Destroy();
  delete cache_;
  cache_ = nullptr;
  // File should still exist after destructing the cache,
  EXPECT_TRUE(FileExists(fpath));
  EXPECT_EQ(1_b, GetFileSize(fpath));
  // and we can load it and find it again.
  cache_ = new ProxyCache(test_dir, meta_dir, cache_size_mb);
  EXPECT_EQ(0, cache_->Load());
  ExpectLookup("aaa", 0, 1_b, 3, 0, 1_b);
}

TEST_F(ProxyCacheTest, BasicWriteBackPollWorks) {
  EXPECT_EQ(0, InsertDirty("bbb", 0, 1_b, 1));
  {
    SCOPED_TRACE("before write-back deadline");
    ExpectPoll("bbb", false, 0, 0);
  }

  sleep(1);
  {
    SCOPED_TRACE("after write-back deadline");
    const char* buf = ExpectPoll("bbb", true, 0, 1_b);
    EXPECT_EQ(0, cache_->MarkWriteBackDone("bbb", 0, 1_b, &buf));
    ExpectPoll("bbb", false, 0, 0);
  }
}

TEST_F(ProxyCacheTest, OutOfOrderWrittenBackMarksAreHandledCorrectly) {
  EXPECT_EQ(0, InsertDirty("aaa", 0, 1_b, 1));
  EXPECT_EQ(0, InsertDirty("bbb", 0, 1_b, 1));
  EXPECT_EQ(0, InsertDirty("ccc", 0, 1_b, 1));
  sleep(1);
  const char* buf_a = ExpectPoll("aaa", true, 0, 1_b);
  const char* buf_b = ExpectPoll("bbb", true, 0, 1_b);
  const char* buf_c = ExpectPoll("ccc", true, 0, 1_b);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("ccc", 0, 1_b, &buf_c));
  EXPECT_EQ(0, cache_->MarkWriteBackDone("bbb", 0, 1_b, &buf_b));
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, 1_b, &buf_a));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

TEST_F(ProxyCacheTest, FailedWriteBackWillBeRetried) {
  EXPECT_EQ(0, InsertDirty("aaa", 0, 1_b, 1));
  sleep(1);
  const char* buf = ExpectPoll("aaa", true, 0, 1_b);
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, 0, &buf));  // failed
  buf = ExpectPoll("aaa", true, 0, 1_b);                          // retried
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, 1_b, &buf));
  ExpectPoll("aaa", false, 0, 0);  // no more writeback needed
}

// The capacity is divided into shards (16 shards by default), each of which
// contains a range of keys based on the hash.
TEST_F(ProxyCacheTest, CapacityLimitEnforced) {
  std::string buf = GetRandomString(1);
  const size_t shards = 16;
  // find 3 keys that will fall into the same shard:
  std::vector<std::string> keys;
  for (int i = 0; keys.size() < 3; ++i) {
    char key[6 + 1];
    snprintf(key, 6 + 1, "%06d", i);
    if ((secnfs::util::Hash(key, 6, 0) >> (32 - 4)) == 0) {
      VLOG(3) << "key: " << key;
      keys.push_back(std::string(key));
    }
  }

  const size_t half_size = (cache_size_mb_per_shard << 20) >> 1;
  EXPECT_EQ(0, InsertClean(keys[0], 0, half_size));
  {
    SCOPED_TRACE("cache is found before go beyond capacity");
    ExpectLookup(keys[0], 0, 1_b, FULL_MATCH, 0, 1_b);
  }
  EXPECT_EQ(0, InsertClean(keys[1], 0, half_size));
  EXPECT_EQ(0, InsertClean(keys[2], 0, half_size));
  {
    SCOPED_TRACE("cache not found after reach capacity and be evicted");
    ExpectLookup(keys[0], 0, 1_b, NOT_FOUND, 0, 0);
  }
}

TEST_F(ProxyCacheTest, MultiplePendingWriteBacks) {
  EXPECT_EQ(0, InsertDirty("bbb", 0, 1_b, 1));
  EXPECT_EQ(0, InsertDirty("bbb", 1_b, 1_b, 1));
  {
    SCOPED_TRACE("before write-back deadline");
    ExpectPoll("bbb", false, 0, 0);
  }

  sleep(2);
  {
    SCOPED_TRACE("after write-back deadline");
    const char* buf = ExpectPoll("bbb", true, 0, 2_b);
    EXPECT_EQ(0, cache_->MarkWriteBackDone("bbb", 0, 2_b, &buf));
  }
  ExpectNoWriteBack();
}

TEST_F(ProxyCacheTest, PartCleanPartDirtyData) {
  EXPECT_EQ(0, InsertDirty("bbb", 0, 1_b, 1));
  EXPECT_EQ(0, InsertClean("bbb", 1_b, 1_b));
  ExpectLookup("bbb", 0, 2_b, FULL_MATCH, 0, 2_b);
  sleep(1);
  const char* buf = ExpectPoll("bbb", true, 0, 1_b);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("bbb", 0, 1_b, &buf));
}

TEST_F(ProxyCacheTest, DeletedDirtyExtentIsNotWrittenBack) {
  EXPECT_EQ(0, InsertDirty("bbb", 0, 1_b, 1));
  EXPECT_EQ(0, InsertDirty("bbb", 2_b, 1_b, 1));
  EXPECT_EQ(1_b, cache_->Invalidate("bbb", 2_b, 1_b, true));
  sleep(1);
  ExpectPollAndWriteBack("bbb", true, 0, 1_b);
  ExpectNoWriteBack();
}

TEST_F(ProxyCacheTest, DirtyExtentsOfDeletedFileAreNotWrittenBack) {
  EXPECT_EQ(0, InsertDirty("bbb", 0, 1_b, 1));
  EXPECT_EQ(0, InsertDirty("bbb", 2_b, 1_b, 1));
  sleep(1);
  cache_->Delete("bbb");
  ExpectNoWriteBack();
}

TEST_F(ProxyCacheTest, DeleteTheOnlyExtentWillEraseTheWholeFileCache) {
  EXPECT_EQ(0, InsertDirty("bbb", 2_b, 1_b, 0));
  ExpectLookup("bbb", 2_b, 1_b, FULL_MATCH, 2_b, 1_b);
  EXPECT_EQ(0, cache_->Invalidate("bbb", 0, 4_b, true));
  ExpectLookup("bbb", 2_b, 1_b, NOT_FOUND, 0, 0);
  ExpectNoWriteBack();
  EXPECT_FALSE(FileExists(cache_->FileHandleToPath("bbb")));
}

TEST_F(ProxyCacheTest, InvalidateCleanExtentsShouldSucceed) {
  EXPECT_EQ(0, InsertClean("bbb", 2_b, 1_b));
  ExpectLookup("bbb", 2_b, 1_b, FULL_MATCH, 2_b, 1_b);
  EXPECT_EQ(0, cache_->Invalidate("bbb", 0, 4_b, false));
  EXPECT_FALSE(FileExists(cache_->FileHandleToPath("bbb")));
  ExpectNoWriteBack();
}

TEST_F(ProxyCacheTest, InvalidateDirtyExtentsShouldFail) {
  EXPECT_EQ(0, InsertDirty("bbb", 2_b, 1_b, 1));
  EXPECT_EQ(-1, cache_->Invalidate("bbb", 0, 4_b, false));
  sleep(1);
  ExpectPollAndWriteBack("bbb", true, 2_b, 1_b);
  ExpectNoWriteBack();
}

TEST_F(ProxyCacheTest, InvalidateNotCachedRangeIsOkay) {
  EXPECT_EQ(0, InsertClean("bbb", 0_b, 1_b));
  EXPECT_EQ(1_b, cache_->Invalidate("bbb", 2_b, 4_b, false));
  ExpectLookup("bbb", 0, 1_b, FULL_MATCH, 0, 1_b);
}

TEST_F(ProxyCacheTest, MultipleCreationsShouldBeSynchronized) {
  std::string buf = GetRandomString(1_b);
  DoParallel(10, [this, &buf](int i) {
    EXPECT_EQ(0, cache_->InsertClean("bbb", i * 1_b, 1_b, buf.data()));
  });
  ExpectLookup("bbb", 0, 10_b, FULL_MATCH, 0, 10_b);
}

TEST_F(ProxyCacheTest, StressMergingOfDirtyExtents) {
  std::string buf = GetRandomString(2_b);
  DoParallel(10, [this, &buf](int i) {
    EXPECT_EQ(0, cache_->InsertDirty("bbb", i * 1_b, 2_b, buf.data(), 0));
  });
  ExpectTotalWriteBackLength(11_b);
}

TEST_F(ProxyCacheTest, ParallelInvalidation) {
  InsertDirty("aaa", 0, 10_b, 0);
  DoParallel(10, [this](int i) {
    cache_->Invalidate("aaa", i * 1_b, 1_b, true);
  });
  ExpectNoWriteBack();
  ExpectLookup("aaa", 0, 10_b, NOT_FOUND, 0, 0);
}

TEST_F(ProxyCacheTest, ParallelInsertionAndInvalidation) {
  std::string buf = GetRandomString(1_b);
  DoParallel(10, [this, &buf](int i) {
    EXPECT_EQ(0, cache_->InsertClean("aaa", i * 1_b, 1_b, buf.data()));
  });
  DoParallel(10, [this](int i) {
    ExpectLookup("aaa", i * 1_b, 1_b, FULL_MATCH, i * 1_b, 1_b);
    cache_->Invalidate("aaa", i * 1_b, 1_b, true);
  });
  ExpectLookup("aaa", 0, 10_b, NOT_FOUND, 0, 0);
}

TEST_F(ProxyCacheTest, ParallelOverlappingWrites) {
  for (int i = 0; i < 10; ++i) {
    EXPECT_EQ(0, InsertDirty("aaa", 10_b - i * 1_b, 2_b * (i + 1), 0));
  }
  std::atomic<size_t> total_wb_length(0);
  DoParallel(10, [this, &total_wb_length](int i) {
    const_buffer_t fh = {0};
    size_t offset = 0;
    size_t length = 0;
    const char *data;
    if (cache_->PollWriteBack(&fh, &offset, &length, &data)) {
      total_wb_length.fetch_add(length);
      cache_->MarkWriteBackDone(ToSlice(fh), offset, length, &data);
    }
  });
  EXPECT_EQ(20_b, total_wb_length.fetch_add(0));
}

TEST_F(ProxyCacheTest, DirtyExtentCanBeTrimmedInTheMiddle) {
  InsertDirty("aaa", 1_b, 1_b, 0);
  const char* buf1 = ExpectPoll("aaa", true, 1_b, 1_b);
  InsertDirty("aaa", 0, 1_b, 0);
  InsertDirty("aaa", 2_b, 1_b, 0);

  // If we don't allow trim in the middle, the length of the writeback will be
  // 3_b.  Note that extent [1_b, 2_b) is still dirty and read-locked.
  const char* buf0 = ExpectPoll("aaa", true, 0, 1_b);

  cache_->MarkWriteBackDone("aaa", 1_b, 1_b, &buf1);
  cache_->MarkWriteBackDone("aaa", 0, 1_b, &buf0);

  const char* buf2 = ExpectPoll("aaa", true, 2_b, 1_b);
  cache_->MarkWriteBackDone("aaa", 2_b, 1_b, &buf0);
}

TEST_F(ProxyCacheTest, MixDeletionAndCleanInsertions) {
  // Race conditions may cause the program to hang if the cache file exists and
  // creation threads decide to wait, but the file is later removed without
  // later attempt to recreate it.
  InsertClean("aaa", 0, 1_b);
  DoParallel(10, [this](int i) {
    if ((i & 0x1) == 0) {
      cache_->Delete("aaa");
    } else {
      InsertClean("aaa", i * 1_b, 1_b);
    }
  });
}

TEST_F(ProxyCacheTest, MixDeletionAndDirtyInsertions) {
  // Same as MixDeletionAndCleanInsertions but also test the dirty extents which
  // might hold references and prevent the cached file from being deleted.
  InsertDirty("aaa", 0, 1_b, 0);
  DoParallel(10, [this](int i) {
    if ((i & 0x1) == 0) {
      cache_->Delete("aaa");
    } else {
      EXPECT_EQ(0, InsertDirty("aaa", i * 1_b, 1_b, 0));
    }
  });
  cache_->Delete("aaa");
}

TEST_F(ProxyCacheTest, DeleteAndInsertAgain) {
  InsertDirty("aaa", 1_b, 1_b, 0);
  cache_->Delete("aaa");
  InsertClean("aaa", 0, 1_b);
  ExpectLookup("aaa", 0, 1_b, FULL_MATCH, 0, 1_b);
  ExpectLookup("aaa", 1_b, 1_b, NOT_FOUND, 0, 0);
}

TEST_F(ProxyCacheTest, InvalidateAllDirtyExtentsAndInsertAgain) {
  InsertDirty("aaa", 2_b, 6_b, 0);
  cache_->Invalidate("aaa", 0, 8_b, true);
  InsertClean("aaa", 0, 1_b);
  ExpectLookup("aaa", 0, 1_b, FULL_MATCH, 0, 1_b);
  ExpectLookup("aaa", 2_b, 6_b, NOT_FOUND, 0, 0);
}

TEST_F(ProxyCacheTest, FileSpecificPollingShouldIgnoreDeadline) {
  InsertDirty("aaa", 0, 1_b, 1);
  ExpectNoWriteBack();
  ExpectPollAndWriteBack("aaa", true, 0, 1_b, true);
}

TEST_F(ProxyCacheTest, NoDuplicateExtentsWithFileSpecificPolling) {
  InsertDirty("aaa", 0, 1_b, 1);
  InsertDirty("aaa", 2_b, 1_b, 1);
  ExpectNoWriteBack();
  ExpectPollAndWriteBack("aaa", true, 0, 1_b, true);
  ExpectNoWriteBack();

  sleep(1);

  ExpectPollAndWriteBack("aaa", true, 2_b, 1_b);
  ExpectNoWriteBack();
}

TEST_F(ProxyCacheTest, RewriteDirty) {
  std::string buf1 = GetRandomString(10);
  EXPECT_EQ(0, cache_->InsertDirty("aaa", 0, 10, buf1.data(), 1));
  std::string buf2 = GetRandomString(10);
  EXPECT_EQ(0, cache_->InsertDirty("aaa", 0, 10, buf2.data(), 2));
  sleep(1);
  const char *cbuf1_wb;
  std::string buf1_wb(cbuf1_wb = ExpectPoll("aaa", true, 0, 10), 10);
  EXPECT_EQ(buf2, buf1_wb);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, 10, &cbuf1_wb));
  sleep(1);
  ExpectPoll("aaa", false, 0, 10);
}

TEST_F(ProxyCacheTest, RewriteDirtyAndFileSpecificPolling) {
  std::string buf1 = GetRandomString(10);
  EXPECT_EQ(0, cache_->InsertDirty("aaa", 0, 10, buf1.data(), 1));
  std::string buf2 = GetRandomString(10);
  EXPECT_EQ(0, cache_->InsertDirty("aaa", 0, 10, buf2.data(), 2));
  const char *cbuf1_wb;
  std::string buf1_wb(cbuf1_wb = ExpectPoll("aaa", true, 0, 10, true), 10);
  EXPECT_EQ(buf2, buf1_wb);
  EXPECT_EQ(0, cache_->MarkWriteBackDone("aaa", 0, 10, &cbuf1_wb));
  ExpectPoll("aaa", false, 0, 0, true);
}

TEST_F(ProxyCacheTest, fsxReplay) {
  InsertDirty("aaa", 106496, 32768, 1);
  cache_->Commit("aaa", 0, 139264);
  ExpectPollAndWriteBack("aaa", true, 106496, 32768, true);
  ExpectLookup("aaa", 106496, 28672, FULL_MATCH, 106496, 28672);
  InsertClean("aaa", 73728, 28672);
}

TEST_F(ProxyCacheTest, IsFileDirtyBasic) {
  EXPECT_FALSE(cache_->IsFileDirty("ccc"));
  EXPECT_EQ(0, InsertDirty("ccc", 2_b, 1_b, 1));
  EXPECT_TRUE(cache_->IsFileDirty("ccc"));
  sleep(1);
  const char* buf = ExpectPoll("ccc", true, 2_b, 1_b);
  EXPECT_TRUE(cache_->IsFileDirty("ccc"));
  cache_->MarkWriteBackDone("ccc", 2_b, 1_b, &buf);
  EXPECT_FALSE(cache_->IsFileDirty("ccc"));
  ExpectNoWriteBack();
}

// TODO(mchen): delete a file with polled dirty extent

}  // namespace test
}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
