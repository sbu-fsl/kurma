/*
 * Copyright (c) 2013-2018 Ming Chen
 * Copyright (c) 2016-2016 Praveen Kumar Morampudi
 * Copyright (c) 2016-2016 Harshkumar Patel
 * Copyright (c) 2017-2017 Rushabh Shah
 * Copyright (c) 2013-2014 Arun Olappamanna Vasudevan
 * Copyright (c) 2013-2014 Kelong Wang
 * Copyright (c) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
/**
 * vim:noexpandtab:shiftwidth=8:tabstop=8:
 */
#include <execinfo.h>

#include "fsal.h"
#include "export_mgr.h"
#include "pcachefs_methods.h"
#include "cache_handle.h"
#include "antivirus.h"
#include "nfs_file_handle.h"
#include "writeback_manager.h"

#include "minmax.h"
#include "capi/proxy_cache.h"

/************************* helpers **********************/

#define print_base64(hdl)                                                      \
	{                                                                      \
		int out_len = (hdl)->size * 4 / 3 + 4;                         \
		char out[out_len];                                             \
		int b64_len =                                                  \
		    b64_ntop((hdl)->data, (hdl)->size, out, out_len);          \
		out[b64_len] = '\0';                                           \
		LogFullDebug(COMPONENT_FSAL, "handle in base64: %s", out);     \
	}

#define lock_mutex_av(obj)                                                     \
	do {                                                                   \
		if (enable_av) {                                               \
			pthread_mutex_lock(&(obj)->pc_lock);                   \
		}                                                              \
	} while (0)

#define unlock_mutex_av(obj)                                                   \
	do {                                                                   \
		if (enable_av) {                                               \
			pthread_mutex_unlock(&(obj)->pc_lock);                 \
		}                                                              \
	} while (0)

static void atomic_set(uint64_t *ptr, uint64_t new_value)
{
	uint64_t old_value;

	do {
		old_value = __sync_fetch_and_or(ptr, 0);
	} while (!__sync_bool_compare_and_swap(ptr, old_value, new_value));
}


static void print_backtrace(void)
{
	void *array[30];
	int size;
	int i;
	char **bt_symbols;

	size = backtrace(array, 30);
	bt_symbols = backtrace_symbols(array, size);

	fprintf(stderr, "Obtained %zd stack frames.\n", size);

	for (i = 0; i < size; i++)
		fprintf(stderr, "%s\n", bt_symbols[i]);

	free(bt_symbols);
}

/* get effective filesize */
static inline uint64_t pc_get_filesize(struct pcachefs_fsal_obj_handle *hdl)
{
	uint64_t filesize;

	pthread_mutex_lock(&hdl->pc_lock);
	filesize = hdl->pc_filesize;
	pthread_mutex_unlock(&hdl->pc_lock);

	return filesize;
}

/* update effective filesize in handle */
static inline void pc_set_filesize(struct pcachefs_fsal_obj_handle *hdl,
				   uint64_t new_size)
{
	pthread_mutex_lock(&hdl->pc_lock);
	hdl->pc_filesize = new_size;
	pthread_mutex_unlock(&hdl->pc_lock);
}

static inline void pc_inc_filesize(struct pcachefs_fsal_obj_handle *hdl,
				   uint64_t new_size)
{
	pthread_mutex_lock(&hdl->pc_lock);
	if (new_size > hdl->pc_filesize) {
		hdl->pc_filesize = new_size;
	}
	pthread_mutex_unlock(&hdl->pc_lock);
}

static bool __pc_lock_range(struct pcachefs_fsal_obj_handle *hdl,
			    uint64_t lower, uint64_t upper)
{
	uint64_t lb = ROUNDDOWN(lower);
	uint64_t ub = ROUNDUP(upper);
	bool res = true;
	uint64_t block_offset;
	int i;

	if (!hdl->locked_blocks) {
		hdl->locked_blocks =
		    g_array_new(false, false, sizeof(uint64_t));
	}

	for (i = 0; i < hdl->locked_blocks->len; ++i) {
		int bi = g_array_index(hdl->locked_blocks, uint64_t, i);
		if (bi >= lb && bi < ub) {
			res = false;
			goto out;
		}
	}

	for (block_offset = lb; block_offset < ub; block_offset += ALIGNMENT) {
		g_array_append_val(hdl->locked_blocks, block_offset);
	}

out:
	return res;
}

static bool pc_lock_range(struct pcachefs_fsal_obj_handle *hdl, uint64_t lower,
			  uint64_t upper)
{
	bool res;

	pthread_mutex_lock(&hdl->pc_lock);
	res = __pc_lock_range(hdl, lower, upper);
	pthread_mutex_unlock(&hdl->pc_lock);
	return res;
}

static void __pc_unlock_range(struct pcachefs_fsal_obj_handle *hdl,
			    uint64_t lower, uint64_t upper)
{
	uint64_t lb = ROUNDDOWN(lower);
	uint64_t ub = ROUNDUP(upper);
	int count = ub - lb;
	int i;

	if (!hdl->locked_blocks) {
		hdl->locked_blocks =
		    g_array_new(false, false, sizeof(uint64_t));
	}

	for (i = 0; i < hdl->locked_blocks->len && count > 0;) {
		int bi = g_array_index(hdl->locked_blocks, uint64_t, i);
		if (bi >= lb && bi < ub) {
			g_array_remove_index_fast(hdl->locked_blocks, i);
			--count;
		} else {
			++i;
		}
	}
}

static void pc_unlock_range(struct pcachefs_fsal_obj_handle *hdl,
			    uint64_t lower, uint64_t upper)
{
	pthread_mutex_lock(&hdl->pc_lock);
	__pc_unlock_range(hdl, lower, upper);
	pthread_mutex_unlock(&hdl->pc_lock);
}

static bool pc_lock_block(struct pcachefs_fsal_obj_handle *hdl,
			  uint64_t block_offset) {
	return pc_lock_range(hdl, block_offset, block_offset + ALIGNMENT);
}

static void pc_unlock_block(struct pcachefs_fsal_obj_handle *hdl,
			    uint64_t block_offset) {
	pc_unlock_range(hdl, block_offset, block_offset + ALIGNMENT);
}

#define MAKE_PCACHE_FH(func, parent, handle, args...)                          \
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(parent);        \
	struct fsal_obj_handle *next_hdl;                                      \
	fsal_status_t st;                                                      \
	st = next_ops.obj_ops->func(hdl->next_handle, ##args, &next_hdl);      \
	if (FSAL_IS_ERROR(st)) {                                               \
		return st;                                                     \
	}                                                                      \
	return make_handle_from_next(op_ctx->fsal_export, next_hdl, handle);

// TODO remove exp: to use op_ctx->export
static struct pcachefs_fsal_obj_handle *
alloc_handle(struct fsal_export *exp, const struct attrlist *attr)
{
	struct pcachefs_fsal_obj_handle *hdl;
	fsal_status_t st;
	pthread_rwlockattr_t attrs;

	hdl = gsh_calloc(1, sizeof(*hdl));
	if (hdl == NULL)
		return NULL;

	fsal_obj_handle_init(&hdl->obj_handle, exp, attr->type);
	update_attributes(attr, &hdl->obj_handle.attributes);
	hdl->pc_filesize = attr->filesize;

	pthread_rwlockattr_init(&attrs);
#ifdef GLIBC
	pthread_rwlockattr_setkind_np(
	    &attrs, PTHREAD_RWLOCK_PREFER_WRITER_NONRECURSIVE_NP);
#endif
	pthread_mutex_init(&hdl->pc_lock, NULL);

	return hdl;
}

/**
 * Create a PCACHE obj handle from a corresponding handle of the next layer.
 *
 * @param[IN]       exp      PCACHE export
 * @param[IN/OUT]   next_hdl handle of the next layer
 * @param[OUT]      handle   resultant PCACHE handle
 *
 * NOTE: next_hdl will be released on failure!
 */
static fsal_status_t make_handle_from_next(struct fsal_export *exp,
					   struct fsal_obj_handle *next_hdl,
					   struct fsal_obj_handle **handle)
{
	struct pcachefs_fsal_obj_handle *pcachefs_hdl;
	fsal_status_t st;

	pcachefs_hdl = alloc_handle(exp, &next_hdl->attributes);
	if (!pcachefs_hdl) {
		LogMajor(COMPONENT_FSAL, "cannot allocate pcache handle");
		next_ops.obj_ops->release(next_hdl);
		return fsalstat(ERR_FSAL_NOMEM, 0);
	}

	pcachefs_hdl->next_handle = next_hdl;
	*handle = &pcachefs_hdl->obj_handle;

	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

typedef fsal_status_t (*op_cb)(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			       size_t buffer_size, void *buffer, size_t *amount,
			       bool *status);

// TODO should we save the digest somewhere?
static struct const_buffer_t *
alloc_cache_handle(struct pcachefs_fsal_obj_handle *obj_hdl)
{
	struct gsh_buffdesc fh_desc;
	struct const_buffer_t *chdl =
	    gsh_malloc(sizeof(struct const_buffer_t) +
		       sizeof(struct alloc_file_handle_v4));

	if (!chdl) {
		PC_FATAL("out of memory when allocating cache handle for %p", obj_hdl);
	}

	fh_desc.addr = ((char *)(chdl) + sizeof(struct const_buffer_t));
	fh_desc.len = sizeof(struct alloc_file_handle_v4);

	fsal_status_t ret = next_ops.obj_ops->handle_digest(
	    obj_hdl->next_handle, FSAL_DIGEST_NFSV4, &fh_desc);
	if (FSAL_IS_ERROR(ret)) {
		free(chdl);
		PC_FATAL("Unable to generate digest for %p", obj_hdl);
	}

	chdl->data = fh_desc.addr;
	chdl->size = fh_desc.len;

	/*print_base64(chdl);*/
	return chdl;
}

static void free_cache_handle(struct const_buffer_t *chdl)
{
	gsh_free(chdl);
}

static fsal_status_t get_object_handle(struct const_buffer_t *hdl,
				       struct fsal_obj_handle **obj_hdl)
{
	print_base64(hdl);
	struct gsh_buffdesc hdl_desc = { .addr = (void *)hdl->data,
					 .len = hdl->size };
	fsal_status_t ret =
	    pcachefs_create_handle(super_fsal_export, &hdl_desc, obj_hdl);
	if (FSAL_IS_ERROR(ret)) {
		PC_FATAL("Unable to object handle from descriptor");
		return ret;
	}
	PC_FULL("Object handle %p", *obj_hdl);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

static fsal_status_t op_in_loop(op_cb op, size_t max_amt, bool quit_on_true,
				struct fsal_obj_handle *obj_hdl,
				uint64_t offset, size_t buffer_size,
				void *buffer, size_t *amount, bool *status)
{
	uint64_t op_offset = offset;
	size_t op_remaining = buffer_size;
	size_t op_length;
	void *op_buff = buffer;
	size_t op_amount = 0;
	bool op_status;
	fsal_status_t ret;

	*status = true;
	*amount = 0;

	do {
		op_length = op_remaining <= max_amt ? op_remaining : max_amt;
		ret = op(obj_hdl, op_offset, op_length, op_buff, &op_amount,
			 &op_status);
		if (FSAL_IS_ERROR(ret)) {
			return ret;
		}
		op_offset += op_amount;
		op_remaining -= op_amount;
		op_buff += op_amount;
		*amount += op_amount;
		if (op_status && quit_on_true) {
			*status = true;
			break;
		}
		*status = *status && op_status;
	} while (op_remaining);

	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

static inline fsal_status_t read_in_loop(op_cb op, size_t max_read,
					 struct fsal_obj_handle *obj_hdl,
					 uint64_t offset, size_t buffer_size,
					 void *buffer, size_t *read_amount,
					 bool *end_of_file)
{
	return op_in_loop(op, max_read, true, obj_hdl, offset, buffer_size,
			  buffer, read_amount, end_of_file);
}

static inline fsal_status_t write_in_loop(op_cb op, size_t max_write,
					  struct fsal_obj_handle *obj_hdl,
					  uint64_t offset, size_t buffer_size,
					  void *buffer, size_t *write_amount,
					  bool *fsal_stable)
{
	return op_in_loop(op, max_write, false, obj_hdl, offset, buffer_size,
			  buffer, write_amount, fsal_stable);
}

/**
 * Read from the next layer, and put newly read data into proxy cache. It may
 * read less than requested.  This should be called only after the requested
 * data are not found in the proxy cache.
 */
static fsal_status_t read_and_fill(struct fsal_obj_handle *obj_hdl,
				   uint64_t offset, size_t buffer_size,
				   void *buffer, size_t *read_amount,
				   bool *end_of_file)
{
	fsal_status_t ret;
	int insert_ret;
	struct const_buffer_t *chdl = NULL;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	size_t filesize = pc_get_filesize(hdl);

	ret = next_ops.obj_ops->read(hdl->next_handle, offset, buffer_size,
				     buffer, read_amount, end_of_file);
	if (FSAL_IS_ERROR(ret)) {
		return ret;
	}

	// We are reading holes, this can happen if we have extended the file
	// size by writting beyond the file size to the cache, but the dirty
	// write has not been flushed back to the server yet.  Therefore, the
	// server side's file size is the old size not the extended one.
	//
	// +----------+----------+----------+
	// | server   | hole     | cache    |
	// +----------+----------+----------+
	//            ^  ^    ^
	//            |  |    |
	//            |  | offset + buffer_size
	//            |  |
	//            | offset
	//            |
	//   obj_hdl->attributes.filesize
	if (*read_amount == 0 && *end_of_file) {
		assert(offset + buffer_size < filesize);
		memset(buffer, 0, buffer_size);
		*read_amount = buffer_size;
	}

	if (*read_amount > 0) {
		PC_DEBUG("inserting %lu bytes into cache at %lu", *read_amount,
			 offset);
		chdl = alloc_cache_handle(hdl);
		insert_ret = insert_cache(chdl, offset, *read_amount,
					  buffer, -1);
		PC_FATAL_IF((insert_ret != 0),
			    "insert_cache returned %d", insert_ret);
		free_cache_handle(chdl);
	}

	*end_of_file = ((offset + *read_amount) == filesize);

	LogDebug(COMPONENT_FSAL,
		 "return: %d; read %zd bytes at %zd with end-of-file %s\n",
		 ret.major, *read_amount, offset,
		 (*end_of_file ? "set" : "unset"));

	return ret;
}

/**
 * Perform block-aligned read.  This is an optimization based on the assumption
 * that the underlying FSAL is doing block-aligned operation anyway.
 */
static fsal_status_t aligned_read(struct fsal_obj_handle *obj_hdl,
				  uint64_t offset, size_t buffer_size,
				  void *buffer, size_t *read_amount,
				  bool *end_of_file)
{
	fsal_status_t ret;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	size_t aligned_offset = ROUNDDOWN(offset);
	size_t aligned_size = ROUNDUP(offset + buffer_size) - aligned_offset;
	void *aligned_buffer = buffer;
	bool unaligned = aligned_offset != offset ||
			 ((offset + buffer_size) < pc_get_filesize(hdl) &&
			  buffer_size != aligned_size);
	size_t cached_offset, cached_length;
	struct const_buffer_t *chdl;
	int cache_status;

	if (unaligned) {
		aligned_buffer = gsh_malloc(aligned_size);
		PC_FULL("unaligned read adjusted: offset %zd -> %zd; "
			"size %zd -> %zd",
			offset, aligned_offset, buffer_size, aligned_size);
		PC_FATAL_IF(aligned_buffer == NULL, "out of memory");
	}

	/**
	 * We need to exclude the part of the dirty data within
	 * [aligned_offset, aligned_offset + aligned_size) that has already
	 * been cached but not yet written back to server.
	 */
	if (offset != aligned_offset) {
		chdl = alloc_cache_handle(hdl);
		cache_status = lookup_cache(chdl, aligned_offset,
					    aligned_size, aligned_buffer,
					    &cached_offset, &cached_length);
		if (cache_status == FRONT_MATCH) {
			aligned_size -= cached_length;
			aligned_offset += cached_length;
		} else if (cache_status == FULL_MATCH) {
			free_cache_handle(chdl);
			return fsalstat(ERR_FSAL_NO_ERROR, 0);
		} else if (cache_status == BACK_MATCH) {
			aligned_size -= cached_length;
		} else if (cache_status != NOT_FOUND) {
			PC_FATAL("unexpected cache status of [%lu, +%lu): %d",
				 aligned_offset, aligned_size, cache_status);
		}
		free_cache_handle(chdl);
	}

	ret = read_and_fill(obj_hdl, aligned_offset, aligned_size,
			    aligned_buffer, read_amount, end_of_file);
	if (FSAL_IS_ERROR(ret)) {
		return ret;
	}

	// adjust the results if alignment happened
	if (unaligned) {
		PC_FATAL_IF(*read_amount + aligned_offset <= offset,
			    "this should not happend because the underlying "
			    "integrity layer operates on block bounds");
		*end_of_file = *end_of_file && (offset + buffer_size >=
						aligned_offset + *read_amount);
		*read_amount = ccan_min(*read_amount + aligned_offset - offset,
					buffer_size);
		memmove(buffer, aligned_buffer + (offset - aligned_offset),
			*read_amount);
	}

	return ret;
}

/*
 *    cache | server | remarks
 *    ------+--------+----------------------------------------------------------
 * (1)   Y  |   Y    | cache has latest data
 * (2)   Y  |   N    | DIRTY data (writeback pending)
 * (3)   N  |   Y    | data to be read from server and inserted to cache (CLEAN)
 * (4)   N  |   N    | hole (TODO(arun): mark in cache?). memset zero
 */
static fsal_status_t __cachefs_read(struct fsal_obj_handle *obj_hdl,
				    uint64_t offset, size_t buffer_size,
				    void *buffer, size_t *read_amount,
				    bool *end_of_file)
{
	size_t cached_offset, cached_length;
	// based on how much data is cached, we'll read remaining data from
	// server
	size_t new_buffer_size = buffer_size;
	fsal_status_t ret = {0};

	*read_amount = 0;

	// CASE (1) and (2) <- not distinguishable
	// lookup cache (and read data into buffer)
	struct const_buffer_t *chdl;
	chdl = alloc_cache_handle(pcachefs_handle(obj_hdl));

	PC_FULL("handle: %p", obj_hdl);
	int lookup_ret = lookup_cache(chdl, offset, buffer_size, buffer,
				      &cached_offset, &cached_length);
	if (lookup_ret == NOT_FOUND) {
		__sync_fetch_and_add(&pc_counters.cache_misses, 1);
		ret = aligned_read(obj_hdl, offset, buffer_size, buffer,
				   read_amount, end_of_file);
		PC_FULL("lookup of %p at offset %zu length %zu not found",
			obj_hdl, offset, buffer_size);
	} else if (lookup_ret == FRONT_MATCH || lookup_ret == FULL_MATCH) {
		__sync_fetch_and_add(&pc_counters.cache_full_hits, 1);
		*read_amount = cached_length;
		PC_FULL("front or full match (%d) of %p: cached offset: %zu, "
			"length: %zu", lookup_ret, obj_hdl, cached_offset,
			cached_length);
	} else if (lookup_ret == BACK_MATCH || lookup_ret == MIDDLE_MATCH) {
		__sync_fetch_and_add(&pc_counters.cache_partial_hits, 1);
		assert(cached_offset > offset);
		new_buffer_size = cached_offset - offset;
		ret = read_in_loop(aligned_read, pcache_maxread, obj_hdl,
				   offset, new_buffer_size, buffer, read_amount,
				   end_of_file);
		if (FSAL_IS_ERROR(ret)) {
			goto free_and_exit;
		}
		assert(*read_amount == new_buffer_size);
		*read_amount += cached_length;
		PC_FULL("partial match (%d) of %p: cached offset: %zu, "
			"length: %zu",
			lookup_ret, obj_hdl, cached_offset, cached_length);
	} else {
		PC_FATAL("invalid cache lookup result: %d", lookup_ret);
		ret = fsalstat(ERR_FSAL_IO, 0);
	}

free_and_exit:
	free_cache_handle(chdl);
	return ret;
}

/********************************************************/

fsal_status_t cachefs_read(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			   size_t buffer_size, void *buffer,
			   size_t *read_amount, bool *end_of_file, bool full)
{
	fsal_status_t ret;
	size_t cur_size;

	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);

	cur_size = pc_get_filesize(hdl);

	// check end of file
	if (offset >= cur_size) {
		*end_of_file = true;
		*read_amount = 0;
		ret = fsalstat(ERR_FSAL_NO_ERROR, 0);
		goto unlock_and_exit;
	}

	if (offset + buffer_size >= cur_size) {
		buffer_size = cur_size - offset;
	}

	if (full) {
		ret = read_in_loop(__cachefs_read, pcache_maxread, obj_hdl,
				   offset, buffer_size, buffer, read_amount,
				   end_of_file);
	} else {
		ret = __cachefs_read(obj_hdl, offset, buffer_size, buffer,
				     read_amount, end_of_file);
	}

	*end_of_file = ((offset + *read_amount) == cur_size);

unlock_and_exit:
	return ret;
}

static inline bool is_block_cached(struct const_buffer_t *cache_handle,
				   size_t block_offset, size_t sizelimit)
{
	size_t cached_offset, cached_length;
	size_t len = ALIGNMENT;
	int ret;

	if (block_offset + len > sizelimit) {
		len = sizelimit - block_offset;
	}
	ret = lookup_cache(cache_handle, block_offset, len, NULL,
			    &cached_offset, &cached_length);
	PC_DEBUG("is_block_cached: [%lu, %lu): %d", block_offset,
		 block_offset + len, ret);
	return ret == FULL_MATCH;
}

static fsal_status_t fill_aligned_range(struct pcachefs_fsal_obj_handle *hdl,
					size_t aligned_offset,
					size_t aligned_size,
					struct const_buffer_t *cache_handle,
					size_t filesize)
{
	size_t block = aligned_offset;
	size_t read_amount;
	bool eof;
	char *buf;
	fsal_status_t st;

	PC_DEBUG("filling block at %lu with %lu bytes", aligned_offset,
		 aligned_size);

	buf = malloc(ALIGNMENT);
	for (; block < aligned_offset + aligned_size; block += ALIGNMENT) {
		if (is_block_cached(cache_handle, block, filesize)) {
			continue;
		}
		while (!pc_lock_block(hdl, block)) {
			usleep(1000);
			continue;
		}
		if (!is_block_cached(cache_handle, block, filesize)) {
			st = read_and_fill(&hdl->obj_handle, block, ALIGNMENT,
					   buf, &read_amount, &eof);
			if (FSAL_IS_ERROR(st)) {
				goto out;
			}
		}
		pc_unlock_block(hdl, block);
		block += ALIGNMENT;
	}

out:
	free(buf);
	return st;
}

static size_t pad_file_to_size(struct pcachefs_fsal_obj_handle *hdl,
			       struct const_buffer_t *chandle,
			       size_t new_filesize)
{
	int ret;
	char *buffer;
	size_t block_beg;
	size_t block_end;


	pthread_mutex_lock(&hdl->pc_lock);
	PC_DEBUG("padding file size from %lu to %lu", hdl->pc_filesize,
		 new_filesize);
	if (hdl->pc_filesize >= new_filesize) {
		new_filesize = hdl->pc_filesize;
		goto exit;
	}

	block_end = MIN(new_filesize, ROUNDUP(hdl->pc_filesize));
	if (block_end > hdl->pc_filesize) {
		buffer = calloc(block_end - hdl->pc_filesize, 1);
		ret = insert_cache(chandle, hdl->pc_filesize,
				   block_end - hdl->pc_filesize,
				   buffer, -1);
		if (ret != 0) {
			PC_FATAL("insert_cache failed: %d", ret);
		}
		free(buffer);
	}

	block_beg = MAX(hdl->pc_filesize, ROUNDDOWN(new_filesize));
	if (block_beg >= block_end && new_filesize > block_beg) {
		buffer = calloc(new_filesize - block_beg, 1);
		ret = insert_cache(chandle, block_beg,
				   new_filesize - block_beg,
				   buffer, -1);
		if (ret != 0) {
			PC_FATAL("insert_cache failed: %d", ret);
		}
		free(buffer);
	}
	hdl->pc_filesize = new_filesize;

exit:
	pthread_mutex_unlock(&hdl->pc_lock);
	return new_filesize;
}

fsal_status_t cachefs_write(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			    size_t buffer_size, void *buffer,
			    size_t *write_amount, bool *fsal_stable)
{
	struct const_buffer_t *chdl;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	fsal_status_t st;
	struct timespec ctime;
	int ret;
	size_t aligned_offset = ROUNDDOWN(offset);
	size_t aligned_size;
	size_t filesize = pc_get_filesize(hdl);

	PC_DEBUG("writing at %lu with %lu bytes", offset, buffer_size);
	chdl = alloc_cache_handle(hdl);

	while (offset > filesize) {
		filesize = pad_file_to_size(hdl, chdl, offset);
	}

	if (offset + buffer_size > filesize) {
		aligned_size = (offset + buffer_size) - aligned_offset;
	} else {
		aligned_size = MIN(ROUNDUP(offset + buffer_size), filesize) -
			       aligned_offset;
	}

	// Check if read-modify-update is necessary or not
	if (offset < filesize &&
	    ((offset != aligned_offset) || (buffer_size != aligned_size))) {
		st = fill_aligned_range(hdl, aligned_offset, aligned_size,
					chdl, filesize);
		if (FSAL_IS_ERROR(st)) {
			return st;
		}
	}

	ret = insert_cache(chdl, offset, buffer_size, buffer,
			   writeback_seconds);
	free_cache_handle(chdl);
	if (ret == 0) {
		*fsal_stable = true;
		*write_amount = buffer_size;
		st = fsalstat(ERR_FSAL_NO_ERROR, 0);
		pc_inc_filesize(hdl, offset + buffer_size);
		now(&ctime);
		atomic_set(&hdl->pc_ctime, timespec_to_nsecs(&ctime));
	} else {
		PC_FATAL("insert_cache returned %d", ret);
	}

	return st;
}

fsal_status_t cachefs_commit(struct fsal_obj_handle *obj_hdl, /* sync */
			     off_t offset, size_t len)
{
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	fsal_status_t r;
	struct const_buffer_t *chdl =
	    alloc_cache_handle(pcachefs_handle(obj_hdl));

	// TODO(arun): compute offset and len if it's 0,0
	int ret = commit_cache(chdl, offset, len);
	free_cache_handle(chdl);
	if (ret == 0) {
		r = fsalstat(ERR_FSAL_NO_ERROR, 0);
	} else {
		PC_FATAL("commit_cache returned %d", ret);
		r = fsalstat(ERR_FSAL_NOMEM, 0);
	}
	return r;
}

bool read_file_and_scan(struct fsal_obj_handle *obj_hdl)
{
	void *file_buff;
	fsal_status_t ret;
	size_t read_amount;
	bool end_of_file;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	size_t file_size = pc_get_filesize(hdl);

	PC_FULL("Size of file %lu", file_size);
	if (file_size > av_max_filesize) {
		LogWarn(COMPONENT_FSAL, "File %p not scanned: Too large.",
			obj_hdl);
		return true;
	}

	file_buff = gsh_malloc(file_size);
	if (!file_buff) {
		PC_FATAL("File %p not scanned: Out of memory.", obj_hdl);
		return true;
	}

	ret = cachefs_read(obj_hdl, 0, file_size, file_buff, &read_amount,
			   &end_of_file, true);
	if (read_amount < file_size) {
		LogWarn(COMPONENT_FSAL, "File %p not scanned: "
					"read_amount %lu, read length "
					"%lu, end of file %d",
			obj_hdl, read_amount, file_size, end_of_file);
		gsh_free(file_buff);
		return true;
	}

	__sync_fetch_and_add(&pc_counters.av_scans, 1);
	if (av_scan(file_buff, file_size) != 0) {
		PC_CRT("malware detected by ClamAV!");
		gsh_free(file_buff);
		return false;
	}

	gsh_free(file_buff);
	return true;
}

fsal_status_t cachefs_file_unlink(struct fsal_obj_handle *dir_hdl,
				  const char *name)
{
	fsal_status_t ret;
	struct fsal_obj_handle *obj_hdl;
	struct const_buffer_t *chdl;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(dir_hdl);
	int cret;

	// lookup object handle
	ret = cachefs_lookup(dir_hdl, name, &obj_hdl);
	if (FSAL_IS_ERROR(ret)) {
		LogMajor(COMPONENT_FSAL, "Cannot find fsal object");
		return ret;
	}

	chdl = alloc_cache_handle(pcachefs_handle(obj_hdl));

	// remove from cache
	// TODO(arun): hardlinks
	if ((cret = delete_cache(chdl)) < 0) {
		PC_FATAL("cache invalidate returned error %d", cret);
	}
	free_cache_handle(chdl);
	LogDebug(COMPONENT_FSAL, "file unlinked: %s\n", name);
	return next_ops.obj_ops->unlink(next_handle(dir_hdl), name);
}

fsal_status_t cachefs_getattrs(struct fsal_obj_handle *obj_hdl)
{
	struct fsal_obj_handle *next_hdl;
	fsal_status_t ret;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	struct timespec ctime;

	next_hdl = next_handle(obj_hdl);
	ret = next_ops.obj_ops->getattrs(next_hdl);
	if (!FSAL_IS_ERROR(ret)) {
		if (timespec_diff(&obj_hdl->attributes.chgtime,
				  &next_hdl->attributes.chgtime) > 0) {
			PC_DEBUG("chtime %ld -> %ld: update filesize to %ld",
				 timespec_to_nsecs(&obj_hdl->attributes.chgtime),
				 timespec_to_nsecs(&next_hdl->attributes.chgtime),
				 next_hdl->attributes.filesize);
			update_attributes(&next_hdl->attributes,
					  &obj_hdl->attributes);
			pc_set_filesize(hdl, next_hdl->attributes.filesize);
			obj_hdl->attributes.change = timespec_to_nsecs(
					&next_hdl->attributes.chgtime);
		} else {
			update_attributes(&next_hdl->attributes,
					  &obj_hdl->attributes);
			obj_hdl->attributes.filesize = pc_get_filesize(hdl);
			PC_DEBUG("chtime %ld -> %ld: use old filesize %ld",
				 timespec_to_nsecs(&obj_hdl->attributes.chgtime),
				 timespec_to_nsecs(&next_hdl->attributes.chgtime),
				 obj_hdl->attributes.filesize);
		}
	}

	nsecs_to_timespec(__sync_fetch_and_or(&hdl->pc_ctime, 0), &ctime);
	if (gsh_time_cmp(&ctime, &next_hdl->attributes.ctime) > 0) {
		obj_hdl->attributes.ctime = ctime;
		obj_hdl->attributes.mtime = ctime;
		obj_hdl->attributes.change = timespec_to_nsecs(&ctime);
	}
	return ret;
}

fsal_status_t cachefs_setattrs(struct fsal_obj_handle *obj_hdl,
			       struct attrlist *attrs)
{
	fsal_status_t ret;
	size_t old_size, new_size;
	int cret;
	struct const_buffer_t *chdl;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);

	lock_mutex_av(hdl);
	old_size = pc_get_filesize(hdl);
	if (FSAL_TEST_MASK(attrs->mask, ATTR_SIZE)) {
		chdl = alloc_cache_handle(hdl);
		new_size = attrs->filesize;
		if (new_size < old_size) {
			cret = invalidate_cache(chdl, new_size,
						(old_size - new_size), true);
			if (cret != 0) {
				PC_FATAL("Invalidate cache failed for "
					 "[%lu. %lu) with ret %d",
					 new_size, old_size, cret);
				print_base64(chdl);
			}
			pc_set_filesize(hdl, new_size);
		} else if (new_size > old_size) {
			pad_file_to_size(hdl, chdl, new_size);
		}
		LogDebug(COMPONENT_FSAL, "file size set from %zu to %zu\n",
			 old_size, new_size);
		free_cache_handle(chdl);
	}

	ret = next_ops.obj_ops->setattrs(hdl->next_handle, attrs);

unlock:
	unlock_mutex_av(hdl);
	return ret;
}

fsal_status_t cachefs_lookup_path(struct fsal_export *exp_hdl, const char *path,
				  struct fsal_obj_handle **handle)
{
	struct pcachefs_fsal_export *exp = pcachefs_export(exp_hdl);
	struct fsal_obj_handle *next_hdl;
	fsal_status_t st;

	// register super FSAL
	if (super_fsal_export == NULL) {
		assert(op_ctx->fsal_export == op_ctx->export->fsal_export);
		get_gsh_export_ref(op_ctx->export);
		// TODO(arun): put ref at umount (release?)
		super_fsal_export = op_ctx->fsal_export;
		LogDebug(COMPONENT_FSAL, "super fsal_export: %p",
			 super_fsal_export);
	}
	// get maxread and maxwrite
	pcache_maxread =
	    op_ctx->fsal_export->ops->fs_maxread(op_ctx->fsal_export);
	pcache_maxwrite =
	    op_ctx->fsal_export->ops->fs_maxwrite(op_ctx->fsal_export);
	LogDebug(COMPONENT_FSAL, "max read %lu, max write %lu", pcache_maxread,
		 pcache_maxwrite);

	st = next_ops.exp_ops->lookup_path(exp->sub_export, path, &next_hdl);
	if (FSAL_IS_ERROR(st)) {
		return st;
	}

	return make_handle_from_next(exp_hdl, next_hdl, handle);
}

fsal_status_t cachefs_lookup(struct fsal_obj_handle *parent, const char *path,
			     struct fsal_obj_handle **handle)
{
	MAKE_PCACHE_FH(lookup, parent, handle, path);
}

fsal_status_t scan_and_write(struct fsal_obj_handle *obj_hdl)
{
	struct const_buffer_t *chdl;
	fsal_status_t ret;
	size_t offset, length;
	const char *dirty_data;
	size_t write_amount;
	bool fsal_stable;
	bool pending_wb;
	int r;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);

	chdl = alloc_cache_handle(hdl);

	// why lock: we don't want following situations (1) and (2):
	//
	// thread 1              | thread 2
	// ----------------------+--------------------------
	// =====================(1)=========================
	// scan completed        |
	//                       | write: insert new data or
	//                       | setattrs: change file size
	// writeback             |
	//                       |
	// [Problem unscanned data is written back!]
	//                       |
	// =====================(2)==========================
	//  close: scanning      | scanner thread: scanning
	//                       |
	//  [Just inefficient]   |
	//
	lock_mutex_av(hdl);
	pending_wb =
	    poll_writeback_cache(chdl, &offset, &length, &dirty_data);
	if (!pending_wb) {
		ret = fsalstat(ERR_FSAL_NO_ERROR, 0);
		goto unlock;
	}
	if (enable_av) {
		if (!read_file_and_scan(obj_hdl)) {
			size_t filesize = pc_get_filesize(hdl);
			if ((r = mark_writeback_done(chdl, offset, length,
						     &dirty_data)) != 0) {
				PC_FATAL("mark_writeback_done "
					 "returned %d obj_hdl = "
					 "%p, offset = %zu, "
					 "length = %zu",
					 r, obj_hdl, offset, length);
				print_base64(chdl);
			}
			if ((r = invalidate_cache(chdl, 0, filesize,
						  true)) != 0) {
				PC_FATAL(
				    "Invalidate cache failed for [%zu. %zu) "
				    "with ret %d",
				    0, filesize, r);
				print_base64(chdl);
			}
			/*
			 * We return a security error in case the file is
			 * infected.  Another alternative is to write to a
			 * quarantined virus data folder depending on the
			 * security policy.
			 */
			ret = fsalstat(ERR_FSAL_SEC, NFS4ERR_FILE_INFECTED);
			goto unlock;
		}
	}

	// writebacks
	do {
		LogDebug(COMPONENT_FSAL,
			 "offset: %zu, buffer_size: %zu, obj_hdl: %p", offset,
			 length, obj_hdl);
		ret = write_in_loop(next_ops.obj_ops->write, pcache_maxwrite,
				    hdl->next_handle, offset, length,
				    (void *)dirty_data, &write_amount,
				    &fsal_stable);
		LogDebug(COMPONENT_FSAL, "Write amount: %zu/%zu", write_amount,
			 length);
		if (FSAL_IS_ERROR(ret)) {
			print_backtrace();
			PC_FATAL("write_in_loop failed: major: %d, minor: %d",
				 ret.major, ret.minor);
			goto unlock;
		}
		if ((r = mark_writeback_done(chdl, offset, length,
					     &dirty_data)) != 0) {
			PC_FATAL("mark_writeback_done returned "
				 "%d: obj_hdl = %p, offset = "
				 "%zu, length = %zu",
				 r, obj_hdl, offset, length);
			print_base64(chdl);
		}
	} while (poll_writeback_cache(chdl, &offset, &length, &dirty_data));

unlock:
	unlock_mutex_av(hdl);
	free_cache_handle(chdl);
	return ret;
}

static fsal_status_t cachefs_do_close(struct pcachefs_fsal_obj_handle *hdl,
				      struct const_buffer_t *chdl)
{
	fsal_status_t ret;

	pthread_mutex_lock(&hdl->pc_lock);
	if (hdl->pc_opener_count == 0) {
		ret = next_ops.obj_ops->close(hdl->next_handle);
		if (FSAL_IS_ERROR(ret)) {
			PC_FATAL("failed to close file (%p): %d", hdl,
				 ret.major);
		}
		hdl->pc_file_flags = FSAL_O_CLOSED;
		close_cache(chdl);
	}
	pthread_mutex_unlock(&hdl->pc_lock);

	return ret;
}


fsal_status_t cachefs_close(struct fsal_obj_handle *obj_hdl)
{
	fsal_status_t ret = {0};
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	struct fsal_obj_handle *next_hdl = hdl->next_handle;
	bool infected = false;
	struct const_buffer_t *chdl = NULL;
	uint32_t open_count = 0;
	bool dirty = false;

	PC_DBG("closing file: %p", obj_hdl);

	pthread_mutex_lock(&hdl->pc_lock);
	if (hdl->pc_opener_count == 0) {
		PC_FATAL("cannot close without opener");
	}
	hdl->pc_opener_count -= 1;
	open_count = hdl->pc_opener_count;
	pthread_mutex_unlock(&hdl->pc_lock);

	if (open_count > 0) {
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
	}

	chdl = alloc_cache_handle(hdl);
	dirty = is_file_dirty(chdl);

	if (dirty && writeback_seconds == 0) {
		ret = scan_and_write(obj_hdl);
		if (IS_INFECTED(ret)) {
			PC_FATAL("file is infected");
			goto exit;
		} else if (FSAL_IS_ERROR(ret)) {
			PC_FATAL(
			    "scan_and_write returned error major %d, minor %d",
			    ret.major, ret.minor);
			goto exit;
		}
		dirty = false;
	}

	if (!dirty) {
		ret = deregister_wb_file(obj_hdl);
		if (FSAL_IS_ERROR(ret)) {
			print_backtrace();
			PC_WARN("deregister_wb_file failed");
		}

		while (is_file_dirty(chdl)) {
			PC_DBG("waiting for writing back to finish");
			usleep(10000); // 10 miliseconds
		}

		cachefs_do_close(hdl, chdl);
	}

exit:
	free_cache_handle(chdl);
	return ret;
}

void writeback_thread(struct fsal_obj_handle *obj_hdl)
{
	struct user_cred user_credentials;
	struct req_op_context req_ctx;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	fsal_status_t ret;
	struct const_buffer_t *chdl = NULL;
	bool closing = false;

	// set op_ctx
	memset(&req_ctx, 0, sizeof(struct req_op_context));
	op_ctx = &req_ctx;
	op_ctx->fsal_export = super_fsal_export;
	op_ctx->creds = &user_credentials;
	init_credentials();
	op_ctx->creds->caller_uid = 0;
	op_ctx->creds->caller_gid = 0;

	ret = scan_and_write(obj_hdl);
	if (FSAL_IS_ERROR(ret)) {
		PC_FATAL("failed to writeback file (%p): %d",
			 obj_hdl, ret.major);
	}


	pthread_mutex_lock(&hdl->pc_lock);
	closing =
	    (hdl->pc_file_flags != FSAL_O_CLOSED) && hdl->pc_opener_count == 0;
	pthread_mutex_unlock(&hdl->pc_lock);

	if (writeback_seconds > 0 && closing) {
		chdl = alloc_cache_handle(hdl);
		ret = deregister_wb_file(obj_hdl);
		if (FSAL_IS_ERROR(ret)) {
			print_backtrace();
			PC_WARN("deregister_wb_file failed");
		}
		cachefs_do_close(hdl, chdl);
		free_cache_handle(chdl);
	}
}

fsal_status_t cachefs_open(struct fsal_obj_handle *obj_hdl,
			   fsal_openflags_t openflags)
{
	fsal_status_t ret;
	nsecs_elapsed_t chgtime;
	struct const_buffer_t *chdl = NULL;
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	struct fsal_obj_handle *next_hdl = hdl->next_handle;

	PC_DBG("opening file %p with flags: %d", obj_hdl, openflags);

	pthread_mutex_lock(&hdl->pc_lock);
	hdl->pc_opener_count += 1;
	PC_DEBUG("opener count increased to %d", hdl->pc_opener_count);
	if (hdl->pc_file_flags != FSAL_O_CLOSED) {
		PC_DEBUG("file %p already opened, status changed from %d to %d",
			 hdl, hdl->pc_file_flags, openflags);
		hdl->pc_file_flags = openflags;
		pthread_mutex_unlock(&hdl->pc_lock);
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
	}
	pthread_mutex_unlock(&hdl->pc_lock);

	ret = next_ops.obj_ops->open(next_hdl, openflags);
	if (FSAL_IS_ERROR(ret)) {
		return ret;
	}

	chdl = alloc_cache_handle(hdl);

	// revalidate cache
	pthread_rwlock_rdlock(&next_hdl->lock);
	chgtime = timespec_to_nsecs(&next_hdl->attributes.chgtime);
	pthread_rwlock_unlock(&next_hdl->lock);
	open_and_revalidate_cache(chdl, chgtime);

	ret = register_wb_file(obj_hdl);
	if (FSAL_IS_ERROR(ret)) {
		goto exit;
	}

	pthread_mutex_lock(&hdl->pc_lock);
	hdl->pc_file_flags = openflags;
	pthread_mutex_unlock(&hdl->pc_lock);

exit:
	free_cache_handle(chdl);
	return ret;
}

fsal_status_t cachefs_create(struct fsal_obj_handle *dir_hdl, const char *name,
			     struct attrlist *attrib,
			     struct fsal_obj_handle **handle)
{
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(dir_hdl);
	struct fsal_obj_handle *next_hdl;
	fsal_status_t st;

	PC_DBG("CREATING '%s' in dir hdl (%x)", name, hdl);

	st =
	    next_ops.obj_ops->create(hdl->next_handle, name, attrib, &next_hdl);
	if (FSAL_IS_ERROR(st))
		return st;

	st = make_handle_from_next(op_ctx->fsal_export, next_hdl, handle);
	if (FSAL_IS_ERROR(st)) {
		LogCrit(COMPONENT_FSAL, "cannot create pcachefs handle");
		return st;
	}

	return st;
}

fsal_status_t cachefs_makedir(struct fsal_obj_handle *dir_hdl, const char *name,
			      struct attrlist *attrib,
			      struct fsal_obj_handle **handle)
{
	MAKE_PCACHE_FH(mkdir, dir_hdl, handle, name, attrib);
}

fsal_status_t cachefs_makenode(struct fsal_obj_handle *dir_hdl,
			       const char *name, object_file_type_t nodetype,
			       fsal_dev_t *dev, /* IN */
			       struct attrlist *attrib,
			       struct fsal_obj_handle **handle)
{
	MAKE_PCACHE_FH(mknode, dir_hdl, handle, name, nodetype, dev, attrib);
}

fsal_status_t cachefs_symlink(struct fsal_obj_handle *dir_hdl, const char *name,
			      const char *link_path, struct attrlist *attrib,
			      struct fsal_obj_handle **handle)
{
	MAKE_PCACHE_FH(symlink, dir_hdl, handle, name, link_path, attrib);
}

fsal_status_t cachefs_create_handle(struct fsal_export *exp_hdl,
				    struct gsh_buffdesc *hdl_desc,
				    struct fsal_obj_handle **handle)
{
	fsal_status_t st;
	struct fsal_obj_handle *next_hdl;
	struct pcachefs_fsal_export *pcachefs_exp = pcachefs_export(exp_hdl);

	st = next_ops.exp_ops->create_handle(pcachefs_exp->sub_export, hdl_desc,
					     &next_hdl);
	if (FSAL_IS_ERROR(st)) {
		PC_CRT("cannot create next handle (%d, %d)", st.major,
		       st.minor);
		return st;
	}

	PC_DBG("handle created by pcachefs_create_handle\n");

	return make_handle_from_next(exp_hdl, next_hdl, handle);
}

void cachefs_release(struct fsal_obj_handle *obj_hdl)
{
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	struct fsal_obj_handle *next_hdl = hdl->next_handle;

	fsal_obj_handle_uninit(obj_hdl);
	if (hdl->locked_blocks) {
		g_array_free(hdl->locked_blocks, false);
	}
	pthread_mutex_destroy(&hdl->pc_lock);
	gsh_free(hdl);
	next_ops.obj_ops->release(next_hdl);
}
