//  Copyright (c) 2013, Facebook, Inc.  All rights reserved.
//  This source code is licensed under the BSD-style license found in the
//  LICENSE file in the root directory of this source tree. An additional grant
//  of patent rights can be found in the PATENTS file in the same directory.
//
// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.
//
// Simple hash function used for internal data structures

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "util/slice.h"

namespace secnfs {
namespace util {

uint32_t Hash(const char* data, size_t n, uint32_t seed);

inline uint32_t GetSliceHash(const Slice& s) {
  return Hash(s.data(), s.size(), 8887);
}

}  // namespace util
}  // namespace secnfs

namespace std {
template<> struct hash<secnfs::util::Slice> {
  size_t operator()(const secnfs::util::Slice& s) const {
    return GetSliceHash(s);
  }
};
}

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
