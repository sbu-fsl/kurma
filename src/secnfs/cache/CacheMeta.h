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
// A CacheEntry is a cached file extent (identfied by a secnfs.util.Range).  A
// CacheFile consists of one or more CacheEntry(s), and zero or more file
// hole(s).
//
// Each CacheEntry has a range that specifies its position in the containing
// file and a CacheMeta, which contains auxiliary metadata of the cache entry
// such as dirty state, access time, access frequency etc.

// A CacheMeta contains the metadata of a CacheEntry, which is a range of a
// FileCache's address space.

#pragma once

#include <vector>

namespace secnfs {
namespace cache {

class CacheMeta {
 public:
  virtual ~CacheMeta() = 0;

  // Merge (mix) the meta data of two CacheEntry of a common range
  // Return true if the metadata of the two entries can be merged.
  virtual bool Merge(const CacheMeta& other) = 0;

  // Join the meta data of two touching CacheEntry 
  // Return true if join is successful.
  virtual bool Join(const CacheMeta& other) = 0;

  // Split the meta data at the specified position.
  virtual bool Split(uint64 pos, CacheMeta* first, CacheMeta* second) = 0;

  // Getters of basic attributes.
  virtual bool dirty() const = 0;
  virtual uint64 offset() const = 0;
  virtual uint64 length() const = 0;
};

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
