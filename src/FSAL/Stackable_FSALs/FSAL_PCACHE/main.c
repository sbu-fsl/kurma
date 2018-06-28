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
#include <libgen.h> /* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <limits.h>
#include <sys/types.h>
#include "ganesha_list.h"
#include "FSAL/fsal_init.h"
#include "pcachefs_methods.h"
#include "cache_handle.h"
#include "antivirus.h"
#include "writeback_manager.h"
#include "capi/proxy_cache.h"

/* PCACHEFS FSAL module private storage
 */

/* defined the set of attributes supported with POSIX */
#define PCACHEFS_SUPPORTED_ATTRIBUTES                                          \
	(ATTR_TYPE | ATTR_SIZE | ATTR_FSID | ATTR_FILEID | ATTR_MODE |         \
	 ATTR_NUMLINKS | ATTR_OWNER | ATTR_GROUP | ATTR_ATIME | ATTR_RAWDEV |  \
	 ATTR_CTIME | ATTR_MTIME | ATTR_SPACEUSED | ATTR_CHGTIME)

/* FSAL name determines name of shared library: libfsal<name>.so */
const char myname[] = "PCACHE";

/* filesystem info for PCACHEFS */
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
	.lease_time = { 10, 0 },
	.acl_support = FSAL_ACLSUPPORT_ALLOW,
	.cansettime = true,
	.homogenous = true,
	.supported_attrs = PCACHEFS_SUPPORTED_ATTRIBUTES,
	.maxread = FSAL_MAXIOSIZE,
	.maxwrite = FSAL_MAXIOSIZE,
	.umask = 0,
	.auth_exportpath_xdev = false,
	.xattr_access_rights = 0400, /* root=RW, owner=R */
};

/* private helper for export object
 */

struct fsal_staticfsinfo_t *pcachefs_staticinfo(struct fsal_module *hdl)
{
	struct pcachefs_fsal_module *myself;

	myself = container_of(hdl, struct pcachefs_fsal_module, fsal);
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
	struct pcachefs_fsal_module *pcachefs_me =
	    container_of(fsal_hdl, struct pcachefs_fsal_module, fsal);

	/* get a copy of the defaults */
	pcachefs_me->fs_info = default_posix_info;

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

	display_fsinfo(&pcachefs_me->fs_info);
	LogFullDebug(COMPONENT_FSAL,
		     "Supported attributes constant = 0x%" PRIx64,
		     (uint64_t)PCACHEFS_SUPPORTED_ATTRIBUTES);
	LogFullDebug(COMPONENT_FSAL,
		     "Supported attributes default = 0x%" PRIx64,
		     default_posix_info.supported_attrs);
	LogDebug(COMPONENT_FSAL,
		 "FSAL INIT: Supported attributes mask = 0x%" PRIx64,
		 pcachefs_me->fs_info.supported_attrs);

	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/* Internal PCACHEFS method linkage to export object
 */

fsal_status_t pcachefs_create_export(struct fsal_module *fsal_hdl,
				     void *parse_node,
				     const struct fsal_up_vector *up_ops);

/* Module initialization.
 * Called by dlopen() to register the module
 * keep a private pointer to me in myself
 */

/* my module private storage
 */

static struct pcachefs_fsal_module PCACHEFS;
struct next_ops next_ops;
struct export_ops next_exp_ops;
struct fsal_obj_ops next_obj_ops;
struct fsal_ds_ops next_ds_ops;
struct fsal_export *super_fsal_export;
// config
bool enable_av;		// flag to control anti virus
const char *data_cache; // path of data cache
const char *meta_cache; // path of metadata cache
size_t av_max_filesize; // size limit of file scanned by Anti Virus
int writeback_seconds;  // time after which writeback to server happens
// read/write limits
size_t pcache_maxread = PCACHE_MAXREAD;
size_t pcache_maxwrite = PCACHE_MAXWRITE;

struct pcachefs_counters pc_counters;

static pthread_t pc_counter_thread;
static const char* pc_counter_path = "/var/log/pcachefs-counters.txt";
static int pc_counter_running = 1;

static void *output_counter(void *arg)
{
	int n;
	char buf[1024];

#define PC_TIME_MS(name)                                                       \
	(__sync_fetch_and_or(&pc_counters.name, 0) / NS_PER_MSEC)

	while (__sync_fetch_and_or(&pc_counter_running, 0)) {
		n = snprintf(
		    buf, 1024, "%d %d %d %d %d %d %d %d %d %d %d %d %d %d %d "
			       "%d %d %d %d %d %d %d %llu %llu %llu %llu %llu "
			       "%llu %llu %llu\n",
		    __sync_fetch_and_or(&pc_counters.nr_creates, 0),
		    __sync_fetch_and_or(&pc_counters.nr_opens, 0),
		    __sync_fetch_and_or(&pc_counters.nr_getattrs, 0),
		    __sync_fetch_and_or(&pc_counters.nr_setattrs, 0),
		    __sync_fetch_and_or(&pc_counters.nr_lookups, 0),
		    __sync_fetch_and_or(&pc_counters.nr_mkdirs, 0),
		    __sync_fetch_and_or(&pc_counters.nr_unlinks, 0),
		    __sync_fetch_and_or(&pc_counters.nr_reads, 0),
		    __sync_fetch_and_or(&pc_counters.nr_writes, 0),
		    __sync_fetch_and_or(&pc_counters.nr_commits, 0),
		    __sync_fetch_and_or(&pc_counters.nr_readdirs, 0),
		    __sync_fetch_and_or(&pc_counters.nr_renames, 0),
		    __sync_fetch_and_or(&pc_counters.nr_closes, 0),
		    __sync_fetch_and_or(&pc_counters.ops_hist.nr_ops_tiny, 0),
		    __sync_fetch_and_or(&pc_counters.ops_hist.nr_ops_4k, 0),
		    __sync_fetch_and_or(&pc_counters.ops_hist.nr_ops_16k, 0),
		    __sync_fetch_and_or(&pc_counters.ops_hist.nr_ops_64k, 0),
		    __sync_fetch_and_or(&pc_counters.ops_hist.nr_ops_unaligned,
					0),
		    __sync_fetch_and_or(&pc_counters.cache_full_hits, 0),
		    __sync_fetch_and_or(&pc_counters.cache_partial_hits, 0),
		    __sync_fetch_and_or(&pc_counters.cache_misses, 0),
		    __sync_fetch_and_or(&pc_counters.av_scans, 0),
		    PC_TIME_MS(read_time_ns),
		    PC_TIME_MS(write_time_ns),
		    PC_TIME_MS(open_time_ns),
		    PC_TIME_MS(close_time_ns),
		    PC_TIME_MS(getattr_time_ns),
		    PC_TIME_MS(lookup_time_ns),
		    PC_TIME_MS(create_time_ns),
		    PC_TIME_MS(unlink_time_ns));

#undef PC_TIME_MS

		lock_and_append(pc_counter_path, buf, n);

		sleep(COUNTER_OUTPUT_INTERVAL);
	}
}

/* linkage to the exports and handle ops initializers
 */

MODULE_INIT void pcachefs_init(void)
{
	int retval;
	struct fsal_module *self = &PCACHEFS.fsal;

	retval = register_fsal(self, myname, FSAL_MAJOR_VERSION,
			       FSAL_MINOR_VERSION, FSAL_ID_NO_PNFS);
	if (retval != 0) {
		fprintf(stderr, "PCACHEFS module failed to register");
		return;
	}
	self->ops->create_export = pcachefs_create_export;
	self->ops->init_config = init_config;

	retval =
	    pthread_create(&pc_counter_thread, NULL, &output_counter, NULL);
	if (retval != 0) {
		PC_FATAL("failed to create counter output thread: %d", retval);
	}
}

MODULE_FINI void pcachefs_unload(void)
{
	int retval;

	retval = unregister_fsal(&PCACHEFS.fsal);
	if (retval != 0) {
		fprintf(stderr, "PCACHEFS module failed to unregister");
		return;
	}

	__sync_fetch_and_sub(&pc_counter_running, 1);

	wb_mgr_destroy();
	destroy_proxy_cache(); // TODO(arun): How to make sure all dirty data is
			       // written
	pm_destroy();
}
