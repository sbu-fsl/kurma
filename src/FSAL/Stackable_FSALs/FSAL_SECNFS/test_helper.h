/*
 * Copyright (c) 2013-2018 Ming Chen
 * Copyright (c) 2016-2016 Praveen Kumar Morampudi
 * Copyright (c) 2016-2016 Harshkumar Patel
 * Copyright (c) 2017-2017 Rushabh Shah
 * Copyright (c) 2013-2014 Arun Olappamanna Vasudevan
 * Copyright (c) 2013-2014 Kelong Wang
 * Copyright (c) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
/**
 * vim:expandtab:shiftwidth=8:tabstop=8:
 *
 * Helper for unit tests.
 */

#ifndef H_TEST_HELPER
#define H_TEST_HELPER

#include "secnfs.h"
#include "context.h"

namespace secnfs_test {

#define EXPECT_OKAY(x) EXPECT_EQ(x, SECNFS_OKAY)

#define EXPECT_SAME(buf_a, buf_b, len) EXPECT_EQ(memcmp(buf_a, buf_b, len), 0)

secnfs::Context *NewContextWithProxies(int nproxy);

secnfs_info_t *NewSecnfsInfo(int nproxy);

};

#endif
