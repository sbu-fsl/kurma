/*
 * vim:noexpandtab:shiftwidth=8:tabstop=8:
 *
 * Copyright (C) Panasas Inc., 2011
 * Author: Jim Lieb jlieb@panasas.com
 *
 * contributeur : Philippe DENIEL   philippe.deniel@cea.fr
 *                Thomas LEIBOVICI  thomas.leibovici@cea.fr
 *
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

/* file.c
 * File I/O methods for DBG module
 */

#include "config.h"

#include <assert.h>
#include "fsal.h"
#include "FSAL/access_check.h"
#include "fsal_convert.h"
#include <unistd.h>
#include <fcntl.h>
#include "FSAL/fsal_commonlib.h"
#include "dbgfs_methods.h"


/** dbgfs_open
 * called with appropriate locks taken at the cache inode level
 */

fsal_status_t dbgfs_open(struct fsal_obj_handle *obj_hdl,
			  fsal_openflags_t openflags)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->open(obj_hdl, openflags);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_status
 * Let the caller peek into the file's open/close state.
 */

fsal_openflags_t dbgfs_status(struct fsal_obj_handle *obj_hdl)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_openflags_t ret =  next_ops.obj_ops->status(obj_hdl);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_read
 * concurrency (locks) is managed in cache_inode_*
 */

fsal_status_t dbgfs_read(struct fsal_obj_handle *obj_hdl,
			  uint64_t offset,
			  size_t buffer_size, void *buffer,
			  size_t *read_amount,
			  bool *end_of_file)
{
	LogDebug(COMPONENT_FSAL,
		 "obj_hdl %p, offset %lu, buffer_size %lu, buffer %p", obj_hdl,
		 offset, buffer_size, buffer);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->read(obj_hdl, offset, buffer_size,
				      buffer, read_amount, end_of_file);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_write
 * concurrency (locks) is managed in cache_inode_*
 */

fsal_status_t dbgfs_write(struct fsal_obj_handle *obj_hdl,
			   uint64_t offset,
			   size_t buffer_size, void *buffer,
			   size_t *write_amount, bool *fsal_stable)
{
	LogDebug(COMPONENT_FSAL,
		 "obj_hdl %p, offset %lu, buffer_size %lu, buffer %p", obj_hdl,
		 offset, buffer_size, buffer);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->write(obj_hdl, offset, buffer_size,
				       buffer, write_amount, fsal_stable);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_commit
 * Commit a file range to storage.
 * for right now, fsync will have to do.
 */

fsal_status_t dbgfs_commit(struct fsal_obj_handle *obj_hdl,	/* sync */
			    off_t offset, size_t len)
{
	LogDebug(COMPONENT_FSAL, "obj_hdl %p, offset %lu, len %lu", obj_hdl,
		 offset, len);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->commit(obj_hdl, offset, len);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_lock_op
 * lock a region of the file
 * throw an error if the fd is not open.  The old fsal didn't
 * check this.
 */

fsal_status_t dbgfs_lock_op(struct fsal_obj_handle *obj_hdl,
			     void *p_owner,
			     fsal_lock_op_t lock_op,
			     fsal_lock_param_t *request_lock,
			     fsal_lock_param_t *conflicting_lock)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->lock_op(obj_hdl, p_owner, lock_op,
					 request_lock, conflicting_lock);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_close
 * Close the file if it is still open.
 * Yes, we ignor lock status.  Closing a file in POSIX
 * releases all locks but that is state and cache inode's problem.
 */

fsal_status_t dbgfs_close(struct fsal_obj_handle *obj_hdl)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->close(obj_hdl);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* dbgfs_lru_cleanup
 * free non-essential resources at the request of cache inode's
 * LRU processing identifying this handle as stale enough for resource
 * trimming.
 */

fsal_status_t dbgfs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				 lru_actions_t requests)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->lru_cleanup(obj_hdl, requests);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

fsal_status_t dbgfs_test_access(struct fsal_obj_handle *obj_hdl,
			       fsal_accessflags_t access_type,
			       fsal_accessflags_t *allowed,
			       fsal_accessflags_t *denied)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->test_access(obj_hdl, access_type, allowed, denied);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}
