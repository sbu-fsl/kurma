// Copyright (C) 2013-2018 Ming Chen
// Copyright (C) 2016-2016 Praveen Kumar Morampudi
// Copyright (C) 2016-2016 Harshkumar Patel
// Copyright (C) 2017-2017 Rushabh Shah
// Copyright (C) 2013-2014 Arun Olappamanna Vasudevan 
// Copyright (C) 2013-2014 Kelong Wang
// Copyright (C) 2013-2018 Erez Zadok
// Copyright (c) 2013-2018 Stony Brook University
// Copyright (c) 2013-2018 The Research Foundation for SUNY
//
// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

#include "util/lrucache.h"

#include "port/port.h"
#include "util/hash.h"
#include "util/mutexlock.h"

#include "glog/logging.h"
#include "util/base64.h"

namespace secnfs {
namespace util {

// LRU cache implementation

LRUCache::LRUCache()
    : usage_(0) {
  // Make empty circular linked list
  lru_.next = &lru_;
  lru_.prev = &lru_;
}

LRUCache::~LRUCache() {
  for (LRUHandle* e = lru_.next; e != &lru_; ) {
    LRUHandle* next = e->next;
    assert(e->refs == 1);  // Error if caller has an unreleased handle
    Unref(e, true);
    e = next;
  }
}

void LRUCache::Unref(LRUHandle* e, bool exiting) {
  assert(e->refs > 0);
  e->refs--;
  VLOG(5) << __PRETTY_FUNCTION__ << " decrement ref to " << e->refs;
  if (e->refs <= 0) {
    VLOG(1) << "Deleting %x", e->value;
    usage_ -= e->charge;
    (*e->deleter)(e->key(), e->value, exiting);
    free(e);
  }
}

void LRUCache::LRU_Remove(LRUHandle* e) {
  VLOG(5) << __PRETTY_FUNCTION__;
  e->next->prev = e->prev;
  e->prev->next = e->next;
}

void LRUCache::LRU_Append(LRUHandle* e) {
  VLOG(5) << __PRETTY_FUNCTION__;
  // Make "e" newest entry by inserting just before lru_
  e->next = &lru_;
  e->prev = lru_.prev;
  e->prev->next = e;
  e->next->prev = e;
}

CacheHandle* LRUCache::Lookup(const Slice& key, uint32_t hash) {
  VLOG(5) << __PRETTY_FUNCTION__ << " key: " << BinaryToBase64(key);
  MutexLock l(&mutex_);
  LRUHandle* e = table_.Lookup(key, hash);
  if (e != nullptr) {
    e->refs++;
    VLOG(5) << __PRETTY_FUNCTION__ << " increment ref to " << e->refs;
    LRU_Remove(e);
    LRU_Append(e);
  }
  return reinterpret_cast<CacheHandle*>(e);
}

void LRUCache::Release(CacheHandle* handle) {
  VLOG(5) << __PRETTY_FUNCTION__;
  MutexLock l(&mutex_);
  Unref(reinterpret_cast<LRUHandle*>(handle));
}

void LRUCache::Reclaim() {
  while (usage_ > capacity_ && lru_.next != &lru_) {
    VLOG(5) << __PRETTY_FUNCTION__ << " usage: " << usage_
            << " capacity: " << capacity_;
    LRUHandle* old = lru_.next;
    LRU_Remove(old);
    table_.Remove(old->key(), old->hash);
    Unref(old);
  }
}

CacheHandle* LRUCache::Insert(
    const Slice& key, uint32_t hash, void* value, size_t charge,
    CacheDeleter deleter) {
  VLOG(5) << __PRETTY_FUNCTION__ << " key: " << BinaryToBase64(key);
  MutexLock l(&mutex_);

  LRUHandle* e = table_.Remove(key, hash);
  if (e != nullptr && e->value == value) {
    VLOG(5) << __PRETTY_FUNCTION__ << " updating cache entry";
    // do not create a new entry if both key and value are the same
    // TODO(mchen): optimize by avoiding another HandleTable::FindPointer() in
    // HandleTable::Insert.
    e->deleter = deleter;
    e->refs++;
    usage_ -= e->charge;
    e->charge = charge;
    usage_ += charge;
    LRU_Remove(e);
    LRU_Append(e);
  } else {
    if (e != nullptr) {
      VLOG(5) << __PRETTY_FUNCTION__ << " replacing cache entry";
      LRU_Remove(e);
      Unref(e);
    }
    e = reinterpret_cast<LRUHandle*>(
        malloc(sizeof(LRUHandle)-1 + key.size()));
    e->value = value;
    e->deleter = deleter;
    e->charge = charge;
    e->key_length = key.size();
    e->hash = hash;
    e->refs = 2;  // One from LRUCache, one for the returned handle
    memcpy(e->key_data, key.data(), key.size());
    LRU_Append(e);
    usage_ += charge;
  }

  VLOG(5) << __PRETTY_FUNCTION__ << " set ref to " << e->refs;

  LRUHandle* lh = table_.Insert(e);
  assert(lh == nullptr);

  Reclaim();

  return reinterpret_cast<CacheHandle*>(e);
}

bool LRUCache::Update(const Slice& key, uint32_t hash, LRUHandle* handle,
                      size_t charge) {
  VLOG(5) << __PRETTY_FUNCTION__ << " key: " << BinaryToBase64(key);
  MutexLock l(&mutex_);

  LRUHandle* e = table_.Remove(key, hash);
  if (e != nullptr) {
    if (e != handle) {
      VLOG(1) << __PRETTY_FUNCTION__
              << " key associated with a different value: " << e->value;
      return false;
    }
    LRU_Remove(handle);
  } else {
    // Increment refcount for the LRUCache if it is not in yet.
    handle->refs++;
  }

  handle->refs++;   // increment refcount for this update
  usage_ -= handle->charge;
  handle->charge = charge;
  usage_ += charge;
  LRU_Append(handle);

  LRUHandle* lh = table_.Insert(handle);
  assert(lh == nullptr);

  Reclaim();
  return true;
}

void LRUCache::Erase(const Slice& key, uint32_t hash) {
  MutexLock l(&mutex_);
  LRUHandle* e = table_.Remove(key, hash);
  if (e != nullptr) {
    VLOG(5) << __PRETTY_FUNCTION__ << " erasing key: " << BinaryToBase64(key);
    LRU_Remove(e);
    Unref(e);
  }
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
