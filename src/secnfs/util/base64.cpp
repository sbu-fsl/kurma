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
 * C++ wrapper of the BSD base64 lib.
 */

#include "util/base64.h"

#include <string>
#include <sstream>
#include <iomanip>

#ifdef __cplusplus
extern "C" {
#endif

// NOLINTNEXTLINE
#include "bsd-base64.h"

#ifdef __cplusplus
}
#endif

namespace secnfs {
namespace util {

int BinaryToBase64(Slice binary, std::string* base64) {
  int bin_len = binary.size();
  int buf_len = bin_len * 4 / 3 + 4;
  char* buf = new char[buf_len];
  int b64_len = b64_ntop(reinterpret_cast<const unsigned char*>(binary.data()),
                         bin_len, buf, buf_len);
  if (b64_len > 0) base64->append(buf, buf + b64_len);
  delete[] buf;
  return b64_len;
}

std::string BinaryToBase64(Slice binary) {
  std::string res;
  BinaryToBase64(binary, &res);
  return res;
}

int Base64ToBinary(Slice base64, std::string* binary) {
  int b64_len = base64.size();
  int buf_len = b64_len * 3 / 4 + 3;
  unsigned char* buf = new unsigned char[buf_len];
  int bin_len = b64_pton(base64.data(), buf, buf_len);
  if (bin_len > 0) binary->append(buf, buf + bin_len);
  delete[] buf;
  return bin_len;
}

std::string Base64ToBinary(Slice base64) {
  std::string res;
  Base64ToBinary(base64, &res);
  return res;
}

// Base64 is not a good idea for filenames because it introduces '/'s
// Here is an alternative: constant length hex strings
// TODO(mchen): the Base64 we use does not contains '/',  see line 59 of
// "support/bsd-base64.c".  It should be caused by something else, which we
// still need to find out.

#define HEX_PREFIX "pc"
#define HEX_PREFIX_LEN 2

int BinaryToHex(Slice binary, std::string* hex) {
  std::stringstream stream;
  stream << HEX_PREFIX << std::setfill('0') << std::setw(binary.size() * 2)
         << std::hex
         << *(reinterpret_cast<const uint64_t*>(binary.data()));
  hex->append(stream.str());
  return (binary.size() * 2 + HEX_PREFIX_LEN);
}

std::string BinaryToHex(Slice binary) {
  std::string res;
  BinaryToHex(binary, &res);
  return res;
}

int HexToBinary(Slice hex, std::string* binary) {
  uint64_t num = std::stoul(hex.data() + HEX_PREFIX_LEN, 0, 16);
  binary->append(reinterpret_cast<const char*>(&num), sizeof(uint64_t));
  return sizeof(uint64_t);
}

std::string HexToBinary(Slice hex) {
  std::string res;
  HexToBinary(hex, &res);
  return res;
}

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
