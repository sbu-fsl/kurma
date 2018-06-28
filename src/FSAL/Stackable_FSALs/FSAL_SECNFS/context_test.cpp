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
 * Test context.cpp
 */

#include "secnfs.h"
#include "context.h"
#include "secnfs_lib.h"

#include <string>
using std::string;

#include <gtest/gtest.h>

#include <cryptopp/osrng.h>
using CryptoPP::AutoSeededRandomPool;

using namespace secnfs;

namespace secnfs_test {

static const char* proxy_name = "context-test";

class ContextTest : public ::testing::Test {
protected:
        ContextTest() : context_(proxy_name) {}
        virtual void SetUp() {
                rsa_pri_key_.GenerateRandomWithKeySize(prng_, RSAKeyLength);
        }

        AutoSeededRandomPool prng_;
        Context context_;
        RSA::PrivateKey rsa_pri_key_;
};


TEST_F(ContextTest, Basic) {
        const string filename = "/tmp/secure-context-test.conf";
        context_.Unload(filename);

        Context new_context(proxy_name);
        new_context.Load(filename);

        EXPECT_EQ(context_.name(), new_context.name());
        EXPECT_EQ(context_.key_pair(), new_context.key_pair());
}


TEST_F(ContextTest, GenerateKeyFileCorrectly) {
        byte key[SECNFS_KEY_LENGTH + 1] = {0};
        byte iv[SECNFS_KEY_LENGTH + 1] = {0};
        KeyFile kf;

        context_.GenerateKeyFile(key, iv, SECNFS_KEY_LENGTH, &kf);

        string file_key(reinterpret_cast<char *>(key));
        for (size_t i = 0; i < kf.key_blocks_size(); ++i) {
                const KeyBlock &kb = kf.key_blocks(i);
                string recovered_key;
                RSADecrypt(rsa_pri_key_, kb.encrypted_key(), &recovered_key);
                EXPECT_EQ(recovered_key, file_key);
        }
}


TEST_F(ContextTest, TestCacheMap) {
        string key("Hello"), value("World");
        std::pair<std::string, std::string> item(key, value);

        {
                Context::hash_entry result;
                EXPECT_TRUE(context_.map_.insert(result, item));
        }

        Context::hash_entry entry;
        EXPECT_TRUE(context_.map_.find(entry, key));
        EXPECT_EQ(entry->second, value);
}

};
