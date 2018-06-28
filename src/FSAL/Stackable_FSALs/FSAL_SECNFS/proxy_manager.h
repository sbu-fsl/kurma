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
 * @brief SecureProxy
 */

#ifndef H_SECNFS_PROXY
#define H_SECNFS_PROXY

#include <string>
#include <vector>

#include <cryptopp/rsa.h>
using CryptoPP::RSA;

#include "secnfs.pb.h"
#include "secnfs_lib.h"

namespace secnfs {

/**
 * A SecureProxy class that contains public information of a proxy.
 *
 * SecureProxy information should be maintained by a public-key server.
 *
 * It can be encoded into or decoded from a ProxyEntry.
 */
class SecureProxy {
public:
        SecureProxy();
        SecureProxy(const std::string& name, const RSA::PublicKey& public_key);
        SecureProxy(const ProxyEntry& pe);

        const std::string& name() const { return name_; }
        void set_name(const std::string& nm) { name_ = nm; }

        const RSA::PublicKey& key() const { return key_; }
        void set_key(const std::string& key_str);

        bool operator==(const SecureProxy& sp) const {
                return name() == sp.name() && IsSamePublicKey(key(), sp.key());
        }

private:
        std::string name_;
        RSA::PublicKey key_;
};


/**
 * Manager of the proxy list.
 *
 * Ideally, it makes RPC to the public key server to get proxy information.
 * For now, it just read proxy list from config file.
 */
class ProxyManager {
public:
        ProxyManager() {}
        ProxyManager(const std::string& config_file);
        ProxyManager(const ProxyList& plist);

        bool Load(const std::string& config_file);
        bool Unload(const std::string& config_file);

        void AddProxyList(const ProxyList& plist);
        void SetProxyList(const ProxyList& plist);
        void GetProxyList(ProxyList* plist);

        size_t proxies_size() const { return proxies_.size(); }
        const SecureProxy& proxies(size_t i) const { return proxies_[i]; }
        void add_proxy(const SecureProxy& sp) { proxies_.push_back(sp); }

        SecureProxy* Find(const std::string& nm);

private:
        std::vector<SecureProxy> proxies_;
};

};


#endif
