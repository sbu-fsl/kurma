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
/*
 * vim:expandtab:shiftwidth=8:tabstop=8:
 *
 * Test proxy_manager.cpp
 */

#include "proxy_manager.h"
#include "secnfs_lib.h"
#include "proxy_manager.h"

#include <string>
using std::string;

#include <gtest/gtest.h>

using namespace secnfs;

namespace secnfs_test {

static const char* plist_file = "pm-test.tmp";
static const char* proxy_name = "context-test";

TEST(ProxyManagerTest, Basic) {
        ProxyManager pm;

        RSAKeyPair kp;
        pm.add_proxy(SecureProxy(proxy_name, kp.pub_));

        EXPECT_TRUE(pm.Unload(plist_file));

        ProxyManager pm2;
        EXPECT_TRUE(pm2.Load(plist_file));

        EXPECT_EQ(pm.proxies_size(), 1);
        EXPECT_EQ(pm.proxies(0), pm2.proxies(0));
}

};
