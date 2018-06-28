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
#include "FSAL/fsal_init.h"
#include "secnfs_methods.h"
#include "secnfs.h"

/* defined the set of attributes supported with POSIX */
#define SECNFS_SUPPORTED_ATTRIBUTES (                                       \
          ATTR_TYPE     | ATTR_SIZE     |                  \
          ATTR_FSID     | ATTR_FILEID   |                  \
          ATTR_MODE     | ATTR_NUMLINKS | ATTR_OWNER     | \
          ATTR_GROUP    | ATTR_ATIME    | ATTR_RAWDEV    | \
          ATTR_CTIME    | ATTR_MTIME    | ATTR_SPACEUSED | \
          ATTR_CHGTIME  )

const char myname[] = "SECNFS";

/* filesystem info for SECNFS */
static struct fsal_staticfsinfo_t default_posix_info = {
	.maxfilesize = 0xFFFFFFFFFFFFFFFFLL,	/* (64bits) */
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
	.supported_attrs = SECNFS_SUPPORTED_ATTRIBUTES,
	.maxread = FSAL_MAXIOSIZE,
	.maxwrite = FSAL_MAXIOSIZE,
	.auth_exportpath_xdev = false,
	.xattr_access_rights = 0400,	/* root=RW, owner=R */
};

/************** private helper for export object *******************/

struct fsal_staticfsinfo_t *secnfs_staticinfo(struct fsal_module *hdl)
{
	struct secnfs_fsal_module *myself;

	myself = container_of(hdl, struct secnfs_fsal_module, fsal);
	return &myself->fs_info;
}

/************************ Module methods **************************/

static struct config_item secnfs_params[] = {
	CONF_ITEM_STR("Name", 1, MAXNAMLEN, NULL,
		      secnfs_info, secnfs_name),
	CONF_ITEM_BOOL("Create_If_No_Context", true,
		       secnfs_info, create_if_no_context),
        CONF_ITEM_PATH("Context_Cache_File", 1, MAXPATHLEN,
                       "/etc/ganesha/secnfs-context.conf",
                       secnfs_info, context_cache_file),
	CONF_ITEM_PATH("Proxy_Lists", 1, MAXPATHLEN,
                       "/etc/ganesha/secnfs-proxy-list.conf",
		       secnfs_info, plist_file),
	CONF_ITEM_BOOL("File_Encryption", false,
		       secnfs_info, file_encryption),
	CONFIG_EOL
};

struct config_block secnfs_param = {
	.dbus_interface_name = "org.ganesha.nfsd.config.fsal.secnfs",
	.blk_desc.name = "SECNFS",
	.blk_desc.type = CONFIG_BLOCK,
	.blk_desc.u.blk.init = noop_conf_init,
	.blk_desc.u.blk.params = secnfs_params,
	.blk_desc.u.blk.commit = noop_conf_commit
};

/* can be removed if ganesha config_parsing checks for us */
static int validate_conf_params(const secnfs_info_t *info)
{
        if (!info->context_cache_file) {
                LogCrit(COMPONENT_CONFIG, "'Context_Cache_File' not set");
                return 0;
        }

        if (!access(info->context_cache_file, F_OK)
                        && !info->create_if_no_context) {
                LogCrit(COMPONENT_CONFIG, "cannot access '%s'",
                        info->context_cache_file);
                return 0;
        }

        return 1;
}

/*
 * must be called with a reference taken (via lookup_fsal)
 */
static fsal_status_t init_config(struct fsal_module *fsal_hdl,
				 config_file_t config_struct)
{
	struct secnfs_fsal_module *secnfs_me = secnfs_module(fsal_hdl);
        secnfs_info_t *info = &secnfs_info;
	struct config_error_type err_type;

	secnfs_me->fs_info = default_posix_info;	/* get a copy of the defaults */

	/* if we have fsal specific params, do them here
	 * fsal_hdl->name is used to find the block containing the
	 * params.
	 */
	(void) load_config_from_parse(config_struct,
				      &secnfs_param,
				      &secnfs_info,
				      true,
				      &err_type);
	if (!config_error_is_harmless(&err_type))
		return fsalstat(ERR_FSAL_INVAL, 0);

        SECNFS_F("Context_Cache_File = %s", info->context_cache_file);
        SECNFS_F("secnfs_name = %s", info->secnfs_name);
        SECNFS_F("create_if_no_context = %d", info->create_if_no_context);
        SECNFS_F("file_encryption = %d", info->file_encryption);

        if (!validate_conf_params(info)) {
                SECNFS_ERR("invalid SECNFS config");
                return fsalstat(ERR_FSAL_INVAL, SECNFS_WRONG_CONFIG);
        }

        if (secnfs_init_info(info) != SECNFS_OKAY) {
                SECNFS_ERR("SECNFS failed to created context");
                return fsalstat(ERR_FSAL_NOMEM, ENOMEM);
        }

	display_fsinfo(&secnfs_me->fs_info);
	LogFullDebug(COMPONENT_FSAL,
		     "Supported attributes constant = 0x%" PRIx64,
		     (uint64_t) SECNFS_SUPPORTED_ATTRIBUTES);
	LogFullDebug(COMPONENT_FSAL,
		     "Supported attributes default = 0x%" PRIx64,
		     default_posix_info.supported_attrs);
	LogDebug(COMPONENT_FSAL,
		 "FSAL INIT: Supported attributes mask = 0x%" PRIx64,
		 secnfs_me->fs_info.supported_attrs);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/* Internal SECNFS method linkage to export object
 */

fsal_status_t secnfs_create_export(struct fsal_module * fsal_hdl,
				   void *parse_node,
				   const struct fsal_up_vector * up_ops);

/* Module initialization.
 * Called by dlopen() to register the module
 * keep a private pointer to me in myself
 */

/* my module private storage
 */

static struct secnfs_fsal_module SECNFS;

/* TODO make it a per-export variable? Then, we need to call fsal_export_init
 * to initialize each struct next_ops. */
struct next_ops next_ops;


struct secnfs_counters sn_counters;

static pthread_t sn_counter_thread;
static const char *sn_counter_path = "/var/log/secnfs-counters.txt";
static int sn_counter_running = 1;

static void *output_secnfs_counters(void *arg)
{
	int n;
	char buf[1024];

	while (__sync_fetch_and_or(&sn_counter_running, 0)) {
		n = snprintf(
		    buf, 1024, "%d %d %d %d %d %d %d %d %d %d %d %d %d %d %d "
			       "%d %d %d %d %d\n",
		    __sync_fetch_and_or(&sn_counters.nr_creates, 0),
		    __sync_fetch_and_or(&sn_counters.nr_opens, 0),
		    __sync_fetch_and_or(&sn_counters.nr_getattrs, 0),
		    __sync_fetch_and_or(&sn_counters.nr_setattrs, 0),
		    __sync_fetch_and_or(&sn_counters.nr_lookups, 0),
		    __sync_fetch_and_or(&sn_counters.nr_mkdirs, 0),
		    __sync_fetch_and_or(&sn_counters.nr_unlinks, 0),
		    __sync_fetch_and_or(&sn_counters.nr_reads, 0),
		    __sync_fetch_and_or(&sn_counters.nr_writes, 0),
		    __sync_fetch_and_or(&sn_counters.nr_commits, 0),
		    __sync_fetch_and_or(&sn_counters.nr_readdirs, 0),
		    __sync_fetch_and_or(&sn_counters.nr_renames, 0),
		    __sync_fetch_and_or(&sn_counters.nr_closes, 0),
		    __sync_fetch_and_or(&sn_counters.ops_hist.nr_ops_tiny, 0),
		    __sync_fetch_and_or(&sn_counters.ops_hist.nr_ops_4k, 0),
		    __sync_fetch_and_or(&sn_counters.ops_hist.nr_ops_16k, 0),
		    __sync_fetch_and_or(&sn_counters.ops_hist.nr_ops_64k, 0),
		    __sync_fetch_and_or(&sn_counters.ops_hist.nr_ops_unaligned,
					0),
		    __sync_fetch_and_or(&sn_counters.nr_read_modify_update, 0),
		    __sync_fetch_and_or(&sn_counters.nr_read_headers, 0));

		lock_and_append(sn_counter_path, buf, n);

		sleep(COUNTER_OUTPUT_INTERVAL);
	}
}

/* linkage to the exports and handle ops initializers
 */

MODULE_INIT void secnfs_init(void)
{
	int retval;
	struct fsal_module *myself = &SECNFS.fsal;

	retval = register_fsal(myself, myname, FSAL_MAJOR_VERSION,
			       FSAL_MINOR_VERSION, FSAL_ID_NO_PNFS);
	if (retval != 0) {
		fprintf(stderr, "SECNFS module failed to register");
		return;
	}

	myself->ops->create_export = secnfs_create_export;
	myself->ops->init_config = init_config;

	retval = pthread_create(&sn_counter_thread, NULL,
				&output_secnfs_counters, NULL);
	if (retval != 0) {
		fprintf(stderr, "failed to create counter output thread: %d",
			retval);
	}

	SECNFS_D("secnfs module initialized.");
}

MODULE_FINI void secnfs_unload(void)
{
	int retval;

	retval = unregister_fsal(&SECNFS.fsal);
	if (retval != 0) {
		fprintf(stderr, "SECNFS module failed to unregister");
		return;
	}

	__sync_fetch_and_sub(&sn_counter_running, 1);

        secnfs_destroy_context(&secnfs_info);
}
