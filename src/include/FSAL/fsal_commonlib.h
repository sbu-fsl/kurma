/*
 *
 *
 *
 * Copyright CEA/DAM/DIF  (2008)
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 * ---------------------------------------
 */

/**
 * @defgroup FSAL File-System Abstraction Layer
 * @{
 */

/**
 * @file  fsal_commomnlib.h
 * @brief Miscelaneous FSAL common library routines
 */

#ifndef FSAL_COMMONLIB_H
#define FSAL_COMMONLIB_H

/*
 * fsal common utility functions
 */

/* fsal_module to fsal_export helpers
 */

int fsal_attach_export(struct fsal_module *fsal_hdl,
		       struct glist_head *obj_link);
void fsal_detach_export(struct fsal_module *fsal_hdl,
			struct glist_head *obj_link);

/* fsal_export common methods
 */

int fsal_export_init(struct fsal_export *export);

void free_export_ops(struct fsal_export *exp_hdl);

/* fsal_obj_handle common methods
 */

void fsal_obj_handle_init(struct fsal_obj_handle *, struct fsal_export *,
			  object_file_type_t);

void fsal_obj_handle_uninit(struct fsal_obj_handle *obj);

/*
 * pNFS DS Helpers
 */

void fsal_ds_handle_init(struct fsal_ds_handle *, struct fsal_ds_ops *,
			 struct fsal_module *);
void fsal_ds_handle_uninit(struct fsal_ds_handle *ds);

int open_dir_by_path_walk(int first_fd, const char *path, struct stat *stat);

struct avltree avl_fsid;
struct avltree avl_dev;

struct glist_head posix_file_systems;

pthread_rwlock_t fs_lock;

void free_fs(struct fsal_filesystem *fs);

int populate_posix_file_systems(void);

void release_posix_file_systems(void);

int re_index_fs_fsid(struct fsal_filesystem *fs,
		     enum fsid_type fsid_type,
		     uint64_t major,
		     uint64_t minor);

int re_index_fs_dev(struct fsal_filesystem *fs,
		    struct fsal_dev__ *dev);

int change_fsid_type(struct fsal_filesystem *fs,
		     enum fsid_type fsid_type);

struct fsal_filesystem *lookup_fsid_locked(struct fsal_fsid__ *fsid,
					   enum fsid_type fsid_type);
struct fsal_filesystem *lookup_dev_locked(struct fsal_dev__ *dev);
struct fsal_filesystem *lookup_fsid(struct fsal_fsid__ *fsid,
				    enum fsid_type fsid_type);
struct fsal_filesystem *lookup_dev(struct fsal_dev__ *dev);

void unclaim_fs(struct fsal_filesystem *this);

int claim_posix_filesystems(const char *path,
			    struct fsal_module *fsal,
			    struct fsal_export *exp,
			    claim_filesystem_cb claim,
			    unclaim_filesystem_cb unclaim,
			    struct fsal_filesystem **root_fs);

int encode_fsid(char *buf,
		int max,
		struct fsal_fsid__ *fsid,
		enum fsid_type fsid_type);

int decode_fsid(char *buf,
		int max,
		struct fsal_fsid__ *fsid,
		enum fsid_type fsid_type);

struct subfsal_args {
	void *fsal_node;
	char *name;
};

int subfsal_commit(void *node, void *link_mem, void *self_struct,
		   struct config_error_type *err_type);

struct operation_size_histogram {
	uint32_t nr_ops_tiny;	/* # of reads/writes < 4KB */
	uint32_t nr_ops_4k;	/* # of reads/writes between [4KB, 16KB) */
	uint32_t nr_ops_16k;
	uint32_t nr_ops_64k;
	uint32_t nr_ops_256k;
	uint32_t nr_ops_1m;     /* >= 1MB */
	uint32_t nr_ops_unaligned;
};

static inline void ops_hist_add(struct operation_size_histogram *ops_hist,
				size_t offset, size_t size) {
	if ((offset & 0xFFF) || (size & 0xFFF)) {
		__sync_fetch_and_add(&ops_hist->nr_ops_unaligned, 1);
	}
	if (size < 0x1000) {
		__sync_fetch_and_add(&ops_hist->nr_ops_tiny, 1);
	} else if (size < 0x4000) {
		__sync_fetch_and_add(&ops_hist->nr_ops_4k, 1);
	} else if (size < 0x10000) {
		__sync_fetch_and_add(&ops_hist->nr_ops_16k, 1);
	} else if (size < 0x40000) {
		__sync_fetch_and_add(&ops_hist->nr_ops_64k, 1);
	} else if (size < 0x100000) {
		__sync_fetch_and_add(&ops_hist->nr_ops_256k, 1);
	} else {
		__sync_fetch_and_add(&ops_hist->nr_ops_1m, 1);
	}
}

#define COUNTER_OUTPUT_INTERVAL 5

void lock_and_append(const char *fname, const char *buf, size_t len);

/**
 * Update the attributes that are set in "new_attrs".
 *
 * @new_attrs [in]: the new attributes to set
 * @attrs [in/out]: the attrlist to be updated
 */
void update_attributes(const struct attrlist *new_attrs,
		       struct attrlist *attrs);

static inline void set_attr_expire_time(struct attrlist *attrs, int seconds) {
	attrs->expire_time_attr = seconds;
	attrs->mask |= ATTR_EXPIRE_TIME_ATTR;
}

#endif				/* FSAL_COMMONLIB_H */
