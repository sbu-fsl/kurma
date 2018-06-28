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
#include <glog/logging.h>
#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include "WriteBackManager.hpp"

namespace secnfs {
namespace cache {
namespace test {

using namespace ::testing;

class MockFileCache {
 public:
  MOCK_CONST_METHOD0(handle, const std::string&());
  MOCK_CONST_METHOD5(Lookup,
                     ssize_t(size_t offset, size_t length, char* buf,
                             size_t* cached_offset, size_t* cache_length));
};

class MockProxyCache {
 public:
  MOCK_CONST_METHOD4(PollWriteBack,
                     int(const_buffer_t* handle, size_t* offset, size_t* length,
                         const char** dirty_data));
  MOCK_CONST_METHOD4(MarkWriteBackDone,
                     int(const Slice& fh, size_t offset, size_t length,
                         const char** dirty_data));
};

typedef WriteBackManagerT<MockProxyCache, MockFileCache> WBM4Test;

bool WriteBackFunc(const_buffer_t* cache_hdl, uint64_t offset,
                   size_t buffer_size, const void* buffer,
                   size_t* write_amount) {
  *write_amount = buffer_size;
  return true;
}

TEST(WriteBackManagerTest, PollThreadRuns) {
  int npoll = 5;
  MockProxyCache cache;
  EXPECT_CALL(cache, PollWriteBack(NotNull(), NotNull(), NotNull(), NotNull()))
      .Times(AtLeast(npoll - 1))
      .WillRepeatedly(Return(0));
  WBM4Test::Init(cache, &WriteBackFunc);
  WBM4Test& obj = WBM4Test::GetInstance();
  obj.Start();
  std::this_thread::sleep_for(
      std::chrono::milliseconds(POLL_TIME_GAP_MS * npoll));
  obj.Stop();
  WBM4Test::DeleteInstance();
}

TEST(WriteBackManagerTest, PollThreadStops) {
  MockProxyCache cache;
  EXPECT_CALL(cache, PollWriteBack(NotNull(), NotNull(), NotNull(), NotNull()))
      .Times(AtMost(1))
      .WillRepeatedly(Return(0));
  WBM4Test::Init(cache, &WriteBackFunc);
  WBM4Test& obj = WBM4Test::GetInstance();
  obj.Start();
  obj.Stop();
  std::this_thread::sleep_for(std::chrono::milliseconds(POLL_TIME_GAP_MS * 5));
  WBM4Test::DeleteInstance();
}

TEST(WriteBackManagerTest, WorkerThreadRuns) {
  int npoll = 5;
  MockProxyCache cache;
  const_buffer_t handle = {"123456", 6};
  const char* dirty_data = "a man a plan a canal panama";
  EXPECT_CALL(cache, PollWriteBack(NotNull(), NotNull(), NotNull(), NotNull()))
      .Times(AtLeast(npoll - 1))
      .WillRepeatedly(DoAll(SetArgPointee<0>(handle), SetArgPointee<1>(100UL),
                            SetArgPointee<2>(200UL),
                            SetArgPointee<3>(dirty_data), Return(1)));
  EXPECT_CALL(cache, MarkWriteBackDone(Slice("123456"), 100UL, 200UL,
                                       Pointee(StrEq(dirty_data))))
      .Times(AtLeast(npoll - 1))
      .WillRepeatedly(Return(0));
  WBM4Test::Init(cache, &WriteBackFunc);
  WBM4Test& obj = WBM4Test::GetInstance();
  obj.Start();
  std::this_thread::sleep_for(
      std::chrono::milliseconds(POLL_TIME_GAP_MS * npoll));
  obj.Stop();
  WBM4Test::DeleteInstance();
}

}  // test
}  // cache
}  // secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
