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
#pragma once

// 4KB block uinit
constexpr size_t operator"" _b(unsigned long long a) { return a << 12; }

#define DISALLOW_COPY_AND_ASSIGN(T) \
  T(const T&); \
  void operator=(const T&)

namespace secnfs {
namespace util {
  
uint64_t NowMicros();

inline bool IsAligned(uint64_t size, uint64_t alignment) {
  if (alignment == 0) return true;
  return (size & (alignment - 1)) == 0;
}

inline uint64_t AlignDown(uint64_t size, uint64_t alignment) {
  if (alignment == 0) return size;
  return size & ~(alignment - 1);
}

inline uint64_t AlignUp(uint64_t size, uint64_t alignment) {
  if (alignment == 0) return size;
  return (size + alignment - 1) & ~(alignment - 1);
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:sts=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
