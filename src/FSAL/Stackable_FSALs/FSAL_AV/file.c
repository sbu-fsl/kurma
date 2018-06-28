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
 * File I/O methods for AV module
 */

#include "config.h"

#include <assert.h>
#include "fsal.h"
#include "FSAL/access_check.h"
#include "fsal_convert.h"
#include <unistd.h>
#include <fcntl.h>
#include "FSAL/fsal_commonlib.h"
#include "avfs_methods.h"

#include "antivirus.h"
#include "temp_file_helper.h"

/** avfs_open
 * called with appropriate locks taken at the cache inode level
 */

fsal_status_t avfs_open(struct fsal_obj_handle *obj_hdl,
			  fsal_openflags_t openflags)
{
	fsal_status_t open_ret = next_ops.obj_ops->open(obj_hdl, openflags);
	if(FSAL_IS_ERROR(open_ret)) {
		return open_ret;
	}

	// read file into buffer
	uint64_t filesize = obj_hdl->attributes.filesize;
	void *av_buff = gsh_malloc((size_t)filesize);
	if(av_buff == NULL) {
		return fsalstat(ERR_FSAL_NOMEM, ENOMEM);
	}
	size_t av_read_amount;
	size_t av_read_size = (size_t)filesize;
	void *av_read_buff = av_buff;
	bool av_end_of_file = false;
	while(av_read_size > 0 && !av_end_of_file) {
		fsal_status_t ret = next_ops.obj_ops->read(obj_hdl, 0, av_read_size,
						av_read_buff, &av_read_amount, &av_end_of_file);
		if(FSAL_IS_ERROR(ret)) {
			open_ret = fsalstat(ERR_FSAL_ACCESS, 0);
			goto error_buffer;
		}
		av_read_size -= av_read_amount;
		av_read_buff += av_read_amount;
	}

	// write to temp file
	struct temp_file_t obj;
	if(-1 == temp_file_init(&obj, obj_hdl)) {
		open_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_buffer;
	}
	if(-1 == temp_file_create_new(&obj)) {
		open_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	if(filesize != temp_file_write(&obj, av_buff, (size_t)filesize)
			|| obj._error) {
		open_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}

	// check virus
	const char *virus_name;
	av_status_t res = av_scan(obj._filename, &virus_name);
	if(res == AV_VIRUS) {
		LogCrit(COMPONENT_FSAL,
			"Virus %s detected in file with handle %x",
			virus_name, obj_hdl);
	}
	if(res == AV_VIRUS || res == AV_ERROR) {
		if(FSAL_IS_ERROR(next_ops.obj_ops->close(obj_hdl))) {
			LogCrit(COMPONENT_FSAL, "close failed!");
		}
		open_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}

error_temp_file:
	temp_file_destroy(&obj);
error_buffer:
	gsh_free(av_buff);
	return open_ret;
}

/* avfs_status
 * Let the caller peek into the file's open/close state.
 */

fsal_openflags_t avfs_status(struct fsal_obj_handle *obj_hdl)
{
	return next_ops.obj_ops->status(obj_hdl);
}

/* avfs_read
 * concurrency (locks) is managed in cache_inode_*
 */

fsal_status_t avfs_read(struct fsal_obj_handle *obj_hdl,
			  uint64_t offset,
			  size_t buffer_size, void *buffer,
			  size_t *read_amount,
			  bool *end_of_file)
{
	fsal_status_t read_ret;
	if (!obj_hdl || !read_amount || !end_of_file)
		return fsalstat(ERR_FSAL_FAULT, EINVAL);

	if (!buffer_size) {
		*read_amount = 0;
		*end_of_file = false;
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
	}

	// read from temp file
	struct temp_file_t obj;
	if(-1 == temp_file_init(&obj, obj_hdl)) {
		return fsalstat(ERR_FSAL_ACCESS, 0);
	}
	if(-1 == temp_file_exists_open(&obj)) {
		read_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error;
	}
	*read_amount = temp_file_read(&obj, buffer, buffer_size);
	if(obj._error) {
		read_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error;
	}
	*end_of_file = obj._eof;

	read_ret = fsalstat(ERR_FSAL_NO_ERROR, 0);
error:
	temp_file_destroy(&obj);
	return read_ret;
}

/* avfs_write
 * concurrency (locks) is managed in cache_inode_*
 */

fsal_status_t avfs_write(struct fsal_obj_handle *obj_hdl,
			   uint64_t offset,
			   size_t buffer_size, void *buffer,
			   size_t *write_amount, bool *fsal_stable)
{
	fsal_status_t write_ret = fsalstat(ERR_FSAL_NO_ERROR, 0);
	if (!obj_hdl || !write_amount)
		return fsalstat(ERR_FSAL_FAULT, EINVAL);

	if (!buffer_size) {
		*write_amount = 0;
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
	}

	struct temp_file_t obj;
	if(-1 == temp_file_init(&obj, obj_hdl)) {
		return fsalstat(ERR_FSAL_ACCESS, 0);
	}
	if(-1 == temp_file_exists_open(&obj)) {
		write_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error;
	}
	if(-1 == temp_file_seek(&obj, offset, SEEK_SET)) {
		write_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error;
	}
	*write_amount = temp_file_write(&obj, buffer, buffer_size);
	if(obj._error) {
		write_ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error;
	}
	*fsal_stable = false;
error:
	temp_file_destroy(&obj);
	return write_ret;
}

/* avfs_commit
 * Commit a file range to storage.
 * for right now, fsync will have to do.
 */

fsal_status_t avfs_commit(struct fsal_obj_handle *obj_hdl,	/* sync */
			    off_t offset, size_t len)
{
	fsal_status_t ret = fsalstat(ERR_FSAL_NO_ERROR, 0);
	struct temp_file_t obj;
	if(-1 == temp_file_init(&obj, obj_hdl)) {
		return fsalstat(ERR_FSAL_ACCESS, 0);
	}
	if(-1 == temp_file_exists(&obj)) {
		ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	// check virus
	const char *virus_name;
	av_status_t res = av_scan(obj._filename, &virus_name);
	if(res == AV_VIRUS) {
		LogCrit(COMPONENT_FSAL,
			"Virus %s detected in file with handle %x",
			virus_name, obj_hdl);
		ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	if(res == AV_ERROR) {
		ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	// read file into buffer
	if(-1 == temp_file_exists_open(&obj)) {
		ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	uint64_t filesize = temp_file_size(&obj);
	if(filesize < offset + len) {
		LogCrit(COMPONENT_FSAL, "filesize %lx. offset %lx, len %lx",
				filesize, offset, len);
		ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	void *av_buff = gsh_malloc(len);
	if(av_buff == NULL) {
		LogCrit(COMPONENT_FSAL, "Out of memory");
		ret = fsalstat(ERR_FSAL_ACCESS, 0);
		goto error_temp_file;
	}
	temp_file_seek(&obj, offset, SEEK_SET);
	temp_file_read(&obj, av_buff, len);
	if(obj._error) {
		LogCrit(COMPONENT_FSAL, "File read failed with error %d", obj._error);
	}

	// TODO: The idea of AVFS using it's own user credentials instead of clients' is under
	// discussion.
	size_t write_amount;
	bool fsal_stable;
	size_t bytes_written = 0;
	size_t bytes_left = len;
	off_t cur_off = offset;
	struct req_op_context avfs_req_ctx;
	memset(&avfs_req_ctx, 0, sizeof(struct req_op_context));
	avfs_req_ctx.creds = &avfs_user;
	while(bytes_left) {
		fsal_status_t wr_ret = next_ops.obj_ops->write(obj_hdl, cur_off, bytes_left,
				       av_buff, &write_amount, &fsal_stable);
		if(FSAL_IS_ERROR(wr_ret)) {
			LogCrit(COMPONENT_FSAL, "Write failed");
			ret = fsalstat(ERR_FSAL_ACCESS, 0);
			goto error_buffer;
		}
		if(!fsal_stable) {
			fsal_status_t cmt_ret = next_ops.obj_ops->commit(obj_hdl, cur_off, write_amount);
			if(FSAL_IS_ERROR(cmt_ret)) {
				LogCrit(COMPONENT_FSAL, "Commit failed");
				ret = cmt_ret;
				goto error_buffer;
			}
		}
		bytes_written += write_amount;
		bytes_left -= write_amount;
		cur_off += write_amount;
	}
error_buffer:
	gsh_free(av_buff);
error_temp_file:
	temp_file_destroy(&obj);
	return ret;
}

/* avfs_lock_op
 * lock a region of the file
 * throw an error if the fd is not open.  The old fsal didn't
 * check this.
 */

fsal_status_t avfs_lock_op(struct fsal_obj_handle *obj_hdl,
			     void *p_owner,
			     fsal_lock_op_t lock_op,
			     fsal_lock_param_t *request_lock,
			     fsal_lock_param_t *conflicting_lock)
{
	return next_ops.obj_ops->lock_op(obj_hdl, p_owner, lock_op,
					 request_lock, conflicting_lock);
}

/* avfs_close
 * Close the file if it is still open.
 * Yes, we ignor lock status.  Closing a file in POSIX
 * releases all locks but that is state and cache inode's problem.
 */

fsal_status_t avfs_close(struct fsal_obj_handle *obj_hdl)
{
	return next_ops.obj_ops->close(obj_hdl);
}

/* avfs_lru_cleanup
 * free non-essential resources at the request of cache inode's
 * LRU processing identifying this handle as stale enough for resource
 * trimming.
 */

fsal_status_t avfs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				 lru_actions_t requests)
{
	return next_ops.obj_ops->lru_cleanup(obj_hdl, requests);
}
