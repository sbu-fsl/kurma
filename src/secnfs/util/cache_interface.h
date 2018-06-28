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
// A CacheInterface is an interface that maps keys to values.  It has internal
// synchronization and may be safely accessed concurrently from multiple
// threads.  It may automatically evict entries to make room for new entries.
// Values have a specified charge against the cache capacity.  For example, a
// cache where the values are variable length strings, may use the length of the
// string as the charge for the string.
//
// A builtin cache implementation with a least-recently-used eviction
// policy is provided.  Clients may use their own implementations if
// they want something more sophisticated (like scan-resistance, a
// custom eviction policy, variable cache sizing, etc.)

#pragma once

#include <stdint.h>
#include "util/slice.h"

namespace secnfs {
namespace util {

class CacheInterface;

// Create a new cache with a fixed size capacity.  This implementation
// of Cache uses a least-recently-used eviction policy.
CacheInterface* NewCache(size_t capacity);

// Opaque handle to an entry stored in the cache.
struct CacheHandle { };

// The deleter of cache entries. It will be called when the refcount of a
// cache handle drops to zero.
//
// @key is the cache key @value is the cache value associated with the key
// @exiting indicates if a cache entry is being deleted because of cache
// eviction or a normal exit of the program.
typedef void (*CacheDeleter)(const Slice& key, void* value, bool exiting);

// TODO(mingch): make value type a template argument
class CacheInterface {
 public:
  // Destroys all existing entries by calling the "deleter"
  // function that was passed to the constructor.
  virtual ~CacheInterface() = 0;

  // Insert a mapping from key->value into the cache and assign it
  // the specified charge against the total cache capacity.
  //
  // Returns a handle that corresponds to the mapping.  The caller
  // must call this->Release(handle) when the returned mapping is no
  // longer needed.
  //
  // When the inserted entry is no longer needed, the key and
  // value will be passed to "deleter".
  virtual CacheHandle* Insert(const Slice& key, void* value, size_t charge,
                              CacheDeleter deleter) = 0;

  // Update the charge of an existing object.
  // Return false if the key has been replaced with a diferent value.
  //
  // The cache handle's reference count will be incremented on success.
  virtual bool Update(const Slice& key, CacheHandle* handle, size_t charge) = 0;

  // If the cache has no mapping for "key", returns nullptr.
  //
  // Else return a handle that corresponds to the mapping.  The caller
  // must call this->Release(handle) when the returned mapping is no
  // longer needed.
  virtual CacheHandle* Lookup(const Slice& key) = 0;

  // Release a mapping returned by a previous Lookup().
  // REQUIRES: handle must not have been released yet.
  // REQUIRES: handle must have been returned by a method on *this.
  virtual void Release(CacheHandle* handle) = 0;

  // Return the value encapsulated in a handle returned by a
  // successful Lookup().
  // REQUIRES: handle must not have been released yet.
  // REQUIRES: handle must have been returned by a method on *this.
  virtual void* Value(CacheHandle* handle) = 0;

  // Return the value encapsulated in a handle returned by a successful
  // Lookup().  No handle will be hold.  Therefore, the returned value might no
  // longer exists in the case after the return.
  virtual void* LookupValue(const Slice& key);

  // If the cache contains entry for key, erase it.  Note that the
  // underlying entry will be kept around until all existing handles
  // to it have been released.
  virtual void Erase(const Slice& key) = 0;

  // Return a new numeric id.  May be used by multiple clients who are
  // sharing the same cache to partition the key space.  Typically the
  // client will allocate a new id at startup and prepend the id to
  // its cache keys.
  virtual uint64_t NewId() = 0;
};

class CacheHandleReleaser {
 public:
  CacheHandleReleaser(CacheInterface* cache, CacheHandle* handle) :
    cache_(cache), handle_(handle) {}
  ~CacheHandleReleaser() {
    if (handle_ != nullptr)
      cache_->Release(handle_);
  }

 private:
  CacheInterface* cache_;
  CacheHandle* handle_;
};

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
