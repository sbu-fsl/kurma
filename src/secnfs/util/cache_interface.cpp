// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

#include "util/cache_interface.h"
#include "util/lrucache.h"

namespace secnfs {
namespace util {

CacheInterface::~CacheInterface() {}

void* CacheInterface::LookupValue(const Slice& key) {
  CacheHandle* handle = Lookup(key);
  void* value = nullptr;
  if (handle != nullptr) {
    value = Value(handle);
    Release(handle);
  }
  return value;
}

CacheInterface* NewCache(size_t capacity) {
  return new ShardedLRUCache(capacity);
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
