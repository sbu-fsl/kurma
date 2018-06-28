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

#pragma once

#include <string>
#include "util/slice.h"

namespace secnfs {
namespace util {

int BinaryToBase64(Slice binary, std::string* base64);
std::string BinaryToBase64(Slice binary);

int Base64ToBinary(Slice base64, std::string* binary);
std::string Base64ToBinary(Slice base64);

int BinaryToHex(Slice binary, std::string* hex);
std::string BinaryToHex(Slice binary);

int HexToBinary(Slice hex, std::string* binary);
std::string HexToBinary(Slice hex);

}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
