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
#include "util/protobuf.h"

#include <google/protobuf/io/coded_stream.h>
using google::protobuf::io::CodedInputStream;
using google::protobuf::io::CodedOutputStream;

#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
using google::protobuf::io::ArrayInputStream;
using google::protobuf::io::ArrayOutputStream;

#include <string>
#include "util/fileutil.h"

namespace secnfs {
namespace util {

bool EncodeMessage(const google::protobuf::Message &msg, void **buf,
                   uint32_t *buf_size, uint32_t align) {
  uint32_t msg_size = msg.ByteSize();

  *buf_size = ((msg_size + sizeof(msg_size) + align - 1) / align) * align;
  *buf = static_cast<char*>(malloc(*buf_size));

  assert(*buf);

  ArrayOutputStream aos(*buf, *buf_size);
  CodedOutputStream cos(&aos);
  cos.WriteLittleEndian32(msg_size);

  return msg.SerializeToCodedStream(&cos);
}

bool DecodeMessage(google::protobuf::Message *msg, const void *buf,
                   uint32_t buf_size, uint32_t *msg_size) {
  ArrayInputStream ais(buf, buf_size);
  CodedInputStream cis(&ais);

  if (!cis.ReadLittleEndian32(msg_size)) {
    return false;
  }

  if (buf_size < *msg_size + 4) {
    return false;
  }

  cis.PushLimit(*msg_size);
  return msg->ParseFromCodedStream(&cis);
}

ssize_t WriteMessageToFile(const google::protobuf::Message &msg,
                           const Slice &path) {
  void* buf = NULL;
  uint32_t buf_size = 0;
  if (!EncodeMessage(msg, &buf, &buf_size, 1)) {
    return -EIO;
  }
  Slice contents(static_cast<char*>(buf), buf_size);
  ssize_t res = WriteToFile(path, contents, true);
  free(buf);
  return res;
}

ssize_t ReadMessageFromFile(const Slice &path, google::protobuf::Message *msg) {
  std::string content;
  ssize_t res = ReadFromFile(path, &content);
  if (res < 0)
    return res;
  uint32_t msg_size = 0;
  if (!DecodeMessage(msg, content.data(), content.length(), &msg_size)) {
    return -EINVAL;
  }
  return msg_size;
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:sts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
