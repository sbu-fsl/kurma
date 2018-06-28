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
// Common data structure and enum used by both the C and C++ code.

#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// A simple struct that represents a data buffer.  It is the same as Ganesha's
// gsh_buffer_t.
//
// When used as an output argument, "data" should point to a pre-allocated
// buffer whose size is specified by "size"; the caller should fill data into
// "data" and set "size" to the number of bytes that has been filled.
struct buffer_t {
  char* data;
  size_t size;
};

struct const_buffer_t {
  const char* data;
  size_t size;
};

struct range_t {
  size_t offset;
  size_t length;
};

enum cache_lookup_result {
  NOT_FOUND = 0,
  FRONT_MATCH = 1,
  BACK_MATCH = 2,
  FULL_MATCH = 3,
  MIDDLE_MATCH = 4,
};

// NOLINTNEXTLINE
static inline bool is_empty(const struct buffer_t* buf) {
  return buf->data == NULL || buf->size == 0;
}

typedef bool (*WriteBackFuncPtr)(struct const_buffer_t* cache_hdl,
                                 uint64_t offset, size_t buffer_size,
                                 const void* buffer, size_t* write_amount);

#ifdef __cplusplus
}
#endif

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
