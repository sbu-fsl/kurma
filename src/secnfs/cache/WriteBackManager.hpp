#include "WriteBackManager.h"

namespace secnfs {
namespace cache {

template <typename ProxyCacheT, typename FileCacheT>
WriteBackManagerT<ProxyCacheT, FileCacheT> *
    WriteBackManagerT<ProxyCacheT, FileCacheT>::x = nullptr;

template <typename ProxyCacheT, typename FileCacheT>
void WriteBackManagerT<ProxyCacheT, FileCacheT>::Init(
    ProxyCacheT &cache, WriteBackFuncPtr writeback) {
  if (x != nullptr) {
    delete x;
    LOG(WARNING) << "Re-initializing WriteBackManager";
  }
  x = new WriteBackManagerT<ProxyCacheT, FileCacheT>(cache, writeback);
}

template <typename ProxyCacheT, typename FileCacheT>
void WriteBackManagerT<ProxyCacheT, FileCacheT>::DeleteInstance() {
  if (x != nullptr) {
    delete x;
  }
  x = nullptr;
}

template <typename ProxyCacheT, typename FileCacheT>
void WriteBackManagerT<ProxyCacheT, FileCacheT>::WriteBackThread(
    const_buffer_t handle, size_t offset, size_t length,
    const char *dirty_data) {
  // do write back
  size_t write_amount;
  if (!writeback_(&handle, offset, length, (const void *)dirty_data,
                  &write_amount)) {
    LOG(ERROR) << "writeback returned error";
    write_amount = 0;
  }

  // mark markwriteback done
  std::string fh(handle.data, handle.size);
  int ret = cache_.MarkWriteBackDone(fh, offset, write_amount, &dirty_data);
  if (ret != 0) {
    LOG(ERROR) << "MarkWriteBackDone returned error" << ret;
  }
}

template <typename ProxyCacheT, typename FileCacheT>
void WriteBackManagerT<ProxyCacheT, FileCacheT>::PollThread() {
  while (poll_.load()) {
    // Remove old worker threads
    {
      std::lock_guard<std::mutex> lock(workerFtrMut_);
      workerFtrList_.remove_if([](std::shared_future<void> &ftr) {
        auto status = ftr.wait_for(std::chrono::milliseconds(0));
        return (status == std::future_status::ready);
      });
    }

    // Poll for writebacks
    const_buffer_t handle = {0};
    size_t offset, length;
    const char *dirty_data;
    int ret = cache_.PollWriteBack(&handle, &offset, &length, &dirty_data);
    if (ret < 0) {
      LOG(ERROR) << "PollWriteBack returned error " << ret;
      continue;
    } else if (ret == 0) {
      // Wait and continue
      std::this_thread::sleep_for(std::chrono::milliseconds(POLL_TIME_GAP_MS));
      continue;
    }

    // Create new worker threads
    std::lock_guard<std::mutex> lock(workerFtrMut_);
    workerFtrList_.push_back(
        std::async(std::launch::async,
                   &WriteBackManagerT<ProxyCacheT, FileCacheT>::WriteBackThread,
                   this, handle, offset, length, dirty_data).share());
  }
}

template <typename ProxyCacheT, typename FileCacheT>
void WriteBackManagerT<ProxyCacheT, FileCacheT>::Start() {
  if (writeback_ == nullptr) return;
  poll_.store(true);
  std::lock_guard<std::mutex> lock(pollFtrMut_);
  if (pollFtr_.valid()) return;
  pollFtr_ = std::async(std::launch::async,
                        &WriteBackManagerT<ProxyCacheT, FileCacheT>::PollThread,
                        this).share();
}

template <typename ProxyCacheT, typename FileCacheT>
void WriteBackManagerT<ProxyCacheT, FileCacheT>::Stop() {
  poll_.store(false);
  {
    std::lock_guard<std::mutex> lock(workerFtrMut_);
    for (auto &ftr : workerFtrList_) {
      ftr.wait();
    }
    workerFtrList_.clear();
  }
  std::lock_guard<std::mutex> lock(pollFtrMut_);
  if (pollFtr_.valid()) pollFtr_.wait();
}

template <typename ProxyCacheT, typename FileCacheT>
WriteBackManagerT<ProxyCacheT, FileCacheT>::~WriteBackManagerT() {
  Stop();
}

}  // namespace cache
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
