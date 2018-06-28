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
/*
 * vim:noexpandtab:shiftwidth=8:tabstop=8:
 */

/* main.c
 * Module core functions
 */

#include "config.h"

#include "fsal.h"
#include <libgen.h> /* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <limits.h>
#include <sys/types.h>
#include "FSAL/fsal_init.h"
#include "kurma_methods.h"
#include "../fsal_private.h"
#include "kurma.h"
#include "perf_stats.h"

/* KURMA FSAL module private storage
 */

/* defined the set of attributes supported with POSIX */
// TODO(kelong) find out supported attrs
#define KURMA_SUPPORTED_ATTRIBUTES                                             \
	(ATTR_TYPE | ATTR_SIZE | ATTR_FSID | ATTR_FILEID | ATTR_MODE |         \
	 ATTR_NUMLINKS | ATTR_OWNER | ATTR_GROUP | ATTR_ATIME | ATTR_RAWDEV |  \
	 ATTR_CTIME | ATTR_MTIME | ATTR_SPACEUSED | ATTR_CHGTIME)

struct kurma_fsal_module
{
	struct fsal_module fsal;
	struct fsal_staticfsinfo_t fs_info;
	kurma_specific_initinfo_t kurma_info;
};

const char myname[] = "KURMA";

/* filesystem info for KURMA */
static struct fsal_staticfsinfo_t default_posix_info = {
	.maxfilesize = INT64_MAX, /* thrift does not support uint64 */
	.maxlink = 0,
	.maxnamelen = MAXNAMLEN,
	.maxpathlen = MAXPATHLEN,
	.no_trunc = true,
	.chown_restricted = true,
	.case_insensitive = false,
	.case_preserving = true,
	.link_support = false,
	.symlink_support = false,
	.lock_support = false,
	.lock_support_owner = false,
	.lock_support_async_block = false,
	.named_attr = false,
	.unique_handles = true,
	.lease_time = { 10, 0 },
	.acl_support = 0,
	.cansettime = true,
	.homogenous = true,
	.supported_attrs = KURMA_SUPPORTED_ATTRIBUTES,
	.maxread = FSAL_MAXIOSIZE,
	.maxwrite = FSAL_MAXIOSIZE,
	.umask = 0,
	.auth_exportpath_xdev = false,
	.xattr_access_rights = 0400, /* root=RW, owner=R */
};

static struct config_item kurma_client_params[] =
    { CONF_ITEM_STR("Kurma_Server", 1, MAXNAMLEN, NULL, client_params, server),
      CONF_ITEM_UI16("Kurma_Port", 1, 65535, 9090, client_params, port),
      CONF_ITEM_STR("Client_ID", 1, MAXNAMLEN, NULL, client_params, clientid),
      CONF_ITEM_STR("Volume_ID", 1, MAXNAMLEN, NULL, client_params, volumeid),
      CONF_ITEM_UI16("Renew_Interval", 0, 65535, 10, client_params,
		     renew_interval),
      CONF_ITEM_BOOL("Enable_Snapshot", false, client_params, enable_snapshot),
      CONFIG_EOL };

static struct config_item kurma_params[] =
    { CONF_ITEM_BOOL("link_support", true, kurma_fsal_module,
		     fs_info.link_support),
      CONF_ITEM_BOOL("symlink_support", true, kurma_fsal_module,
		     fs_info.symlink_support),
      CONF_ITEM_BOOL("cansettime", true, kurma_fsal_module, fs_info.cansettime),
      CONF_ITEM_UI64("maxread", 512, FSAL_MAXIOSIZE, FSAL_MAXIOSIZE,
		     kurma_fsal_module, fs_info.maxread),
      CONF_ITEM_UI64("maxwrite", 512, FSAL_MAXIOSIZE, FSAL_MAXIOSIZE,
		     kurma_fsal_module, fs_info.maxwrite),
      CONF_ITEM_BLOCK("Remote_Server", kurma_client_params, noop_conf_init,
		      noop_conf_commit, kurma_fsal_module,
		      kurma_info), /*fake filler */
      CONFIG_EOL };

struct config_block kurma_param = { .dbus_interface_name =
					"org.ganesha.nfsd.config.fsal.kurma",
				    .blk_desc.name = "KURMA",
				    .blk_desc.type = CONFIG_BLOCK,
				    .blk_desc.u.blk.init = noop_conf_init,
				    .blk_desc.u.blk.params = kurma_params,
				    .blk_desc.u.blk.commit = noop_conf_commit };

/* private helper for export object
 */

struct fsal_staticfsinfo_t *kurma_staticinfo(struct fsal_module *hdl)
{
	struct kurma_fsal_module *myself;

	myself = container_of(hdl, struct kurma_fsal_module, fsal);
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
	struct config_error_type err_type;
	struct kurma_fsal_module *kurma_me =
	    container_of(fsal_hdl, struct kurma_fsal_module, fsal);
	kurma_specific_initinfo_t *kurma_info = &kurma_me->kurma_info;

	/* get a copy of the defaults */
	kurma_me->fs_info = default_posix_info;

	/* if we have fsal specific params, do them here
	 * fsal_hdl->name is used to find the block containing the
	 * params.
	 */
	(void)load_config_from_parse(config_struct, &kurma_param, kurma_me,
				     true, &err_type);
	if (!config_error_is_harmless(&err_type))
		return fsalstat(ERR_FSAL_INVAL, 0);

	KURMA_D("%s:%d; clientid: %s, volumeid: %s, snapshot: %d",
		kurma_info->server, kurma_info->port, kurma_info->clientid,
		kurma_info->volumeid, kurma_info->enable_snapshot);
	if (rpc_init(kurma_info) != KURMA_OKAY)
		return fsalstat(ERR_FSAL_BAD_INIT, 0);

	snapshot_enabled = kurma_info->enable_snapshot;

	display_fsinfo(&kurma_me->fs_info);
	KURMA_F("Supported attributes constant = 0x%" PRIx64,
		(uint64_t)KURMA_SUPPORTED_ATTRIBUTES);
	KURMA_F("Supported attributes default = 0x%" PRIx64,
		default_posix_info.supported_attrs);
	KURMA_D("FSAL INIT: Supported attributes mask = 0x%" PRIx64,
		kurma_me->fs_info.supported_attrs);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/* Module initialization.
 * Called by dlopen() to register the module
 * keep a private pointer to me in myself
 */

/* my module private storage
 */

static struct kurma_fsal_module KURMA;

/* linkage to the exports and handle ops initializers
 */

MODULE_INIT int unload_kurma_fsal(struct fsal_module *fsal_hdl)
{
	int retval;

	retval = unregister_fsal(&KURMA.fsal);
	if (retval != 0)
		fprintf(stderr, "KURMA module failed to unregister");

	return retval;
}

MODULE_INIT void kurma_fsal_init(void)
{
	int retval;
	struct fsal_module *myself = &KURMA.fsal;

	retval = register_fsal(myself, myname, FSAL_MAJOR_VERSION,
			       FSAL_MINOR_VERSION, FSAL_ID_NO_PNFS);
	if (retval != 0) {
		fprintf(stderr, "KURMA module failed to register");
		return;
	}
	myself->ops->create_export = kurma_create_export;
	myself->ops->init_config = init_config;
	myself->ops->unload = unload_kurma_fsal;
	myself->name = gsh_strdup("KURMA");

	init_perf_counters();
}
