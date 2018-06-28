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
/**
 * vim:expandtab:shiftwidth=8:tabstop=8:
 *
 * @file  secnfs.cpp
 * @brief SecureProxy
 */

#include <fstream>

#include <glog/logging.h>

#include "proxy_manager.h"
#include "secnfs_lib.h"

namespace secnfs {

SecureProxy::SecureProxy() {}


SecureProxy::SecureProxy(const std::string& name,
                         const RSA::PublicKey& public_key)
                : name_(name), key_(public_key) { }


SecureProxy::SecureProxy(const ProxyEntry& pe) : name_(pe.name()) {
        set_key(pe.key());
}


void SecureProxy::set_key(const std::string& key_str) {
        DecodeKey(&key_, key_str);
}


ProxyManager::ProxyManager(const ProxyList& plist) {
        SetProxyList(plist);
}


ProxyManager::ProxyManager(const std::string& config_file) {
        Load(config_file);
}


bool ProxyManager::Load(const std::string& config_file) {
        std::ifstream input(config_file.c_str());
        ProxyList plist;

        if (!plist.ParseFromIstream(&input)) {
                LOG(ERROR) << "cannot load ProxyManager from " << config_file;
                return false;
        }

        AddProxyList(plist);

        return true;
}


bool ProxyManager::Unload(const std::string& config_file) {
        std::ofstream output(config_file.c_str());
        ProxyList plist;

        GetProxyList(&plist);

        if (!plist.SerializeToOstream(&output)) {
                LOG(ERROR) << "cannot dump ProxyList to " << config_file;
                return false;
        }

        return true;
}


void ProxyManager::AddProxyList(const ProxyList& plist) {
        size_t old_size = proxies_.size();
        proxies_.resize(old_size + plist.proxies_size());
        for (int i = 0; i < plist.proxies_size(); ++i) {
                const ProxyEntry& pe = plist.proxies(i);
                proxies_[old_size + i].set_name(pe.name());
                proxies_[old_size + i].set_key(pe.key());
        }
}


void ProxyManager::SetProxyList(const ProxyList& plist) {
        proxies_.clear();
        AddProxyList(plist);
}


void ProxyManager::GetProxyList(ProxyList* plist) {
        for (size_t i = 0; i < proxies_.size(); ++i) {
                ProxyEntry* pe = plist->add_proxies();
                const SecureProxy& sp = proxies_[i];
                pe->set_name(sp.name());
                EncodeKey(sp.key(), pe->mutable_key());
        }
}


SecureProxy* ProxyManager::Find(const std::string& nm) {
        for (size_t i = 0; i < proxies_.size(); ++i) {
                if (proxies_[i].name() == nm) {
                        return &proxies_[i];
                }
        }

        return NULL;
}

};
