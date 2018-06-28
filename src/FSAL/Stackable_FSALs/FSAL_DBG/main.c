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
 *
 */

/* main.c
 * Module core functions
 */

#include "config.h"

#include "fsal.h"
#include <libgen.h>		/* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <limits.h>
#include <sys/types.h>
#include "ganesha_list.h"
#include "FSAL/fsal_init.h"
#include "dbgfs_methods.h"

/* DBGFS FSAL module private storage
 */

/* defined the set of attributes supported with POSIX */
#define DBGFS_SUPPORTED_ATTRIBUTES (                    \
	ATTR_TYPE     | ATTR_SIZE     |                  \
	ATTR_FSID     | ATTR_FILEID   |                  \
	ATTR_MODE     | ATTR_NUMLINKS | ATTR_OWNER     | \
	ATTR_GROUP    | ATTR_ATIME    | ATTR_RAWDEV    | \
	ATTR_CTIME    | ATTR_MTIME    | ATTR_SPACEUSED | \
	ATTR_CHGTIME)

struct dbgfs_fsal_module {
	struct fsal_module fsal;
	struct fsal_staticfsinfo_t fs_info;
	/* dbgfsfs_specific_initinfo_t specific_info;  placeholder */
};

const char myname[] = "DBG";

/* filesystem info for DBGFS */
static struct fsal_staticfsinfo_t default_posix_info = {
	.maxfilesize = UINT64_MAX,
	.maxlink = _POSIX_LINK_MAX,
	.maxnamelen = 1024,
	.maxpathlen = 1024,
	.no_trunc = true,
	.chown_restricted = true,
	.case_insensitive = false,
	.case_preserving = true,
	.link_support = true,
	.symlink_support = true,
	.lock_support = true,
	.lock_support_owner = false,
	.lock_support_async_block = false,
	.named_attr = true,
	.unique_handles = true,
	.lease_time = {10, 0},
	.acl_support = FSAL_ACLSUPPORT_ALLOW,
	.cansettime = true,
	.homogenous = true,
	.supported_attrs = DBGFS_SUPPORTED_ATTRIBUTES,
	.maxread = FSAL_MAXIOSIZE,
	.maxwrite = FSAL_MAXIOSIZE,
	.umask = 0,
	.auth_exportpath_xdev = false,
	.xattr_access_rights = 0400,	/* root=RW, owner=R */
};

/* private helper for export object
 */

struct fsal_staticfsinfo_t *dbgfs_staticinfo(struct fsal_module *hdl)
{
	struct dbgfs_fsal_module *myself;

	myself = container_of(hdl, struct dbgfs_fsal_module, fsal);
	return &myself->fs_info;
}

/* Module methods
 */

/* init_config
 * must be called with a reference taken (via lookup_fsal)
 */

static fsal_status_t init_config(struct fsal_module *fsal_hdl,
				 config_file_t config_struct)
{
	struct dbgfs_fsal_module *dbgfs_me =
	    container_of(fsal_hdl, struct dbgfs_fsal_module, fsal);

	/* get a copy of the defaults */
	dbgfs_me->fs_info = default_posix_info;

	/* Configuration setting options:
	 * 1. there are none that are changable. (this case)
	 *
	 * 2. we set some here.  These must be independent of whatever
	 *    may be set by lower level fsals.
	 *
	 * If there is any filtering or change of parameters in the stack,
	 * this must be done in export data structures, not fsal params because
	 * a stackable could be configured above multiple fsals for multiple
	 * diverse exports.
	 */

	display_fsinfo(&dbgfs_me->fs_info);
	LogFullDebug(COMPONENT_FSAL,
		     "Supported attributes constant = 0x%" PRIx64,
		     (uint64_t) DBGFS_SUPPORTED_ATTRIBUTES);
	LogFullDebug(COMPONENT_FSAL,
		     "Supported attributes default = 0x%" PRIx64,
		     default_posix_info.supported_attrs);
	LogDebug(COMPONENT_FSAL,
		 "FSAL INIT: Supported attributes mask = 0x%" PRIx64,
		 dbgfs_me->fs_info.supported_attrs);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/* Internal DBGFS method linkage to export object
 */

fsal_status_t dbgfs_create_export(struct fsal_module *fsal_hdl,
				   void *parse_node,
				   const struct fsal_up_vector *up_ops);

/* Module initialization.
 * Called by dlopen() to register the module
 * keep a private pointer to me in myself
 */

/* my module private storage
 */

static struct dbgfs_fsal_module DBGFS;
struct next_ops next_ops;
struct export_ops next_exp_ops;
struct fsal_obj_ops next_obj_ops;
struct fsal_ds_ops next_ds_ops;

/* linkage to the exports and handle ops initializers
 */

MODULE_INIT void dbgfs_init(void)
{
	int retval;
	struct fsal_module *myself = &DBGFS.fsal;

	retval = register_fsal(myself, myname, FSAL_MAJOR_VERSION,
			       FSAL_MINOR_VERSION, FSAL_ID_NO_PNFS);
	if (retval != 0) {
		fprintf(stderr, "DBGFS module failed to register");
		return;
	}
	myself->ops->create_export = dbgfs_create_export;
	myself->ops->init_config = init_config;
}

MODULE_FINI void dbgfs_unload(void)
{
	int retval;

	retval = unregister_fsal(&DBGFS.fsal);
	if (retval != 0) {
		fprintf(stderr, "DBGFS module failed to unregister");
		return;
	}
}
