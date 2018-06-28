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
 * 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 *
 */

/* handle.c
 */

#include "config.h"

#include "fsal.h"
#include <libgen.h>		/* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <sys/types.h>
#include "ganesha_list.h"
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "dbgfs_methods.h"
#include <os/subr.h>

/* helpers
 */

/* handle methods
 */

/* lookup
 * deprecated DBG parent && DBG path implies root handle
 */

static fsal_status_t dbgfs_lookup(struct fsal_obj_handle *parent,
			    const char *path, struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "parent: %p, path: %s", parent, path);
	fsal_status_t ret = next_ops.obj_ops->lookup(parent, path, handle);
	LogDebug(COMPONENT_FSAL, "handle: %p", *handle);
	return ret;
}

static fsal_status_t dbgfs_create(struct fsal_obj_handle *dir_hdl,
			    const char *name, struct attrlist *attrib,
			    struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "dir_hdl: %p, name: %s", dir_hdl, name);
	fsal_status_t ret =
	    next_ops.obj_ops->create(dir_hdl, name, attrib, handle);
	LogDebug(COMPONENT_FSAL, "handle: %p", *handle);
	return ret;
}

static fsal_status_t dbgfs_makedir(struct fsal_obj_handle *dir_hdl,
			     const char *name, struct attrlist *attrib,
			     struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "%s", __func__);
	return next_ops.obj_ops->mkdir(dir_hdl, name, attrib, handle);
}

static fsal_status_t dbgfs_makenode(struct fsal_obj_handle *dir_hdl,
			      const char *name, object_file_type_t nodetype,
			      fsal_dev_t *dev,	/* IN */
			      struct attrlist *attrib,
			      struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "%s", __func__);
	return next_ops.obj_ops->mknode(dir_hdl, name, nodetype, dev,
					attrib, handle);
}

/** makesymlink
 *  Note that we do not set mode bits on symlinks for Linux/POSIX
 *  They are not really settable in the kernel and are not checked
 *  anyway (default is 0777) because open uses that target's mode
 */

static fsal_status_t dbgfs_makesymlink(struct fsal_obj_handle *dir_hdl,
				 const char *name, const char *link_path,
				 struct attrlist *attrib,
				 struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "%s", __func__);
	return next_ops.obj_ops->symlink(dir_hdl, name, link_path,
					 attrib, handle);
}

static fsal_status_t dbgfs_readsymlink(struct fsal_obj_handle *obj_hdl,
				 struct gsh_buffdesc *link_content,
				 bool refresh)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->readlink(obj_hdl, link_content,
					  refresh);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

static fsal_status_t dbgfs_linkfile(struct fsal_obj_handle *obj_hdl,
			      struct fsal_obj_handle *destdir_hdl,
			      const char *name)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->link(obj_hdl, destdir_hdl, name);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/**
 * read_dirents
 * read the directory and call through the callback function for
 * each entry.
 * @param dir_hdl [IN] the directory to read
 * @param whence [IN] where to start (next)
 * @param dir_state [IN] pass thru of state to callback
 * @param cb [IN] callback function
 * @param eof [OUT] eof marker true == end of dir
 */

static fsal_status_t dbgfs_read_dirents(struct fsal_obj_handle *dir_hdl,
				  fsal_cookie_t *whence, void *dir_state,
				  fsal_readdir_cb cb, bool *eof)
{
	LogDebug(COMPONENT_FSAL, "%s", __func__);
	return next_ops.obj_ops->readdir(dir_hdl, whence, dir_state, cb,
					 eof);
}

static fsal_status_t dbgfs_renamefile(struct fsal_obj_handle *olddir_hdl,
				const char *old_name,
				struct fsal_obj_handle *newdir_hdl,
				const char *new_name)
{
	LogDebug(
	    COMPONENT_FSAL,
	    "olddir_hdl = %p, old_name = %s, newdir_hdl = %p, new_name = %s",
	    olddir_hdl, old_name, newdir_hdl, new_name);
	return next_ops.obj_ops->rename(olddir_hdl, old_name, newdir_hdl,
					new_name);
}

static fsal_status_t dbgfs_getattrs(struct fsal_obj_handle *obj_hdl)
{
	LogDebug(COMPONENT_FSAL, "obj_hdl: %p", obj_hdl);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->getattrs(obj_hdl);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/*
 * NOTE: this is done under protection of the
 * attributes rwlock in the cache entry.
 */

static fsal_status_t dbgfs_setattrs(struct fsal_obj_handle *obj_hdl,
			      struct attrlist *attrs)
{
	LogDebug(COMPONENT_FSAL, "obj_hdl: %p", obj_hdl);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->setattrs(obj_hdl, attrs);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/* file_unlink
 * unlink the named file in the directory
 */

static fsal_status_t dbgfs_file_unlink(struct fsal_obj_handle *dir_hdl,
				 const char *name)
{
	LogDebug(COMPONENT_FSAL, "dir_hdl: %p, name: %s", dir_hdl, name);
	return next_ops.obj_ops->unlink(dir_hdl, name);
}

/* handle_digest
 * fill in the opaque f/s file handle part.
 * we zero the buffer to length first.  This MAY already be done above
 * at which point, remove memset here because the caller is zeroing
 * the whole struct.
 */

static fsal_status_t dbgfs_handle_digest(const struct fsal_obj_handle *obj_hdl,
				   fsal_digesttype_t output_type,
				   struct gsh_buffdesc *fh_desc)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	fsal_status_t ret =  next_ops.obj_ops->handle_digest(obj_hdl, output_type, fh_desc);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	return ret;
}

/**
 * handle_to_key
 * return a handle descriptor into the handle in this object handle
 * @TODO reminder.  make sure things like hash keys don't point here
 * after the handle is released.
 */

static void dbgfs_handle_to_key(struct fsal_obj_handle *obj_hdl,
			  struct gsh_buffdesc *fh_desc)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	next_ops.obj_ops->handle_to_key(obj_hdl, fh_desc);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
}

/*
 * release
 * release our export first so they know we are gone
 */

static void dbgfs_release(struct fsal_obj_handle *obj_hdl)
{
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
	next_ops.obj_ops->release(obj_hdl);
	LogDebug(COMPONENT_FSAL, "fsid major %lx, minor %lx, file id %lx, file size %lx",
			obj_hdl->attributes.fsid.major,
			obj_hdl->attributes.fsid.minor,
			obj_hdl->attributes.fileid,
			obj_hdl->attributes.filesize);
}

void dbgfs_handle_ops_init(struct fsal_obj_ops *ops)
{
	LogDebug(COMPONENT_FSAL, "%s", __func__);
	ops->release = dbgfs_release;
	ops->lookup = dbgfs_lookup;
	ops->readdir = dbgfs_read_dirents;
	ops->create = dbgfs_create;
	ops->mkdir = dbgfs_makedir;
	ops->mknode = dbgfs_makenode;
	ops->symlink = dbgfs_makesymlink;
	ops->readlink = dbgfs_readsymlink;
	ops->test_access = dbgfs_test_access;
	ops->getattrs = dbgfs_getattrs;
	ops->setattrs = dbgfs_setattrs;
	ops->link = dbgfs_linkfile;
	ops->rename = dbgfs_renamefile;
	ops->unlink = dbgfs_file_unlink;
	ops->open = dbgfs_open;
	ops->status = dbgfs_status;
	ops->read = dbgfs_read;
	ops->write = dbgfs_write;
	ops->commit = dbgfs_commit;
	ops->lock_op = dbgfs_lock_op;
	ops->close = dbgfs_close;
	ops->lru_cleanup = dbgfs_lru_cleanup;
	ops->handle_digest = dbgfs_handle_digest;
	ops->handle_to_key = dbgfs_handle_to_key;

	/* xattr related functions */
	ops->list_ext_attrs = dbgfs_list_ext_attrs;
	ops->getextattr_id_by_name = dbgfs_getextattr_id_by_name;
	ops->getextattr_value_by_name = dbgfs_getextattr_value_by_name;
	ops->getextattr_value_by_id = dbgfs_getextattr_value_by_id;
	ops->setextattr_value = dbgfs_setextattr_value;
	ops->setextattr_value_by_id = dbgfs_setextattr_value_by_id;
	ops->getextattr_attrs = dbgfs_getextattr_attrs;
	ops->remove_extattr_by_id = dbgfs_remove_extattr_by_id;
	ops->remove_extattr_by_name = dbgfs_remove_extattr_by_name;

}

/* export methods that create object handles
 */

/* lookup_path
 * modeled on old api except we don't stuff attributes.
 * KISS
 */

fsal_status_t dbgfs_lookup_path(struct fsal_export *exp_hdl,
				 const char *path,
				 struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "path: %s", path);
	fsal_status_t ret = next_ops.exp_ops->lookup_path(exp_hdl, path, handle);
	LogDebug(COMPONENT_FSAL, "handle: %p", *handle);
	return ret;
}

/* create_handle
 * Does what original FSAL_ExpandHandle did (sort of)
 * returns a ref counted handle to be later used in cache_inode etc.
 * NOTE! you must release this thing when done with it!
 * BEWARE! Thanks to some holes in the *AT syscalls implementation,
 * we cannot get an fd on an AF_UNIX socket, nor reliably on block or
 * character special devices.  Sorry, it just doesn't...
 * we could if we had the handle of the dir it is in, but this method
 * is for getting handles off the wire for cache entries that have LRU'd.
 * Ideas and/or clever hacks are welcome...
 */

fsal_status_t dbgfs_create_handle(struct fsal_export *exp_hdl,
				   struct gsh_buffdesc *hdl_desc,
				   struct fsal_obj_handle **handle)
{
	LogDebug(COMPONENT_FSAL, "%s", __func__);
	return next_ops.exp_ops->create_handle(exp_hdl, hdl_desc,
					       handle);
}
