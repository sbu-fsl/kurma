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
// @file  cpputil.h
// @brief C++ utils for handling the C API

#pragma once

#include <glog/logging.h>
#include <string>

#include "capi/common.h"
#include "util/slice.h"

namespace secnfs {
namespace capi {

inline int FillBuffer(secnfs::util::Slice src, buffer_t* dst) {
  if (is_empty(dst))
    return 0;

  if (src.size() > dst->size) {
    VLOG(2) << "buffer too small: " << dst->size << " but need " << src.size();
    return -EINVAL;
  }
  memmove(dst->data, src.data(), src.size());
  dst->size = src.size();
  return 0;
}

inline int FillConstBuffer(const secnfs::util::Slice& src,
                           const_buffer_t* dst) {
  dst->data = src.data();
  dst->size = src.size();
  return dst->size;
}

// Fill the buffer with C-style string ending with a '\0'.
// The returned dst->size include the ending '\0'.
inline int FillString(secnfs::util::Slice src, buffer_t* dst) {
  if (is_empty(dst))
    return 0;

  if (src.size() + 1 > dst->size) {
    VLOG(2) << "buffer too small: " << dst->size << " but need "
            << src.size() + 1;
    return -EINVAL;
  }
  memmove(dst->data, src.data(), src.size());
  dst->data[src.size()] = '\0';
  dst->size = src.size() + 1;
  return 0;
}

inline secnfs::util::Slice ToSlice(const_buffer_t b) {
  secnfs::util::Slice s(b.data, b.size);
  return s;
}

inline secnfs::util::Slice ToSlice(buffer_t b) {
  secnfs::util::Slice s(b.data, b.size);
  return s;
}

inline const_buffer_t FromSlice(const secnfs::util::Slice& slice) {
  const_buffer_t buf = {
    .data = slice.data(),
    .size = slice.size(),
  };
  return buf;
}

}  // namespace capi
}  // namespace secnfs


// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
