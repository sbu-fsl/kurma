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

/* KURMAFS methods for handles
 */

#ifndef FSAL_KURMA_METHOD
#define FSAL_KURMA_METHOD

#include "ganesha_list.h"

#include "kurma.h"

struct kurma_fsal_obj_handle;

extern bool snapshot_enabled;

/*
 * KURMAFS internal export
 */
struct kurma_fsal_export
{
	struct fsal_export export;
	char *export_path;
	struct kurma_fsal_obj_handle *root_handle;
};

fsal_status_t kurma_lookup_path(struct fsal_export *exp_hdl, const char *path,
				struct fsal_obj_handle **handle);

fsal_status_t kurma_create_handle(struct fsal_export *exp_hdl,
				  struct gsh_buffdesc *hdl_desc,
				  struct fsal_obj_handle **handle);

/*
 * KURMAFS internal object handle
 * opaque is a pointer because
 *  a) the last element of file_handle is a char[] meaning variable len...
 *  b) we cannot depend on it *always* being last or being the only
 *     variable sized struct here...  a pointer is safer.
 */
struct kurma_fsal_obj_handle
{
	struct fsal_obj_handle obj_handle;
	char *opaque; /* opaque part of file_handle_v4 */
	uint32_t block_shift;
	ObjectID *oid;
	fsal_openflags_t openflags;
	struct kurma_fsal_obj_handle *parent; /* used for debug, to be removed*/
	char *name; /* used for debug, to be removed */
	int32_t snapshot_id;    /* 0: non-snapshot files; 1: snapshot dir; >1:
				   snapshots */
	int32_t snapshot_count; /* for snapshot dir only: -1 means unknown */
	time_t attrs_timestamp;  /* protected by obj_handle->lock */
};

#define SNAPSHOT_NONE 0
#define SNAPSHOT_DIR 1

void kurma_try_update_attributes(struct kurma_fsal_obj_handle *khdl,
				 const struct attrlist *attrs);

static inline bool is_snapshot_dir(int snapshot_id)
{
	return snapshot_id == SNAPSHOT_DIR;
}

static inline bool is_snapshot_obj(int snapshot_id)
{
	return snapshot_id != SNAPSHOT_NONE;
}

static inline bool is_snapshot_file(int snapshot_id)
{
	return snapshot_id != SNAPSHOT_NONE && snapshot_id != SNAPSHOT_DIR;
}

int kurma_fsal_open(struct kurma_fsal_obj_handle *, int, fsal_errors_t *);
int kurma_fsal_readlink(struct kurma_fsal_obj_handle *, fsal_errors_t *);

static inline bool kurma_unopenable_type(object_file_type_t type)
{
	if ((type == SOCKET_FILE) || (type == CHARACTER_FILE) ||
	    (type == BLOCK_FILE)) {
		return true;
	} else {
		return false;
	}
}

/* I/O management */
fsal_status_t kurma_open(struct fsal_obj_handle *obj_hdl,
			 fsal_openflags_t openflags);
fsal_openflags_t kurma_status(struct fsal_obj_handle *obj_hdl);
fsal_status_t kurma_read(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			 size_t buffer_size, void *buffer, size_t *read_amount,
			 bool *end_of_file);
fsal_status_t kurma_write(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			  size_t buffer_size, void *buffer,
			  size_t *write_amount, bool *fsal_stable);
fsal_status_t kurma_commit(struct fsal_obj_handle *obj_hdl, /* sync */
			   off_t offset, size_t len);
fsal_status_t kurma_lock_op(struct fsal_obj_handle *obj_hdl, void *p_owner,
			    fsal_lock_op_t lock_op,
			    fsal_lock_param_t *request_lock,
			    fsal_lock_param_t *conflicting_lock);
fsal_status_t kurma_share_op(struct fsal_obj_handle *obj_hdl, void *p_owner,
			     fsal_share_param_t request_share);
fsal_status_t kurma_close(struct fsal_obj_handle *obj_hdl);
fsal_status_t kurma_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				lru_actions_t requests);

/* extended attributes management */
fsal_status_t
kurma_list_ext_attrs(struct fsal_obj_handle *obj_hdl, unsigned int cookie,
		     fsal_xattrent_t *xattrs_tab, unsigned int xattrs_tabsize,
		     unsigned int *p_nb_returned, int *end_of_list);
fsal_status_t kurma_getextattr_id_by_name(struct fsal_obj_handle *obj_hdl,
					  const char *xattr_name,
					  unsigned int *pxattr_id);
fsal_status_t kurma_getextattr_value_by_name(struct fsal_obj_handle *obj_hdl,
					     const char *xattr_name,
					     caddr_t buffer_addr,
					     size_t buffer_size,
					     size_t *p_output_size);
fsal_status_t kurma_getextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					   unsigned int xattr_id,
					   caddr_t buffer_addr,
					   size_t buffer_size,
					   size_t *p_output_size);
fsal_status_t kurma_setextattr_value(struct fsal_obj_handle *obj_hdl,
				     const char *xattr_name,
				     caddr_t buffer_addr, size_t buffer_size,
				     int create);
fsal_status_t kurma_setextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					   unsigned int xattr_id,
					   caddr_t buffer_addr,
					   size_t buffer_size);
fsal_status_t kurma_getextattr_attrs(struct fsal_obj_handle *obj_hdl,
				     unsigned int xattr_id,
				     struct attrlist *p_attrs);
fsal_status_t kurma_remove_extattr_by_id(struct fsal_obj_handle *obj_hdl,
					 unsigned int xattr_id);
fsal_status_t kurma_remove_extattr_by_name(struct fsal_obj_handle *obj_hdl,
					   const char *xattr_name);

void kurma_handle_ops_init(struct fsal_obj_ops *ops);

/* Internal KURMAFS method linkage to export object
 */

fsal_status_t kurma_create_export(struct fsal_module *fsal_hdl,
				  void *parse_node,
				  const struct fsal_up_vector *up_ops);

/* kurma fs */

static inline uint64_t get_filesize(struct kurma_fsal_obj_handle *hdl)
{
	return hdl->obj_handle.attributes.filesize;
}

// void kurma_attrs_to_fsal(ObjectAttributes *k_attrs, struct attrlist *attrs);

static inline fsal_status_t kurma_to_fsal_status(kurma_st st)
{
	fsal_status_t fsal_st;

	switch (st) {
	// TODO ERR_FSAL_EXIST case
	case KURMA_OKAY:
		fsal_st.major = ERR_FSAL_NO_ERROR;
		fsal_st.minor = 0;
		break;
	case KURMA_ERROR_OBJECT_NOT_FOUND:
		fsal_st.major = ERR_FSAL_NOENT;
		fsal_st.minor = ENOENT;
		break;
	case KURMA_ERROR_OBJECTID_INVALID:
		fsal_st.major = ERR_FSAL_STALE;
		fsal_st.minor = ESTALE;
		break;
	case KURMA_ERROR_DIRECTORY_NOT_EMPTY:
		fsal_st.major = ERR_FSAL_NOTEMPTY;
		fsal_st.minor = ENOTEMPTY;
		break;
	case KURMA_ERROR_PERMISSION_DENIED:
		fsal_st.major = ERR_FSAL_PERM;
		fsal_st.minor = EPERM;
		break;
	case KURMA_ERROR_SERVER_ERROR:
		fsal_st.major = ERR_FSAL_FAULT;
		fsal_st.minor = EFAULT;
		break;
	case KURMA_ERROR_FILE_ALREADY_EXISTS:
		fsal_st.major = ERR_FSAL_EXIST;
		fsal_st.minor = EEXIST;
		break;
	case KURMA_ERROR_INVALID_OPERATION:
		fsal_st.major = ERR_FSAL_INVAL;
		fsal_st.minor = EINVAL;
		break;
	case KURMA_ERROR_NOT_DIRECTORY:
		fsal_st.major = ERR_FSAL_NOTDIR;
		fsal_st.minor = ENOTDIR;
		break;
	default:
		fsal_st.major = ERR_FSAL_IO;
		fsal_st.minor = st; /* for debug */
	}

	return fsal_st;
}

#endif
