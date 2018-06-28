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
/* file.c
 * File I/O methods for PCACHE module
 */

#include "config.h"

#include <assert.h>
#include "fsal.h"
#include "FSAL/access_check.h"
#include "fsal_convert.h"
#include <unistd.h>
#include <fcntl.h>
#include "FSAL/fsal_commonlib.h"
#include "pcachefs_methods.h"
#include "cache_handle.h"

/** pcachefs_open
 * called with appropriate locks taken at the cache inode level
 */

fsal_status_t pcachefs_open(struct fsal_obj_handle *obj_hdl,
			    fsal_openflags_t openflags)
{
	fsal_status_t st;

	PC_START_TIMER();
	__sync_fetch_and_add(&pc_counters.nr_opens, 1);
	st = cachefs_open(obj_hdl, openflags);
	PC_STOP_TIMER(open_time_ns);

	return st;
}

/* pcachefs_status
 * Let the caller peek into the file's open/close state.
 */

fsal_openflags_t pcachefs_status(struct fsal_obj_handle *obj_hdl)
{
	struct pcachefs_fsal_obj_handle *hdl = pcachefs_handle(obj_hdl);
	fsal_openflags_t flags = FSAL_O_CLOSED;

	pthread_mutex_lock(&hdl->pc_lock);
	if (hdl->pc_opener_count > 0) {
		flags = hdl->pc_file_flags;
	}
	pthread_mutex_unlock(&hdl->pc_lock);
	PC_DBG("status of file %p: %d", hdl, flags);

	return flags;
}

/* pcachefs_read
 * concurrency (locks) is managed in cache_inode_*
 */
fsal_status_t pcachefs_read(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			    size_t buffer_size, void *buffer,
			    size_t *read_amount, bool *end_of_file)
{
	fsal_status_t st;

	PC_START_TIMER();
	ops_hist_add(&pc_counters.ops_hist, offset, buffer_size);
	__sync_fetch_and_add(&pc_counters.nr_reads, 1);
	PC_FULL("reading (%zd, %zd) of file %p", offset, buffer_size, obj_hdl);
	st = cachefs_read(obj_hdl, offset, buffer_size, buffer, read_amount,
			  end_of_file, false);
	PC_STOP_TIMER(read_time_ns);

	return st;
}

/* pcachefs_write
 * concurrency (locks) is managed in cache_inode_*
 */

fsal_status_t pcachefs_write(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			     size_t buffer_size, void *buffer,
			     size_t *write_amount, bool *fsal_stable)
{
	fsal_status_t st;

	PC_START_TIMER();
	ops_hist_add(&pc_counters.ops_hist, offset, buffer_size);
	__sync_fetch_and_add(&pc_counters.nr_writes, 1);
	PC_FULL("writing (%zd, %zd) of file %p", offset, buffer_size, obj_hdl);
	st = cachefs_write(obj_hdl, offset, buffer_size, buffer, write_amount,
			   fsal_stable);
	PC_STOP_TIMER(write_time_ns);

	return st;
}

/* pcachefs_commit
 * Commit a file range to storage.
 * for right now, fsync will have to do.
 */

fsal_status_t pcachefs_commit(struct fsal_obj_handle *obj_hdl, /* sync */
			      off_t offset, size_t len)
{
	__sync_fetch_and_add(&pc_counters.nr_commits, 1);
	return cachefs_commit(obj_hdl, offset, len);
}

/* pcachefs_lock_op
 * lock a region of the file
 * throw an error if the fd is not open.  The old fsal didn't
 * check this.
 */

fsal_status_t pcachefs_lock_op(struct fsal_obj_handle *obj_hdl, void *p_owner,
			       fsal_lock_op_t lock_op,
			       fsal_lock_param_t *request_lock,
			       fsal_lock_param_t *conflicting_lock)
{
	return next_ops.obj_ops->lock_op(next_handle(obj_hdl), p_owner, lock_op,
					 request_lock, conflicting_lock);
}

/* pcachefs_close
 * Close the file if it is still open.
 * Yes, we ignor lock status.  Closing a file in POSIX
 * releases all locks but that is state and cache inode's problem.
 */

fsal_status_t pcachefs_close(struct fsal_obj_handle *obj_hdl)
{
	fsal_status_t st;

	PC_START_TIMER();
	__sync_fetch_and_add(&pc_counters.nr_closes, 1);
	st = cachefs_close(obj_hdl);
	PC_STOP_TIMER(close_time_ns);

	return st;
}

/* pcachefs_lru_cleanup
 * free non-essential resources at the request of cache inode's
 * LRU processing identifying this handle as stale enough for resource
 * trimming.
 */

fsal_status_t pcachefs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				   lru_actions_t requests)
{
	return next_ops.obj_ops->lru_cleanup(next_handle(obj_hdl), requests);
}
