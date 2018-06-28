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
 * vim:expandtab:shiftwidth=8:tabstop=8:
 * SECNFS methods for handles
 */

#include "secnfs.h"
#include "fsal_handle_syscalls.h"
#include "nfs_integrity.h" /* get PI_INTERVAL_SIZE */
#include "FSAL/fsal_commonlib.h"

/* TODO replace this with something like 'struct secnfs_export'. */
struct next_ops {
	struct export_ops *exp_ops;	/*< Vector of operations */
	struct fsal_obj_ops *obj_ops;	/*< Shared handle methods vector */
	struct fsal_ds_ops *ds_ops;	/*< Shared handle methods vector */
	struct fsal_up_vector *up_ops;	/*< Upcall operations */
};

#define SECNFS_ERR(fmt, args...) LogCrit(COMPONENT_FSAL, "=secnfs=" fmt, ## args)
#define SECNFS_I(fmt, args...) LogInfo(COMPONENT_FSAL, "=secnfs=" fmt, ## args)
#define SECNFS_D(fmt, args...) LogDebug(COMPONENT_FSAL, "=secnfs=" fmt, ## args)
#define SECNFS_F(fmt, args...) LogFullDebug(COMPONENT_FSAL, "=secnfs=" fmt, ## args)

/*
 * SECNFS internal export
 */
struct secnfs_fsal_export {
	struct fsal_export export;
        struct fsal_export *next_export;
};

/* SECNFS FSAL module private storage */
struct secnfs_fsal_module {
	struct fsal_module fsal;
	struct fsal_staticfsinfo_t fs_info;
};

// Stackable FSALs better not use FSAL module private storage
secnfs_info_t secnfs_info;

fsal_status_t secnfs_lookup_path(struct fsal_export *exp_hdl,
				 const char *path,
				 struct fsal_obj_handle **handle);

fsal_status_t secnfs_create_handle(struct fsal_export *exp_hdl,
				   struct gsh_buffdesc *hdl_desc,
				   struct fsal_obj_handle **handle);

/*
 * SECNFS internal object handle
 *
 * KeyFile is kept at the beginning of the data file.
 */
struct secnfs_fsal_obj_handle {
        struct fsal_obj_handle obj_handle;
        struct fsal_obj_handle *next_handle;    /*< handle of next layer */
        secnfs_key_t fk;                        /*< file symmetric key */
        secnfs_key_t iv;                        /*< initialization vector */
        secnfs_info_t *info;                    /*< secnfs info */

        size_t filesize;                        /*< effective file size */

        /**
         * Writing to header upon close has the side effect of updating the
         * modify and change timestamps of the file in the server side.  This
         * side effect is harmful because it invalidates clients' cache: when a
         * client write to a file, the client keeps the written content as
         * cache and take timestamp at the write for cache revalidation.  When
         * we write header thereafter, the client's timestamp of the cache no
         * longer match the modify timestamp in the server side.  This mismatch
         * of timestamp requires client to invalidate the cache because the
         * client thinks others have written to the file after him.
         *
         * To avoid this, we need to save the effective modify and change
         * timestamp in header.  We also need to save "server_change" to
         * detect if the file has indeed changed by other clients.
         */
        struct timespec modify_time;
        struct timespec change_time;
        uint64_t server_change;
        uint64_t change;

        uint32_t key_initialized;
        uint32_t has_dirty_meta;                /*< need to update header? */
        void *range_lock;
        void *holes;
        void *kf_cache;                         /* cached keyfile */
        bool encrypted;
};

static inline fsal_status_t secnfs_to_fsal_status(secnfs_s s) {
        fsal_status_t fsal_s;

        if (s == SECNFS_OKAY) {
                fsal_s.major = ERR_FSAL_NO_ERROR;
                fsal_s.minor = 0;
        } else {
                fsal_s.major = ERR_FSAL_IO;
                fsal_s.minor = s;
        }

        return fsal_s;
}

struct secnfs_counters {
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

	struct operation_size_histogram ops_hist;

	uint32_t nr_read_modify_update;
	uint32_t nr_read_headers;
};

extern struct secnfs_counters sn_counters;

static inline struct secnfs_fsal_obj_handle*
secnfs_handle(struct fsal_obj_handle *handle)
{
        return container_of(handle, struct secnfs_fsal_obj_handle, obj_handle);
}

static inline struct secnfs_fsal_export*
secnfs_export(struct fsal_export *export)
{
        return container_of(export, struct secnfs_fsal_export, export);
}

static inline struct secnfs_fsal_module*
secnfs_module(struct fsal_module *fsal)
{
        return container_of(fsal, struct secnfs_fsal_module, fsal);
}

static inline struct fsal_obj_handle* next_handle(struct fsal_obj_handle *hdl)
{
        return secnfs_handle(hdl)->next_handle;
}

static inline struct fsal_export* next_export(struct fsal_export *exp)
{
        return secnfs_export(exp)->next_export;
}

static inline bool is_aligned(uint64_t n, uint64_t m)
{
        assert((m & (m - 1)) == 0);
        return (n & (m - 1)) == 0;
}

/* get effective filesize */
static inline uint64_t get_filesize(struct secnfs_fsal_obj_handle *hdl)
{
        return hdl->filesize;
}

/* update effective filesize in handle */
static inline void update_filesize(struct secnfs_fsal_obj_handle *hdl,
                                   uint64_t s)
{
        if (s != hdl->filesize) {
                hdl->filesize = s;
                hdl->has_dirty_meta = 1;
        }
}

int secnfs_fsal_open(struct secnfs_fsal_obj_handle *, int, fsal_errors_t *);
int secnfs_fsal_readlink(struct secnfs_fsal_obj_handle *, fsal_errors_t *);

static inline bool secnfs_unopenable_type(object_file_type_t type)
{
	if ((type == SOCKET_FILE) || (type == CHARACTER_FILE)
	    || (type == BLOCK_FILE)) {
		return true;
	} else {
		return false;
	}
}

fsal_status_t read_header(struct fsal_obj_handle *fsal_hdl);

fsal_status_t write_header(struct fsal_obj_handle *fsal_hdl);

	/* I/O management */
fsal_status_t secnfs_open(struct fsal_obj_handle * obj_hdl,
			  fsal_openflags_t openflags);
fsal_openflags_t secnfs_status(struct fsal_obj_handle *obj_hdl);
fsal_status_t secnfs_read(struct fsal_obj_handle *obj_hdl,
			  uint64_t offset,
			  size_t buffer_size, void *buffer,
			  size_t * read_amount, bool * end_of_file);
fsal_status_t secnfs_write(struct fsal_obj_handle *obj_hdl,
			   uint64_t offset,
			   size_t buffer_size, void *buffer,
			   size_t * write_amount, bool * fsal_stable);
fsal_status_t secnfs_truncate(struct fsal_obj_handle *obj_hdl,
                              uint64_t newsize);
fsal_status_t secnfs_commit(struct fsal_obj_handle *obj_hdl,	/* sync */
			    off_t offset, size_t len);
fsal_status_t secnfs_lock_op(struct fsal_obj_handle *obj_hdl,
			     void *p_owner,
			     fsal_lock_op_t lock_op,
			     fsal_lock_param_t * request_lock,
			     fsal_lock_param_t * conflicting_lock);
fsal_status_t secnfs_share_op(struct fsal_obj_handle *obj_hdl, void *p_owner,	/* IN (opaque to FSAL) */
			      fsal_share_param_t request_share);
fsal_status_t secnfs_close(struct fsal_obj_handle *obj_hdl);
fsal_status_t secnfs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				 lru_actions_t requests);

/* extended attributes management */
fsal_status_t secnfs_list_ext_attrs(struct fsal_obj_handle *obj_hdl,
				    unsigned int cookie,
				    fsal_xattrent_t * xattrs_tab,
				    unsigned int xattrs_tabsize,
				    unsigned int *p_nb_returned,
				    int *end_of_list);
fsal_status_t secnfs_getextattr_id_by_name(struct fsal_obj_handle *obj_hdl,
					   const char *xattr_name,
					   unsigned int *pxattr_id);
fsal_status_t secnfs_getextattr_value_by_name(struct fsal_obj_handle *obj_hdl,
					      const char *xattr_name,
					      caddr_t buffer_addr,
					      size_t buffer_size,
					      size_t * p_output_size);
fsal_status_t secnfs_getextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					    unsigned int xattr_id,
					    caddr_t buffer_addr,
					    size_t buffer_size,
					    size_t * p_output_size);
fsal_status_t secnfs_setextattr_value(struct fsal_obj_handle *obj_hdl,
				      const char *xattr_name,
				      caddr_t buffer_addr, size_t buffer_size,
				      int create);
fsal_status_t secnfs_setextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					    unsigned int xattr_id,
					    caddr_t buffer_addr,
					    size_t buffer_size);
fsal_status_t secnfs_getextattr_attrs(struct fsal_obj_handle *obj_hdl,
				      unsigned int xattr_id,
				      struct attrlist *p_attrs);
fsal_status_t secnfs_remove_extattr_by_id(struct fsal_obj_handle *obj_hdl,
					  unsigned int xattr_id);
fsal_status_t secnfs_remove_extattr_by_name(struct fsal_obj_handle *obj_hdl,
					    const char *xattr_name);
