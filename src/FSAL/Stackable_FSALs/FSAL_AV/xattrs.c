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
/* xattrs.c
 * AV object (file|dir) handle object extended attributes
 */

#include "config.h"

#include "fsal.h"
#include <libgen.h>		/* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <sys/types.h>
#include <os/xattr.h>
#include <ctype.h>
#include "ganesha_list.h"
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "avfs_methods.h"

fsal_status_t avfs_list_ext_attrs(struct fsal_obj_handle *obj_hdl,
				    unsigned int argcookie,
				    fsal_xattrent_t *xattrs_tab,
				    unsigned int xattrs_tabsize,
				    unsigned int *p_nb_returned,
				    int *end_of_list)
{
	return next_ops.obj_ops->list_ext_attrs(obj_hdl, argcookie,
						xattrs_tab, xattrs_tabsize,
						p_nb_returned, end_of_list);
}

fsal_status_t avfs_getextattr_id_by_name(struct fsal_obj_handle *obj_hdl,
					   const char *xattr_name,
					   unsigned int *pxattr_id)
{
	return next_ops.obj_ops->getextattr_id_by_name(obj_hdl,
						       xattr_name, pxattr_id);
}

fsal_status_t avfs_getextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					    unsigned int xattr_id,
					    caddr_t buffer_addr,
					    size_t buffer_size,
					    size_t *p_output_size)
{
	return next_ops.obj_ops->getextattr_value_by_id(obj_hdl,
							xattr_id, buffer_addr,
							buffer_size,
							p_output_size);
}

fsal_status_t avfs_getextattr_value_by_name(struct fsal_obj_handle *obj_hdl,
					      const char *xattr_name,
					      caddr_t buffer_addr,
					      size_t buffer_size,
					      size_t *p_output_size)
{
	return next_ops.obj_ops->getextattr_value_by_name(obj_hdl,
							  xattr_name,
							  buffer_addr,
							  buffer_size,
							  p_output_size);
}

fsal_status_t avfs_setextattr_value(struct fsal_obj_handle *obj_hdl,
				      const char *xattr_name,
				      caddr_t buffer_addr, size_t buffer_size,
				      int create)
{
	return next_ops.obj_ops->setextattr_value(obj_hdl, xattr_name,
						  buffer_addr, buffer_size,
						  create);
}

fsal_status_t avfs_setextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					    unsigned int xattr_id,
					    caddr_t buffer_addr,
					    size_t buffer_size)
{
	return next_ops.obj_ops->setextattr_value_by_id(obj_hdl,
							xattr_id, buffer_addr,
							buffer_size);
}

fsal_status_t avfs_getextattr_attrs(struct fsal_obj_handle *obj_hdl,
				      unsigned int xattr_id,
				      struct attrlist *p_attrs)
{
	return next_ops.obj_ops->getextattr_attrs(obj_hdl, xattr_id,
						  p_attrs);
}

fsal_status_t avfs_remove_extattr_by_id(struct fsal_obj_handle *obj_hdl,
					  unsigned int xattr_id)
{
	return next_ops.obj_ops->remove_extattr_by_id(obj_hdl, xattr_id);
}

fsal_status_t avfs_remove_extattr_by_name(struct fsal_obj_handle *obj_hdl,
					    const char *xattr_name)
{
	return next_ops.obj_ops->remove_extattr_by_name(obj_hdl,
							xattr_name);
}
