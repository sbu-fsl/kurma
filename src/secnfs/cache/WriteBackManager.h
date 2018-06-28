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
 * WriteBackManager is used to write back dirty cache extents to server.
 *
 * Design
 * - WriteBackManager polls for dirty cache extents that need to be written back
     (cache extents that have reached their writeback deadlines)
 * - These are written back invoking a registered writeback FSAL function
 * - WriteBackManager is designed as a template that accepts ProxyCache and
 *   FileCache as parameters for flexibility in unit testing. Mock types are
 *   used for ProxyCache and FileCache.
 *
 * Usage
 * - Call static function Init() with reference to ProxyCache and the writeback
 *   function to create a singleton instance of WriteBackManager
 * - To get singleton instance of WriteBackManager, call static function
 *   GetInstance()
 * - To start the polling thread, call Start() using WriteBackManager object
 *   obtained using GetInstance()
 */

#pragma once

#include "ProxyCache.h"
#include <boost/noncopyable.hpp>
#include "util/cache_interface.h"
#include "capi/common.h"
#include <thread>
#include <future>
#include <chrono>

#define POLL_TIME_GAP_MS 1000

namespace secnfs {
namespace cache {

template <typename ProxyCacheT, typename FileCacheT>
class WriteBackManagerT : private boost::noncopyable {
 private:  // functions
  // private constructor for singleton
  WriteBackManagerT(ProxyCacheT &cache, WriteBackFuncPtr writeback = nullptr)
      : pollFtr_(),
        workerFtrList_(),
        writeback_(writeback),
        cache_(cache),
        pollFtrMut_(),
        workerFtrMut_(),
        poll_(false) {}

  // main 'polling' thread function
  void PollThread();

  // writeback worker thread
  void WriteBackThread(const_buffer_t handle, size_t offset, size_t length,
                       const char *dirty_data);

  // private destructor for singleton
  ~WriteBackManagerT();

 private:  // data
  // ProxyCacheT reference is used for polling pending dirty buffer
  ProxyCacheT &cache_;

  // write back function pointer
  WriteBackFuncPtr writeback_;

  // future for polling thread
  std::shared_future<void> pollFtr_;

  // mutex to protect access to pollFtr_
  std::mutex pollFtrMut_;

  // control polling thread
  std::atomic_bool poll_;

  // futures for worker threads
  std::list<std::shared_future<void>> workerFtrList_;

  // mutex to protext access to workerFtrList_
  std::mutex workerFtrMut_;

  // singleton instance
  static WriteBackManagerT<ProxyCacheT, FileCacheT> *x;

 public:  // functions
  // get singleton instance of WriteBackManager.
  // should be called only after calling Init
  static WriteBackManagerT<ProxyCacheT, FileCacheT> &GetInstance() {
    return *x;
  }

  // initialize proxycache
  static void Init(ProxyCacheT &, WriteBackFuncPtr);

  // start write back polling thread
  void Start();

  // start write back polling thread
  void Stop();

  // delete singleton instance and free memory
  static void DeleteInstance();
};

typedef WriteBackManagerT<ProxyCache, FileCache> WriteBackManager;

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
