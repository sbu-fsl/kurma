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
 * @brief Encrypt and decrypt data
 */

#include "context.h"
#include "secnfs.pb.h"

#include <fstream>
#include <assert.h>

#include <glog/logging.h>

#include <cryptopp/osrng.h>
using CryptoPP::AutoSeededRandomPool;

namespace secnfs {

// TODO accept option
Context::Context(const std::string& name) : name_(name) {}


Context::~Context() {}


bool Context::Load(const std::string& filename) {
        SecureContextConfig config;
        std::ifstream input(filename.c_str());

        if (!config.ParseFromIstream(&input)) {
                LOG(ERROR) << "cannot load context";
                return false;
        }

        assert(name_ == config.name());

        DecodeKey(&(key_pair_.pri_), config.pri_key());
        DecodeKey(&(key_pair_.pub_), config.pub_key());

        return true;
}


bool Context::Unload(const std::string& filename) {
        // TODO encryption the file
        SecureContextConfig config;
        config.set_name(name_);
        EncodeKey(key_pair_.pri_, config.mutable_pri_key());
        EncodeKey(key_pair_.pub_, config.mutable_pub_key());
        assert(config.pri_key().length() > 0);
        assert(config.pub_key().length() > 0);

        std::ofstream output(filename.c_str());
        if (!config.SerializeToOstream(&output)) {
                LOG(ERROR) << "cannot save context";
                return false;
        }
        output.close();

        return true;
}


bool Context::AddCurrentProxy() {
        SecureProxy* existed = pm_.Find(name_);
        if (existed != NULL) {
                LOG(ERROR) << "proxy " << name_ << " already exists";
                return false;
        }

        pm_.add_proxy(SecureProxy(name(), pub_key()));

        return true;
}


void Context::GenerateKeyFile(byte* key, byte* iv, int len, KeyFile* kf)
{
        kf->set_iv(reinterpret_cast<char*>(iv), len);

        for (size_t i = 0; i < pm_.proxies_size(); ++i) {
                const SecureProxy& p = pm_.proxies(i);

                KeyBlock* block = kf->add_key_blocks();
                block->set_proxy_name(p.name());

                RSAEncrypt(p.key(),
                           std::string(reinterpret_cast<char*>(key), len),
                           block->mutable_encrypted_key());
        }
}

};
