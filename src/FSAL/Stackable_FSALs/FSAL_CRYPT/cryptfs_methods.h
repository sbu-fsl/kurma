/*
 * vim:expandtab:shiftwidth=8:tabstop=8:
 * CRYPTFS methods for handles
 */

#include "fsal_handle_syscalls.h"
struct cryptfs_fsal_obj_handle;

typedef struct cryptfs_file_handle_ {
	int nothing;
} cryptfs_file_handle_t;

struct cryptfs_exp_handle_ops {
	int (*vex_open_by_handle) (struct fsal_export * exp,
				   cryptfs_file_handle_t * fh, int openflags,
				   fsal_errors_t * fsal_error);
	int (*vex_name_to_handle) (int fd, const char *name,
				   cryptfs_file_handle_t * fh);
	int (*vex_fd_to_handle) (int fd, cryptfs_file_handle_t * fh);
	int (*vex_readlink) (struct cryptfs_fsal_obj_handle *, fsal_errors_t *);
};

struct next_ops {
	struct export_ops *exp_ops;	/*< Vector of operations */
	struct fsal_obj_ops *obj_ops;	/*< Shared handle methods vector */
	struct fsal_ds_ops *ds_ops;	/*< Shared handle methods vector */
	struct fsal_up_vector *up_ops;	/*< Upcall operations */
};

/*
 * CRYPTFS internal export
 */
struct cryptfs_fsal_export {
	struct fsal_export export;
};

fsal_status_t cryptfs_lookup_path(struct fsal_export *exp_hdl,
				 const struct req_op_context *opctx,
				 const char *path,
				 struct fsal_obj_handle **handle);

fsal_status_t cryptfs_create_handle(struct fsal_export *exp_hdl,
				   const struct req_op_context *opctx,
				   struct gsh_buffdesc *hdl_desc,
				   struct fsal_obj_handle **handle);

/*
 * CRYPTFS internal object handle
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

struct cryptfs_fsal_obj_handle {
	struct fsal_obj_handle obj_handle;
	cryptfs_file_handle_t *handle;
	union {
		struct {
			int fd;
			fsal_openflags_t openflags;
		} file;
		struct {
			unsigned char *link_content;
			int link_size;
		} symlink;
		struct {
			cryptfs_file_handle_t *dir;
			char *name;
		} unopenable;
	} u;
};

int cryptfs_fsal_open(struct cryptfs_fsal_obj_handle *, int, fsal_errors_t *);
int cryptfs_fsal_readlink(struct cryptfs_fsal_obj_handle *, fsal_errors_t *);

static inline bool cryptfs_unopenable_type(object_file_type_t type)
{
	if ((type == SOCKET_FILE) || (type == CHARACTER_FILE)
	    || (type == BLOCK_FILE)) {
		return true;
	} else {
		return false;
	}
}

	/* I/O management */
fsal_status_t cryptfs_open(struct fsal_obj_handle * obj_hdl,
			  const struct req_op_context * opctx,
			  fsal_openflags_t openflags);
fsal_openflags_t cryptfs_status(struct fsal_obj_handle *obj_hdl);
fsal_status_t cryptfs_read(struct fsal_obj_handle *obj_hdl,
			  const struct req_op_context *opctx, uint64_t offset,
			  size_t buffer_size, void *buffer,
			  size_t * read_amount, bool * end_of_file);
fsal_status_t cryptfs_write(struct fsal_obj_handle *obj_hdl,
			   const struct req_op_context *opctx, uint64_t offset,
			   size_t buffer_size, void *buffer,
			   size_t * write_amount, bool * fsal_stable);
fsal_status_t cryptfs_commit(struct fsal_obj_handle *obj_hdl,	/* sync */
			    off_t offset, size_t len);
fsal_status_t cryptfs_lock_op(struct fsal_obj_handle *obj_hdl,
			     const struct req_op_context *opctx, void *p_owner,
			     fsal_lock_op_t lock_op,
			     fsal_lock_param_t * request_lock,
			     fsal_lock_param_t * conflicting_lock);
fsal_status_t cryptfs_share_op(struct fsal_obj_handle *obj_hdl, void *p_owner,	/* IN (opaque to FSAL) */
			      fsal_share_param_t request_share);
fsal_status_t cryptfs_close(struct fsal_obj_handle *obj_hdl);
fsal_status_t cryptfs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
				 lru_actions_t requests);

/* extended attributes management */
fsal_status_t cryptfs_list_ext_attrs(struct fsal_obj_handle *obj_hdl,
				    const struct req_op_context *opctx,
				    unsigned int cookie,
				    fsal_xattrent_t * xattrs_tab,
				    unsigned int xattrs_tabsize,
				    unsigned int *p_nb_returned,
				    int *end_of_list);
fsal_status_t cryptfs_getextattr_id_by_name(struct fsal_obj_handle *obj_hdl,
					   const struct req_op_context *opctx,
					   const char *xattr_name,
					   unsigned int *pxattr_id);
fsal_status_t cryptfs_getextattr_value_by_name(struct fsal_obj_handle *obj_hdl,
					      const struct req_op_context
					      *opctx, const char *xattr_name,
					      caddr_t buffer_addr,
					      size_t buffer_size,
					      size_t * p_output_size);
fsal_status_t cryptfs_getextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					    const struct req_op_context *opctx,
					    unsigned int xattr_id,
					    caddr_t buffer_addr,
					    size_t buffer_size,
					    size_t * p_output_size);
fsal_status_t cryptfs_setextattr_value(struct fsal_obj_handle *obj_hdl,
				      const struct req_op_context *opctx,
				      const char *xattr_name,
				      caddr_t buffer_addr, size_t buffer_size,
				      int create);
fsal_status_t cryptfs_setextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					    const struct req_op_context *opctx,
					    unsigned int xattr_id,
					    caddr_t buffer_addr,
					    size_t buffer_size);
fsal_status_t cryptfs_getextattr_attrs(struct fsal_obj_handle *obj_hdl,
				      const struct req_op_context *opctx,
				      unsigned int xattr_id,
				      struct attrlist *p_attrs);
fsal_status_t cryptfs_remove_extattr_by_id(struct fsal_obj_handle *obj_hdl,
					  const struct req_op_context *opctx,
					  unsigned int xattr_id);
fsal_status_t cryptfs_remove_extattr_by_name(struct fsal_obj_handle *obj_hdl,
					    const struct req_op_context *opctx,
					    const char *xattr_name);
