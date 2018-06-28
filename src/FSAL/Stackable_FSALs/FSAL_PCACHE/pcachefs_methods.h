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
/* PCACHEFS methods for handles
 */

#ifndef _PCACHEFS_METHODS_H
#define _PCACHEFS_METHODS_H

#include "capi/common.h"
#include "FSAL/fsal_commonlib.h"
#include <glib.h>

#define PC_CRT(fmt, args...) LogCrit(COMPONENT_FSAL, "=pcache=" fmt, ## args)
#define PC_WARN(fmt, args...) LogWarn(COMPONENT_FSAL, "=pcache=" fmt, ## args)
#define PC_FATAL(fmt, args...) LogFatal(COMPONENT_FSAL, "=pcache=" fmt, ## args)
#define PC_DEBUG(fmt, args...) LogDebug(COMPONENT_FSAL, "=pcache=" fmt, ## args)

#define ALIGNMENT 1048576
#define ROUNDDOWN(x) ((x) & ~(ALIGNMENT - 1))
#define ROUNDUP(x) (((x) + ALIGNMENT - 1) & ~(ALIGNMENT - 1))


#define PC_FATAL_IF(cond, fmt, args...)					    \
do {									    \
	if ((cond)) LogFatal(COMPONENT_FSAL, "=pcache=" fmt, ## args);	    \
} while (false)

#define PC_DBG(fmt, args...) LogDebug(COMPONENT_FSAL, "=pcache=" fmt, ## args)
#define PC_FULL(fmt, args...) LogFullDebug(COMPONENT_FSAL, "=pcache=" fmt, ## args)

struct pcachefs_fsal_obj_handle;

struct pcachefs_file_handle {
	int nothing;
};

struct next_ops {
	struct export_ops *exp_ops;	  /*< Vector of operations */
	struct fsal_obj_ops *obj_ops;	/*< Shared handle methods vector */
	struct fsal_ds_ops *ds_ops;	  /*< Shared handle methods vector */
	const struct fsal_up_vector *up_ops; /*< Upcall operations */
};

extern struct next_ops next_ops;
/* The next_xxx_ops vectors may as well be embedded directly in
 * struct next_ops but that would require substantial code changes to use '.'
 * instead or '->'.
 */
extern struct export_ops next_exp_ops;
extern struct fsal_obj_ops next_obj_ops;
extern struct fsal_ds_ops next_ds_ops;
void pcachefs_handle_ops_init(struct fsal_obj_ops *ops);

// config items
extern bool enable_av;
extern const char *data_cache;
extern const char *meta_cache;
extern size_t av_max_filesize;
extern int writeback_seconds;

// items used for op_ctx in writeback thread
// TODO(arun): maxread and maxwrite taken from fsal_export are not giving
// consistent values in writeback thread. So, we initialize them in lookup_path
// called for root file handle
extern size_t pcache_maxread;
extern size_t pcache_maxwrite;
extern struct fsal_export *super_fsal_export;

/*
 * PCACHEFS internal export
 */
struct pcachefs_fsal_export {
	struct fsal_export export;
	struct fsal_export *sub_export;
};

struct pcachefs_fsal_module {
	struct fsal_module fsal;
	struct fsal_staticfsinfo_t fs_info;
};

typedef struct pcachefs_info {
	int temp;
} pcachefs_info_t;

// Stackable FSALs better not use FSAL module private storage
pcachefs_info_t pcachefs_info;

/*
 * PCACHEFS internal object handle
 * handle is a pointer because
 *  a) the last element of file_handle is a char[] meaning variable len...
 *  b) we cannot depend on it *always* being last or being the only
 *     variable sized struct here...  a pointer is safer.
 * wrt locks, should this be a lock counter??
 * AF_UNIX sockets are strange ducks.  I personally cannot see why they
 * are here except for the ability of a client to see such an animal with
 * an 'ls' or get rid of one with an 'rm'.  You can't open them in the
 * usual file way so open_by_handle_at leads to a deadend.  To work around
 * this, we save the args that were used to mknod or lookup the socket.
 */
struct pcachefs_fsal_obj_handle {
	struct fsal_obj_handle obj_handle;
	struct fsal_obj_handle *next_handle; /*< handle of next layer */
	// pcachefs_info_t *info;		     /*< pcache info */

	/*< Effective file size including newly-appended content that is cached
	 * but not written back to server. */
	uint64_t pc_filesize;
	nsecs_elapsed_t pc_ctime;
	uint32_t pc_opener_count;
	fsal_openflags_t pc_file_flags;

	pthread_mutex_t pc_lock; /*< Lock to synchronize file operations,
				    protects pc_opener_count, pc_filesize,
				    pc_open_status, and locked_blocks */
	GArray *locked_blocks;
};

struct pcachefs_counters {
	uint32_t nr_creates;
	uint32_t nr_opens;
	uint32_t nr_getattrs;
	uint32_t nr_setattrs;
	uint32_t nr_lookups;
	uint32_t nr_mkdirs;
	uint32_t nr_unlinks;
	uint32_t nr_reads;
	uint32_t nr_writes;
	uint32_t nr_commits;
	uint32_t nr_readdirs;
	uint32_t nr_renames;
	uint32_t nr_closes;

	uint64_t read_time_ns;
	uint64_t write_time_ns;
	uint64_t open_time_ns;
	uint64_t close_time_ns;
	uint64_t getattr_time_ns;
	uint64_t lookup_time_ns;
	uint64_t create_time_ns;
	uint64_t unlink_time_ns;

	struct operation_size_histogram ops_hist;

	uint32_t cache_full_hits;
	uint32_t cache_partial_hits;
	uint32_t cache_misses;

	uint32_t av_scans;
};

#define PC_START_TIMER()                                                       \
	struct timespec _st_tm, _end_tm;                                       \
	now(&_st_tm)

#define PC_STOP_TIMER(name)                                                    \
	now(&_end_tm);                                                         \
	__sync_fetch_and_add(&pc_counters.name,                                \
			     timespec_diff(&_st_tm, &_end_tm))

extern struct pcachefs_counters pc_counters;

static inline struct pcachefs_fsal_obj_handle *
pcachefs_handle(struct fsal_obj_handle *handle)
{
	return container_of(handle, struct pcachefs_fsal_obj_handle,
			    obj_handle);
}

static inline struct pcachefs_fsal_export *
pcachefs_export(struct fsal_export *export)
{
	return container_of(export, struct pcachefs_fsal_export, export);
}

static inline struct pcachefs_fsal_module *
pcachefs_module(struct fsal_module *fsal)
{
	return container_of(fsal, struct pcachefs_fsal_module, fsal);
}

static inline struct fsal_obj_handle *next_handle(struct fsal_obj_handle *hdl)
{
	return pcachefs_handle(hdl)->next_handle;
}

static inline struct fsal_export *next_export(struct fsal_export *exp)
{
	return pcachefs_export(exp)->sub_export;
}

fsal_status_t pcachefs_lookup_path(struct fsal_export *exp_hdl,
				   const char *path,
				   struct fsal_obj_handle **handle);

fsal_status_t pcachefs_create_handle(struct fsal_export *exp_hdl,
				     struct gsh_buffdesc *hdl_desc,
				     struct fsal_obj_handle **handle);

/* I/O management */
fsal_status_t pcachefs_open(struct fsal_obj_handle *obj_hdl,
			    fsal_openflags_t openflags);
fsal_openflags_t pcachefs_status(struct fsal_obj_handle *obj_hdl);
fsal_status_t pcachefs_read(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			    size_t buffer_size, void *buffer,
			    size_t *read_amount, bool *end_of_file);
fsal_status_t pcachefs_write(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			     size_t buffer_size, void *buffer,
			     size_t *write_amount, bool *fsal_stable);
fsal_status_t pcachefs_commit(struct fsal_obj_handle *obj_hdl, /* sync */
			      off_t offset, size_t len);
fsal_status_t pcachefs_lock_op(struct fsal_obj_handle *obj_hdl, void *p_owner,
			       fsal_lock_op_t lock_op,
			       fsal_lock_param_t *request_lock,
			       fsal_lock_param_t *conflicting_lock);
fsal_status_t pcachefs_share_op(struct fsal_obj_handle *obj_hdl, void *p_owner,
				fsal_share_param_t request_share);
fsal_status_t pcachefs_close(struct fsal_obj_handle *obj_hdl);
fsal_status_t pcachefs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				   lru_actions_t requests);

/* extended attributes management */
fsal_status_t pcachefs_list_ext_attrs(struct fsal_obj_handle *obj_hdl,
				      unsigned int cookie,
				      fsal_xattrent_t *xattrs_tab,
				      unsigned int xattrs_tabsize,
				      unsigned int *p_nb_returned,
				      int *end_of_list);
fsal_status_t pcachefs_getextattr_id_by_name(struct fsal_obj_handle *obj_hdl,
					     const char *xattr_name,
					     unsigned int *pxattr_id);
fsal_status_t pcachefs_getextattr_value_by_name(struct fsal_obj_handle *obj_hdl,
						const char *xattr_name,
						caddr_t buffer_addr,
						size_t buffer_size,
						size_t *p_output_size);
fsal_status_t pcachefs_getextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					      unsigned int xattr_id,
					      caddr_t buffer_addr,
					      size_t buffer_size,
					      size_t *p_output_size);
fsal_status_t pcachefs_setextattr_value(struct fsal_obj_handle *obj_hdl,
					const char *xattr_name,
					caddr_t buffer_addr, size_t buffer_size,
					int create);
fsal_status_t pcachefs_setextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					      unsigned int xattr_id,
					      caddr_t buffer_addr,
					      size_t buffer_size);
fsal_status_t pcachefs_getextattr_attrs(struct fsal_obj_handle *obj_hdl,
					unsigned int xattr_id,
					struct attrlist *p_attrs);
fsal_status_t pcachefs_remove_extattr_by_id(struct fsal_obj_handle *obj_hdl,
					    unsigned int xattr_id);
fsal_status_t pcachefs_remove_extattr_by_name(struct fsal_obj_handle *obj_hdl,
					      const char *xattr_name);

#endif
