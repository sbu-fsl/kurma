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
#pragma once

#include <google/protobuf/message.h>
#include "util/slice.h"

namespace secnfs {
namespace util {

// The returned "buf" is owned by the caller, who should free it properly.
bool EncodeMessage(const google::protobuf::Message &msg, void **buf,
                   uint32_t *buf_size, uint32_t align);

bool DecodeMessage(google::protobuf::Message *msg, const void *buf,
                   uint32_t buf_size, uint32_t *msg_size);

ssize_t WriteMessageToFile(const google::protobuf::Message &msg,
                           const Slice &path);

ssize_t ReadMessageFromFile(const Slice &path, google::protobuf::Message *msg);

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:sts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
