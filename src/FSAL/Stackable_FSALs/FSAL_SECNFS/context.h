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
 * @file  secnfs.h
 * @brief Encrypt and decrypt data
 */

#ifndef H_SECNFS_CONTEXT
#define H_SECNFS_CONTEXT

#include "secnfs.h"
#include "secnfs_lib.h"
#include "secnfs.pb.h"
#include "proxy_manager.h"

#include <vector>
#include <string>

#include <cryptopp/rsa.h>
using CryptoPP::RSA;

#include <tbb/concurrent_hash_map.h>

namespace secnfs {

struct CacheCompare {
        static size_t hash(const std::string &key) {
                 size_t h = 0;
                 for (const char* s = key.c_str(); *s; ++s)
                         h = (h*17)^*s;
                 return h;
        }

        static bool equal(const std::string &k1, const std::string &k2) {
                return k1 == k2;
        }
};

/**
 * Secure Proxy Context.
 */
class Context {
public:
        Context(const std::string& name);
        ~Context();

        tbb::concurrent_hash_map<std::string, std::string, CacheCompare> map_;
        typedef tbb::concurrent_hash_map<std::string, std::string,
                                         CacheCompare>::accessor hash_entry;

        bool Load(const std::string &filename);
        bool Unload(const std::string &filename);

        // Add current proxy into the proxy list.
        bool AddCurrentProxy();

        // key, and iv should be terminated by '\0'.
        void GenerateKeyFile(byte *key, byte *iv, int len, KeyFile *kf);

        const std::string& name() const { return name_; }
        void set_name(const std::string &nm) { name_ = nm; }

        const RSAKeyPair& key_pair() const { return key_pair_; }
        const RSA::PublicKey& pub_key() const { return key_pair_.pub_; }
        const RSA::PrivateKey& pri_key() const { return key_pair_.pri_; }

        ProxyManager& proxy_manager() { return pm_; }

private:
        std::string name_;              /*!< name of current proxy */
        RSAKeyPair key_pair_;           /*!< RSA key pair */

        ProxyManager pm_;              /*!< manager of proxies */
};

};

#endif
