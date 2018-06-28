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
 * @file  proxy_cache.h
 * @brief The C interface of ProxyCache to NFS-Ganesha code.
 */

#pragma once

#include <stdlib.h>

#include "capi/common.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialize proxy cache.
 * @param cache_dir [in] the directory to save cached data
 * @return 0 if the initialization is successful, or a negative error code.
 */
int init_proxy_cache(const char* cache_dir, const char* meta_dir,
                     WriteBackFuncPtr write_back, int alignment);

/**
 * Lookup ProxyCache for a given range of a file.  Partial hit is supported at
 * the beginning or the end of the requested range.
 *
 * For example, when a user requests a byte range of [4096, 40960) and the cache
 * has [0, 20480), then the @cached_offset will be 4096 and the @cached_length
 * will be 20480 - 4096. If, instead, the cache has [20480, 40960), then the
 * @cached_offset will be 20480 and the @cached_length will be 40960 - 20480.
 *
 * Note that @buf can be null, in which case the cached data is NOT read. It can
 * be used to check if a file is cached or not without reading the cached data.
 * For example, to check if a whole file is cached:
 *
 *  if (lookup_cache(handle, 0, file_size, NULL, &off, &len) == 3) {
 *    assert(len == file_size);
 *    // whole file is cached
 *  }
 *
 * @param handle [in] cache handle
 * @param offset [in] offset within the file
 * @param length [in] bytes to read from the offset; a zero length is to check
 * if any part of the file is cached, in which case @cached_length is set to the
 * total size of data cached. When @length is 0, @buf has to be null.
 * @param buf [out] buffer to get cached data; its length should be @length if
 * not null. If @buf is null, cached data is not read, but @cached_offset and
 * @cached_length will be set correctly.
 * @param cached_offset [out] offset of the cached portion of the request
 * @param cached_length [out] bytes cached
 * @return
 *    NOT_FOUND if the requested data are not found in the cache,
 *    FRONT_MATCH if the front part of the requested data are found,
 *    BACK_MATCH if the back part of the requested data are found,
 *    FULL_MATCH if all the requested data are found,
 *    MIDDLE_MATCH if a middle portion of the requested data are found,
 *    or a negative error code.
 *
 * TODO(mchen): allow partial cache hit in the middle of requested range.
 */
int lookup_cache(const struct const_buffer_t* handle,
                 size_t offset,
                 size_t length,
                 char* buf,
                 size_t* cached_offset,
                 size_t* cached_length);

/**
 * Return whether if the specified file is dirty or not.
 *
 * @param handle [in] cache handle
 * @return true if the file is cached and has dirty data.
 */
bool is_file_dirty(const struct const_buffer_t* handle);

/**
 * Insert a byte range of a file into the cache.
 * @param handle [in] cache handle
 * @param offset [in] the offset of the byte range
 * @param length [in] the length of the byte range
 * @param buf [in] the data buffer of the byte range
 * @param writeback_seconds [in] the retention time before write the cache back
 * to server.
 * @return 0 if the insertion is successful, or a negative error code.
 */
int insert_cache(const struct const_buffer_t* handle,
                 size_t offset,
                 size_t length,
                 const char* buf,
                 int writeback_seconds);

/**
 * Commit a byte range of a file to the proxy's local persistent storage.
 * @param handle [in] cache handle
 * @param offset [in] the offset of the byte range
 * @param length [in] the length of the byte range
 * @return 0 if the commit is successful, or a negative error code.
 */
int commit_cache(const struct const_buffer_t* handle,
                 size_t offset,
                 size_t length);

/**
 * Delete the cache of a deleted file.
 * @param[in] handle Cache handle
 * @return 0 if the invalidation is successful, or a negative error code.
 */
int delete_cache(const struct const_buffer_t* handle);

/**
 * Invalidate part of a file.  It can be called by client, or by other proxies.
 *
 * @param[in]: handle Cache handle.
 * @param[in]: offset Offset of the range to be invalidated.
 * @param[in]: length Length of the range to be invalidated.
 * @param[in]: deleted Whether the original file is deleted, or it is just the
 * cache itself is being invalidated.
 *
 * @return 0 on success, or a negative error number.
 */
int invalidate_cache(const struct const_buffer_t* handle, size_t offset,
                     size_t length, bool deleted);

/**
 * Revalidate cache of the file by comparing the cached remote-change-time and
 * the most recent remote-change-time.
 *
 * @param[in]: handle Cache handle.
 * @param[in]: time_us The most recent change time made by remote gateways.
 */
void open_and_revalidate_cache(const struct const_buffer_t* handle,
                               uint64_t time_us);

/**
 * Close the file cache.
 *
 * @param[in]: handle Cache handle.
 */
void close_cache(const struct const_buffer_t* handle);

/**
 * Poll a buffer that needs to be written back to the server.
 *
 * @param handle [in/out] NFS file handle of the buffer:
 *    (1) If handle->data is not NULL, then @handle is an input argument that
 *    specifies the file to be polled.  In this case, only dirty extents belong
 *    to that file will be polled, and the deadlines of the dirty extents are
 *    ignored.
 *    (2) Otherwise, i.e., handle->data is NULL, then @handle is an output
 *    argument that will be pointed to file that is polled.  In this case, due
 *    dirty extents belonging to all files might be polled.  The dirty extent
 *    with the earliest deadline will be returned.
 * @param offset [out] block-aligned offset of the buffer within the file
 * @param length [out] block-aligned buffer length
 * @param dirty_data [out] buffer that contains dirty data. The caller
 * is responsible for releasing it using mark_writeback_done() when the
 * writeback is done.
 * @return the number of cached files that need to be written back now, or a
 * negative error code.
 *
 * The output parameters will be set only when the return value is positive.
 */
bool poll_writeback_cache(struct const_buffer_t* handle, size_t* offset,
                          size_t* length, const char** dirty_data);

/**
 * Mark a buffer that is successfully written back to the server.
 *
 * @param handle [in] NFS file handle of the buffer
 * @param offset [in] block-aligned offset of the buffer that is written back
 * @param length [in] block-aligned length of the buffer that is written back
 * @param dirty_data [in/out] buffer that contains dirty data. The pointer to
 * the original dirty_data will be reset upon this call.
 * @return 0 if successfully, or a negative error code.
 */
int mark_writeback_done(const struct const_buffer_t* handle,
                        size_t offset,
                        size_t length,
                        const char** dirty_data);

/**
 * Destroy proxy cache before the proxy exits.
 *
 * Note: the caller should ensure that all dirty data have been written back.
 */
void destroy_proxy_cache();

#ifdef __cplusplus
}
#endif

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
