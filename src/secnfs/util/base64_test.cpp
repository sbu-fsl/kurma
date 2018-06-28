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
#include <gtest/gtest.h>
#include <glog/logging.h>

#include <string>

#include "util/base64.h"
#include "util/iclutil.h"
#include "util/testutil.h"

namespace secnfs {
namespace util {
namespace test {

class Base64Test : public ::testing::Test {
 public:
  Base64Test() : rnd_(8887) {}

  bool DoesNotHasSlash(const std::string& b64) {
    return b64.find('/') == std::string::npos;
  }

 protected:
  secnfs::util::Random rnd_;
};

TEST_F(Base64Test, Basics) {
  for (int i = 0; i < 1000; ++i) {
    std::string binary = RandomKey(&rnd_, i);
    std::string base64 = BinaryToBase64(binary);
    EXPECT_TRUE(DoesNotHasSlash(base64));
    std::string recover = Base64ToBinary(base64);
    EXPECT_EQ(recover, binary);
  }
}

TEST_F(Base64Test, NoSlash) {
  unsigned char a[4];
  a[0] = 252;
  a[1] = 'a';
  a[2] = 'b';
  a[3] = '\0';
  std::string b64 = BinaryToBase64(reinterpret_cast<char *>(a));
  EXPECT_TRUE(DoesNotHasSlash(b64));
}

}  // namespace test
}  // namespace util
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
