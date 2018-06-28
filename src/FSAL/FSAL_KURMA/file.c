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

/* file.c
 * File I/O methods for KURMA module
 */

#include "config.h"

#include <assert.h>
#include <fcntl.h>
#include <unistd.h>

#include "cache_inode.h"
#include "fsal.h"
#include "FSAL/access_check.h"
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "kurma_methods.h"
#include "kurma.h"
#include "perf_stats.h"

/**
 * Invalidate the cached inode entry if the file is found to have been changed
 * by remote middlewares.
static void invalidate_file_if_changed(struct fsal_obj_handle *hdl,
				       const struct timespec *chgtime)
{
	struct gsh_buffdesc wire_fh;

	if (gsh_time_cmp(chgtime, &hdl->attributes.chgtime) > 0) {
		hdl->ops->handle_to_key(hdl, &wire_fh);
		// We don't need to check the return value because it only
		// fails when the cache inode of the specified file is not
		// found.  In that case, this is nothing to invalidate.
		fsal_invalidate(hdl->fsal, &wire_fh,
				CACHE_INODE_INVALIDATE_ATTRS |
				CACHE_INODE_INVALIDATE_CONTENT);
	}
}
 */

/**
 * called with appropriate locks taken at the cache inode level
 */
fsal_status_t kurma_open(struct fsal_obj_handle *obj_hdl,
			 fsal_openflags_t openflags)
{
	struct kurma_fsal_obj_handle *khdl;
	kurma_st st;
	struct timespec chgtime;  /* time of latest remote change */
	struct attrlist attrs = {0};
	PERF_DECLARE_COUNTER(kopen);

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("OPEN hdl=%p; name=%s", khdl, khdl->name);

	if (is_snapshot_obj(khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	PERF_START_COUNTER(kopen);
	st = rpc_open(khdl->oid, openflags, &chgtime, &attrs);
	if (st == KURMA_OKAY) {
		khdl->openflags = openflags;
		kurma_try_update_attributes(khdl, &attrs);
	} else {
		KURMA_I("fail to open '%s'", khdl->name);
	}

	// TODO remove this
	pthread_rwlock_wrlock(&obj_hdl->lock);
	obj_hdl->attributes.chgtime = chgtime;
	pthread_rwlock_unlock(&obj_hdl->lock);

	PERF_STOP_COUNTER(kopen, 1, st == KURMA_OKAY);
	return kurma_to_fsal_status(st);
}

/* kurma_status
 * Let the caller peek into the file's open/close state.
 */

fsal_openflags_t kurma_status(struct fsal_obj_handle *obj_hdl)
{
	struct kurma_fsal_obj_handle *khdl;
	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	return khdl->openflags;
}

/* kurma_read
 * concurrency (locks) is managed in cache_inode_*
 */

fsal_status_t kurma_read(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			 size_t buffer_size, void *buffer, size_t *read_amount,
			 bool *end_of_file)
{
	struct kurma_fsal_obj_handle *khdl;
	kurma_st st;
	bool align;
	uint64_t offset_align;
	uint64_t offset_moved;
	size_t size_align;
	void *buffer_align;
	uint32_t block_shift = 0;
	uint64_t block_size;
	struct attrlist attrs = {0};
	PERF_DECLARE_COUNTER(kread);

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);

	KURMA_D("READ %zu (%zu) hdl=%p; name=%s", offset, buffer_size, khdl,
		khdl->name);

	if (is_snapshot_obj(khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	/* skip unnecessary read */
	if (offset >= get_filesize(khdl)) {
		*read_amount = 0;
		*end_of_file = true;
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
	}
	PERF_START_COUNTER(kread);

	if (khdl->block_shift) {
		block_shift = khdl->block_shift;
        } else {
		// TODO: File hole? ftruncate on a file without write
		KURMA_ERR("fail to fetch block_shift value from "
			  "kurma_fsal_obj_handle, exiting");
		goto out;
        }
	block_size = 1<<(block_shift);
	/* prepare aligned buffer */
	offset_align = round_down(offset, block_size);
	offset_moved = offset - offset_align;
	size_align = round_up(offset + buffer_size, block_size) - offset_align;
	align = (offset == offset_align && buffer_size == size_align);
	/* allocate buffer if non-aligned */
	buffer_align = align ? buffer : gsh_malloc(size_align);
	if (!buffer_align)
		return fsalstat(ERR_FSAL_NOMEM, 0);

	KURMA_D("aligned read %zu (%zu); hdl=%p", offset_align, size_align,
		khdl);

	st = rpc_read(khdl->oid, offset_align, size_align, buffer_align,
		      read_amount, end_of_file, block_shift, &attrs);
	if (st != KURMA_OKAY) {
		KURMA_I("fail to read; hdl=%p", khdl);
		goto out;
	}
	kurma_try_update_attributes(khdl, &attrs);

	if (offset_align + *read_amount <= offset) {
		/* read_amount may not be aligned because it is last block */
		*read_amount = 0;
		assert(*end_of_file);
	} else if (offset_align + *read_amount <= offset + buffer_size) {
		*read_amount = *read_amount - offset_moved;
	} else { /* > offset + buffer_size */
		*read_amount = buffer_size;
		*end_of_file = false;
	}

	if (!align)
		memcpy(buffer, buffer_align + offset_moved, *read_amount);

	KURMA_D("filesize=%zu; read_amount=%zu; eof=%d; hdl=%p",
		get_filesize(khdl), *read_amount, *end_of_file, khdl);

out:
	if (!align)
		gsh_free(buffer_align);

	PERF_STOP_COUNTER(kread, buffer_size, st == ERR_FSAL_NO_ERROR);
	return kurma_to_fsal_status(st);
}

/* kurma_write
 * concurrency (locks) is managed in cache_inode_*
 */
fsal_status_t kurma_write(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			  size_t buffer_size, void *buffer,
			  size_t *write_amount, bool *fsal_stable)
{
	struct kurma_fsal_obj_handle *khdl;
	kurma_st st;
	uint64_t offset_align;
	uint64_t offset_moved;
	uint64_t size_align;
	void *buffer_align;
	bool align;
	uint64_t block_size;
	uint32_t block_shift = 0;
	struct attrlist attrs = {0};
	PERF_DECLARE_COUNTER(kwrite);

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);

	KURMA_D("WRITE %zu (%zu) hdl=%p; name=%s", offset, buffer_size, khdl,
		khdl->name);

	if (is_snapshot_obj(khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	if (buffer_size == 0) {
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
        }
	PERF_START_COUNTER(kwrite);

	if (khdl->block_shift) {
		block_shift = khdl->block_shift;
        } else {
		/* Initial block shift value calculation */
		if (buffer_size <= (1<<16)) {
			block_shift = 16;
                } else if (buffer_size > (1<<20)) {
			block_shift = 20;
		} else {
                        for (block_shift = 0; (1 << block_shift) < buffer_size; ++block_shift) ;
		}
		KURMA_D("Initially calculated block shift value: %d",
			block_shift);
	}
	block_size = 1<<block_shift;

	/* prepare aligned buffer */
	offset_align = round_down(offset, block_size);
	offset_moved = offset - offset_align;
	size_align = round_up(offset + buffer_size, block_size) - offset_align;
	align = (offset == offset_align && buffer_size == size_align);
	/* allocate buffer if non-aligned */
	buffer_align = align ? buffer : gsh_malloc(size_align);
	if (!buffer_align) {
		return fsalstat(ERR_FSAL_NOMEM, 0);
        }

	if (!align) {
		uint64_t blk_cnt = get_block_count(size_align, block_shift);
		size_t read_amount;
		bool eof = false;

		/* prepare first block */
		st = rpc_read(khdl->oid, offset_align, block_size, buffer_align,
			      &read_amount, &eof, block_shift, NULL);
		if (st != KURMA_OKAY) {
			KURMA_I("fail to read; hdl=%p; offset_align: %llu",
				khdl, offset_align);
			goto out;
		}

		if (read_amount < offset_moved) {
			assert(eof);
			/* fill the gap (hole) with zero */
			memset(buffer_align + read_amount, 0,
			       offset_moved - read_amount);
		}
		memcpy(buffer_align + offset_moved, buffer,
		       blk_cnt == 1 ? buffer_size : block_size - offset_moved);

		if (blk_cnt == 1) {
			size_align =
			    max(read_amount, offset_moved + buffer_size);
		} else {
			/* prepare last block */
			uint64_t tail_offset = (blk_cnt - 1) * block_size;
			if (!eof) {
				st = rpc_read(
				    khdl->oid, offset_align + tail_offset,
				    block_size, buffer_align + tail_offset,
				    &read_amount, &eof, block_shift, NULL);
				if (st != KURMA_OKAY) {
					KURMA_I("fail to read; hdl=%p", khdl);
					goto out;
				}
				assert(eof ||
				       (!eof && read_amount == block_size));
				/* the size of last block can be non-aligned */
				size_align = max(tail_offset + read_amount,
						 offset_moved + buffer_size);
			} else {
				size_align = offset_moved + buffer_size;
			}
			memcpy(buffer_align + tail_offset,
			       buffer - offset_moved + tail_offset,
			       offset_moved + buffer_size - tail_offset);

			/* prepare intermediate blocks */
			if (blk_cnt > 2) {
				memcpy(buffer_align + block_size,
				       buffer - offset_moved + block_size,
				       (blk_cnt - 2) * block_size);
			}
		}
	}

	KURMA_D("aligned write %zu (%zu); hdl=%p", offset_align, size_align,
		khdl);

	st = rpc_write(khdl->oid, offset_align, size_align, buffer_align,
		       write_amount, &block_shift, &attrs);
	if (st == KURMA_OKAY) {
		kurma_try_update_attributes(khdl, &attrs);
		*fsal_stable = true; // TODO define stable

		/* Assign block_shift */
		if (khdl->block_shift) {
			/* If present, cross check with the obtained attrs */
			if (khdl->block_shift != block_shift) {
				KURMA_ERR("fail: BlockShift value miss match");
                        }
		}
		else {
			/* Assign it to the obtained block_shift from kurma
			 * server*/
			khdl->block_shift = block_shift;
		}
	} else {
		KURMA_I("fail to write, hdl=%p", khdl);
	}

	/* TODO: Adjusting the write_amount*/
	if (!align) {
		if (offset_align + *write_amount <= offset) {
			/* write_amount may not be aligned because it is last block */
			*write_amount = 0;
		} else if (offset_align + *write_amount <= offset + buffer_size) {
			*write_amount = *write_amount - offset_moved;
		} else { /* > offset + buffer_size */
			*write_amount = buffer_size;
		}
	}
out:
	if (!align)
		gsh_free(buffer_align);

	PERF_STOP_COUNTER(kwrite, buffer_size, st == ERR_FSAL_NO_ERROR);
	return kurma_to_fsal_status(st);
}

/* kurma_commit
 * Commit a file range to storage.
 * for right now, fsync will have to do.
 */

fsal_status_t kurma_commit(struct fsal_obj_handle *obj_hdl, /* sync */
			   off_t offset, size_t len)
{
	/* KURMA doesn't support non-directory inodes */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

/* kurma_lock_op
 * lock a region of the file
 * throw an error if the fd is not open.  The old fsal didn't
 * check this.
 */

fsal_status_t kurma_lock_op(struct fsal_obj_handle *obj_hdl, void *p_owner,
			    fsal_lock_op_t lock_op,
			    fsal_lock_param_t *request_lock,
			    fsal_lock_param_t *conflicting_lock)
{
	/* KURMA doesn't support non-directory inodes */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

/* kurma_close
 * Close the file if it is still open.
 * Yes, we ignor lock status.  Closing a file in POSIX
 * releases all locks but that is state and cache inode's problem.
 */

fsal_status_t kurma_close(struct fsal_obj_handle *obj_hdl)
{
	struct kurma_fsal_obj_handle *khdl;
	kurma_st st;
	struct attrlist attrs = {0};
	PERF_DECLARE_COUNTER(kclose);

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("CLOSE hdl=%p; name=%s", khdl, khdl->name);

	if (is_snapshot_obj(khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	PERF_START_COUNTER(kclose);
	st = rpc_close(khdl->oid, &attrs);
	if (st == KURMA_OKAY) {
		khdl->openflags = FSAL_O_CLOSED;
		kurma_try_update_attributes(khdl, &attrs);
	} else {
		KURMA_I("fail to close '%s'", khdl->name);
	}

	PERF_STOP_COUNTER(kclose, 1, st == KURMA_OKAY);
	return kurma_to_fsal_status(st);
}

/* kurma_lru_cleanup
 * free non-essential resources at the request of cache inode's
 * LRU processing identifying this handle as stale enough for resource
 * trimming.
 */

fsal_status_t kurma_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				lru_actions_t requests)
{
	/* Unused, but also nothing to do. */
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}
