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

/* xattrs.c
 * KURMA object (file|dir) handle object extended attributes
 */

#include "config.h"

#include "fsal.h"
#include <libgen.h> /* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <sys/types.h>
#include <os/xattr.h>
#include <ctype.h>
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "kurma_methods.h"

fsal_status_t
kurma_list_ext_attrs(struct fsal_obj_handle *obj_hdl, unsigned int argcookie,
		     fsal_xattrent_t *xattrs_tab, unsigned int xattrs_tabsize,
		     unsigned int *p_nb_returned, int *end_of_list)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_getextattr_id_by_name(struct fsal_obj_handle *obj_hdl,
					  const char *xattr_name,
					  unsigned int *pxattr_id)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_getextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					   unsigned int xattr_id,
					   caddr_t buffer_addr,
					   size_t buffer_size,
					   size_t *p_output_size)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_getextattr_value_by_name(struct fsal_obj_handle *obj_hdl,
					     const char *xattr_name,
					     caddr_t buffer_addr,
					     size_t buffer_size,
					     size_t *p_output_size)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_setextattr_value(struct fsal_obj_handle *obj_hdl,
				     const char *xattr_name,
				     caddr_t buffer_addr, size_t buffer_size,
				     int create)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_setextattr_value_by_id(struct fsal_obj_handle *obj_hdl,
					   unsigned int xattr_id,
					   caddr_t buffer_addr,
					   size_t buffer_size)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_getextattr_attrs(struct fsal_obj_handle *obj_hdl,
				     unsigned int xattr_id,
				     struct attrlist *attrs)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_remove_extattr_by_id(struct fsal_obj_handle *obj_hdl,
					 unsigned int xattr_id)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

fsal_status_t kurma_remove_extattr_by_name(struct fsal_obj_handle *obj_hdl,
					   const char *xattr_name)
{
	/* KURMAFS doesn't support xattr */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}
