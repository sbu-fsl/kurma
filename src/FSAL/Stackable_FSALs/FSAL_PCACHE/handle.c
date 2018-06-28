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
/* handle.c
 */

#include "config.h"

#include "fsal.h"
#include <libgen.h> /* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <sys/types.h>
#include "ganesha_list.h"
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "pcachefs_methods.h"
#include <os/subr.h>
#include "cache_handle.h"

/* helpers
 */

/* handle methods
 */

/*
 * deprecated NULL parent && NULL path implies root handle
 */
static fsal_status_t pcachefs_lookup(struct fsal_obj_handle *parent,
				     const char *path,
				     struct fsal_obj_handle **handle)
{
	fsal_status_t st;

	PC_START_TIMER();
	__sync_fetch_and_add(&pc_counters.nr_lookups, 1);
	st = cachefs_lookup(parent, path, handle);
	PC_STOP_TIMER(lookup_time_ns);

	return st;
}

static fsal_status_t pcachefs_create(struct fsal_obj_handle *dir_hdl,
				     const char *name, struct attrlist *attrib,
				     struct fsal_obj_handle **handle)
{
	fsal_status_t st;

	PC_START_TIMER();
	__sync_fetch_and_add(&pc_counters.nr_creates, 1);
	st = cachefs_create(dir_hdl, name, attrib, handle);
	PC_STOP_TIMER(create_time_ns);

	return st;
}

static fsal_status_t makedir(struct fsal_obj_handle *dir_hdl, const char *name,
			     struct attrlist *attrib,
			     struct fsal_obj_handle **handle)
{
	__sync_fetch_and_add(&pc_counters.nr_mkdirs, 1);
	return cachefs_makedir(dir_hdl, name, attrib, handle);
}

static fsal_status_t makenode(struct fsal_obj_handle *dir_hdl, const char *name,
			      object_file_type_t nodetype,
			      fsal_dev_t *dev, /* IN */
			      struct attrlist *attrib,
			      struct fsal_obj_handle **handle)
{
	return cachefs_makenode(dir_hdl, name, nodetype, dev, attrib, handle);
}

/** makesymlink
 *  Note that we do not set mode bits on symlinks for Linux/POSIX
 *  They are not really settable in the kernel and are not checked
 *  anyway (default is 0777) because open uses that target's mode
 */

static fsal_status_t makesymlink(struct fsal_obj_handle *dir_hdl,
				 const char *name, const char *link_path,
				 struct attrlist *attrib,
				 struct fsal_obj_handle **handle)
{
	return cachefs_symlink(dir_hdl, name, link_path, attrib, handle);
}

static fsal_status_t readsymlink(struct fsal_obj_handle *obj_hdl,
				 struct gsh_buffdesc *link_content,
				 bool refresh)
{
	return next_ops.obj_ops->readlink(next_handle(obj_hdl), link_content,
					  refresh);
}

static fsal_status_t pcachefs_link(struct fsal_obj_handle *obj_hdl,
				   struct fsal_obj_handle *destdir_hdl,
				   const char *name)
{
	return next_ops.obj_ops->link(next_handle(obj_hdl),
				      next_handle(destdir_hdl), name);
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

static fsal_status_t read_dirents(struct fsal_obj_handle *dir_hdl,
				  fsal_cookie_t *whence, void *dir_state,
				  fsal_readdir_cb cb, bool *eof)
{
	__sync_fetch_and_add(&pc_counters.nr_readdirs, 1);
	return next_ops.obj_ops->readdir(next_handle(dir_hdl), whence,
					 dir_state, cb, eof);
}

static fsal_status_t pcachefs_rename(struct fsal_obj_handle *olddir_hdl,
				     const char *old_name,
				     struct fsal_obj_handle *newdir_hdl,
				     const char *new_name)
{
	__sync_fetch_and_add(&pc_counters.nr_renames, 1);
	return next_ops.obj_ops->rename(next_handle(olddir_hdl), old_name,
					next_handle(newdir_hdl), new_name);
}

static fsal_status_t pcachefs_getattrs(struct fsal_obj_handle *obj_hdl)
{
	fsal_status_t st;

	PC_START_TIMER();
	__sync_fetch_and_add(&pc_counters.nr_getattrs, 1);
	st = cachefs_getattrs(obj_hdl);
	PC_STOP_TIMER(getattr_time_ns);

	return st;
}

/*
 * NOTE: this is done under protection of the
 * attributes rwlock in the cache entry.
 */

static fsal_status_t pcachefs_setattrs(struct fsal_obj_handle *obj_hdl,
				       struct attrlist *attrs)
{
	__sync_fetch_and_add(&pc_counters.nr_setattrs, 1);
	return cachefs_setattrs(obj_hdl, attrs);
}

/*
 * unlink the named file in the directory
 */
static fsal_status_t pcachefs_unlink(struct fsal_obj_handle *dir_hdl,
				 const char *name)
{
	fsal_status_t st;

	PC_START_TIMER();
	__sync_fetch_and_add(&pc_counters.nr_unlinks, 1);
	st = cachefs_file_unlink(dir_hdl, name);
	PC_STOP_TIMER(unlink_time_ns);

	return st;
}

/* handle_digest
 * fill in the opaque f/s file handle part.
 * we zero the buffer to length first.  This MAY already be done above
 * at which point, remove memset here because the caller is zeroing
 * the whole struct.
 */

static fsal_status_t handle_digest(const struct fsal_obj_handle *obj_hdl,
				   fsal_digesttype_t output_type,
				   struct gsh_buffdesc *fh_desc)
{
	struct fsal_obj_handle *hdl = (struct fsal_obj_handle *)obj_hdl;
	return next_ops.obj_ops->handle_digest(next_handle(hdl), output_type,
					       fh_desc);
}

/**
 * handle_to_key
 * return a handle descriptor into the handle in this object handle
 * @TODO reminder.  make sure things like hash keys don't point here
 * after the handle is released.
 */

static void handle_to_key(struct fsal_obj_handle *obj_hdl,
			  struct gsh_buffdesc *fh_desc)
{
	return next_ops.obj_ops->handle_to_key(next_handle(obj_hdl), fh_desc);
}

/*
 * release
 * release our export first so they know we are gone
 */

static void release(struct fsal_obj_handle *obj_hdl)
{
	cachefs_release(obj_hdl);
}

void pcachefs_handle_ops_init(struct fsal_obj_ops *ops)
{
	ops->release = release;
	ops->lookup = pcachefs_lookup;
	ops->readdir = read_dirents;
	ops->create = pcachefs_create;
	ops->mkdir = makedir;
	ops->mknode = makenode;
	ops->symlink = makesymlink;
	ops->readlink = readsymlink;
	ops->test_access = fsal_test_access;
	ops->getattrs = pcachefs_getattrs;
	ops->setattrs = pcachefs_setattrs;
	ops->link = pcachefs_link;
	ops->rename = pcachefs_rename;
	ops->unlink = pcachefs_unlink;
	ops->open = pcachefs_open;
	ops->status = pcachefs_status;
	ops->read = pcachefs_read;
	ops->write = pcachefs_write;
	ops->commit = pcachefs_commit;
	ops->lock_op = pcachefs_lock_op;
	ops->close = pcachefs_close;
	ops->lru_cleanup = pcachefs_lru_cleanup;
	ops->handle_digest = handle_digest;
	ops->handle_to_key = handle_to_key;

	/* xattr related functions */
	ops->list_ext_attrs = pcachefs_list_ext_attrs;
	ops->getextattr_id_by_name = pcachefs_getextattr_id_by_name;
	ops->getextattr_value_by_name = pcachefs_getextattr_value_by_name;
	ops->getextattr_value_by_id = pcachefs_getextattr_value_by_id;
	ops->setextattr_value = pcachefs_setextattr_value;
	ops->setextattr_value_by_id = pcachefs_setextattr_value_by_id;
	ops->getextattr_attrs = pcachefs_getextattr_attrs;
	ops->remove_extattr_by_id = pcachefs_remove_extattr_by_id;
	ops->remove_extattr_by_name = pcachefs_remove_extattr_by_name;
}

/* export methods that create object handles
 */

/* lookup_path
 * modeled on old api except we don't stuff attributes.
 * KISS
 */

fsal_status_t pcachefs_lookup_path(struct fsal_export *exp_hdl,
				   const char *path,
				   struct fsal_obj_handle **handle)
{
	return cachefs_lookup_path(exp_hdl, path, handle);
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

fsal_status_t pcachefs_create_handle(struct fsal_export *exp_hdl,
				     struct gsh_buffdesc *hdl_desc,
				     struct fsal_obj_handle **handle)
{
	return cachefs_create_handle(exp_hdl, hdl_desc, handle);
}
