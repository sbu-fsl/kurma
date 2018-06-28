// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

#include "util/cache_interface.h"

#include <glog/logging.h>
#include <gtest/gtest.h>
#include <vector>
#include <string>
#include "util/coding.h"

namespace secnfs {
namespace util {

// Conversions between numeric keys/values and the types expected by Cache.
static std::string EncodeKey(int k) {
  std::string result;
  PutFixed32(&result, k);
  return result;
}
static int DecodeKey(const Slice& k) {
  assert(k.size() == 4);
  return DecodeFixed32(k.data());
}
static void* EncodeValue(uintptr_t v) { return reinterpret_cast<void*>(v); }
static int DecodeValue(void* v) { return reinterpret_cast<uintptr_t>(v); }

class CacheTest : public ::testing::Test {
 public:
  static CacheTest* current_;

  static void Deleter(const Slice& key, void* v, bool exiting) {
    VLOG(5) << "key " << key << " deleted";
    current_->deleted_keys_.push_back(DecodeKey(key));
    current_->deleted_values_.push_back(DecodeValue(v));
  }

  static const int kCacheSize = 1000;
  std::vector<int> deleted_keys_;
  std::vector<int> deleted_values_;
  CacheInterface* cache_;

  CacheTest() : cache_(NewCache(kCacheSize)) {
    current_ = this;
  }

  ~CacheTest() {
    delete cache_;
  }

  int Lookup(int key) {
    CacheHandle* handle = cache_->Lookup(EncodeKey(key));
    const int r = (handle == nullptr) ? -1 : DecodeValue(cache_->Value(handle));
    if (handle != nullptr) {
      cache_->Release(handle);
    }
    return r;
  }

  void Insert(int key, int value, int charge = 1) {
    CacheHandle* handle = cache_->Insert(EncodeKey(key), EncodeValue(value),
                                         charge, &CacheTest::Deleter);
    ASSERT_NE(handle, nullptr);
    cache_->Release(handle);
  }

  void Erase(int key) {
    cache_->Erase(EncodeKey(key));
  }
};
CacheTest* CacheTest::current_;

TEST_F(CacheTest, HitAndMiss) {
  ASSERT_EQ(-1, Lookup(100));

  Insert(100, 101);
  ASSERT_EQ(101, Lookup(100));
  ASSERT_EQ(-1,  Lookup(200));
  ASSERT_EQ(-1,  Lookup(300));

  Insert(200, 201);
  ASSERT_EQ(101, Lookup(100));
  ASSERT_EQ(201, Lookup(200));
  ASSERT_EQ(-1,  Lookup(300));

  Insert(100, 102);
  ASSERT_EQ(102, Lookup(100));
  ASSERT_EQ(201, Lookup(200));
  ASSERT_EQ(-1,  Lookup(300));

  ASSERT_EQ(1, deleted_keys_.size());
  ASSERT_EQ(100, deleted_keys_[0]);
  ASSERT_EQ(101, deleted_values_[0]);
}

TEST_F(CacheTest, Erase) {
  Erase(200);
  ASSERT_EQ(0, deleted_keys_.size());

  Insert(100, 101);
  Insert(200, 201);
  Erase(100);
  ASSERT_EQ(-1,  Lookup(100));
  ASSERT_EQ(201, Lookup(200));
  ASSERT_EQ(1, deleted_keys_.size());
  ASSERT_EQ(100, deleted_keys_[0]);
  ASSERT_EQ(101, deleted_values_[0]);

  Erase(100);
  ASSERT_EQ(-1,  Lookup(100));
  ASSERT_EQ(201, Lookup(200));
  ASSERT_EQ(1, deleted_keys_.size());
}

TEST_F(CacheTest, EntriesArePinned) {
  Insert(100, 101);
  CacheHandle* h1 = cache_->Lookup(EncodeKey(100));
  ASSERT_EQ(101, DecodeValue(cache_->Value(h1)));

  Insert(100, 102);
  CacheHandle* h2 = cache_->Lookup(EncodeKey(100));
  ASSERT_EQ(102, DecodeValue(cache_->Value(h2)));
  ASSERT_EQ(0, deleted_keys_.size());

  cache_->Release(h1);
  ASSERT_EQ(1, deleted_keys_.size());
  ASSERT_EQ(100, deleted_keys_[0]);
  ASSERT_EQ(101, deleted_values_[0]);

  Erase(100);
  ASSERT_EQ(-1, Lookup(100));
  ASSERT_EQ(1, deleted_keys_.size());

  cache_->Release(h2);
  ASSERT_EQ(2, deleted_keys_.size());
  ASSERT_EQ(100, deleted_keys_[1]);
  ASSERT_EQ(102, deleted_values_[1]);
}

TEST_F(CacheTest, EvictionPolicy) {
  Insert(100, 101);
  Insert(200, 201);

  // Frequently used entry must be kept around
  for (int i = 0; i < kCacheSize + 100; i++) {
    Insert(1000+i, 2000+i);
    ASSERT_EQ(2000+i, Lookup(1000+i));
    ASSERT_EQ(101, Lookup(100));
  }
  ASSERT_EQ(101, Lookup(100));
  ASSERT_EQ(-1, Lookup(200));
}

TEST_F(CacheTest, HeavyEntries) {
  // Add a bunch of light and heavy entries and then count the combined
  // size of items still in the cache, which must be approximately the
  // same as the total capacity.
  const int kLight = 1;
  const int kHeavy = 10;
  int added = 0;
  int index = 0;
  while (added < 2*kCacheSize) {
    const int weight = (index & 1) ? kLight : kHeavy;
    Insert(index, 1000+index, weight);
    added += weight;
    index++;
  }

  int cached_weight = 0;
  for (int i = 0; i < index; i++) {
    const int weight = (i & 1 ? kLight : kHeavy);
    int r = Lookup(i);
    if (r >= 0) {
      cached_weight += weight;
      ASSERT_EQ(1000+i, r);
    }
  }
  ASSERT_LE(cached_weight, kCacheSize + kCacheSize/10);
}

TEST_F(CacheTest, NewId) {
  uint64_t a = cache_->NewId();
  uint64_t b = cache_->NewId();
  ASSERT_NE(a, b);
}

TEST_F(CacheTest, UpdateExistingKVDoesNotUnref) {
  Insert(100, 100, 1);
  Insert(100, 100, 2);
  ASSERT_EQ(0, deleted_keys_.size());
}

TEST_F(CacheTest, UpdateExistingShouldNotCreateNewCacheHandle) {
  Insert(100, 100);

  // hold a reference
  std::string key = EncodeKey(100);
  CacheHandle* handle1 = cache_->Lookup(key);
  EXPECT_TRUE(handle1 != nullptr);

  // Remove the key from the cache, but the handle is not removed because of the
  // referenced hold by "handle1".
  cache_->Erase(key);

  // If we insert the same (key, value) again, we will create a new cache
  // handle.  Therefore, we will have two cache handles associated with one
  // value.  It is undesirable because each handle has a reference count, and
  // the "deleter" will be called when each of them drops to zero.  Effectively,
  // we will delete the value twice!
  //
  // CacheHandle* handle2 =
  //     cache_->Insert(key, EncodeValue(100), 2, &CacheTest::Deleter);
  // ASSERT_NE(handle1, handle2);
  // cache_->Release(handle2);

  // Instead, we should use "Update()" if we hold a cache handle already.
  bool res = cache_->Update(key, handle1, 2);
  EXPECT_TRUE(res);
  cache_->Release(handle1);

  // The key should still be in the cache.
  CacheHandle* handle3 = cache_->Lookup(key);
  EXPECT_EQ(handle1, handle3);
  cache_->Release(handle3);

  cache_->Erase(key);
  EXPECT_EQ(nullptr, cache_->Lookup(key));
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
