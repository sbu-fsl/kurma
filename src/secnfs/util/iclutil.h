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
// Boost ICL library utils.

#pragma once

#include <boost/icl/interval_map.hpp>

namespace secnfs {
namespace util {

inline boost::icl::interval<size_t>::type make_interval(size_t offset,
                                                        size_t length) {
  return boost::icl::interval<size_t>::right_open(offset, offset + length);
}

inline boost::icl::interval<size_t>::type make_interval2(size_t lower,
                                                         size_t upper) {
  return boost::icl::interval<size_t>::right_open(lower, upper);
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:sts=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
