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
/* Methods for cache handling
 */
#ifndef PCACHEFS_CACHEHANDLE_H
#define PCACHEFS_CACHEHANDLE_H

#include "bsd-base64.h"
#include "pcachefs_methods.h"

/* Write back to server
 */
void writeback_thread(struct fsal_obj_handle *obj_hdl);

fsal_status_t cachefs_read(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			   size_t buffer_size, void *buffer,
			   size_t *read_amount, bool *end_of_file, bool full);

fsal_status_t cachefs_write(struct fsal_obj_handle *obj_hdl, uint64_t offset,
			    size_t buffer_size, void *buffer,
			    size_t *write_amount, bool *fsal_stable);

fsal_status_t cachefs_commit(struct fsal_obj_handle *obj_hdl, /* sync */
			     off_t offset, size_t len);

fsal_status_t cachefs_file_unlink(struct fsal_obj_handle *dir_hdl,
				  const char *name);

fsal_status_t cachefs_getattrs(struct fsal_obj_handle *obj_hdl);

fsal_status_t cachefs_setattrs(struct fsal_obj_handle *obj_hdl,
			       struct attrlist *attrs);

fsal_status_t cachefs_lookup_path(struct fsal_export *exp_hdl, const char *path,
				  struct fsal_obj_handle **handle);

fsal_status_t cachefs_close(struct fsal_obj_handle *handle);

fsal_status_t cachefs_open(struct fsal_obj_handle *obj_hdl,
			   fsal_openflags_t openflags);

fsal_status_t cachefs_create(struct fsal_obj_handle *dir_hdl, const char *name,
			     struct attrlist *attrib,
			     struct fsal_obj_handle **handle);

fsal_status_t cachefs_makedir(struct fsal_obj_handle *dir_hdl, const char *name,
			      struct attrlist *attrib,
			      struct fsal_obj_handle **handle);

fsal_status_t cachefs_makenode(struct fsal_obj_handle *dir_hdl,
			       const char *name, object_file_type_t nodetype,
			       fsal_dev_t *dev, /* IN */
			       struct attrlist *attrib,
			       struct fsal_obj_handle **handle);

fsal_status_t cachefs_symlink(struct fsal_obj_handle *dir_hdl, const char *name,
			      const char *link_path, struct attrlist *attrib,
			      struct fsal_obj_handle **handle);

fsal_status_t cachefs_create_handle(struct fsal_export *exp_hdl,
				    struct gsh_buffdesc *hdl_desc,
				    struct fsal_obj_handle **handle);

void cachefs_release(struct fsal_obj_handle *obj_hdl);

fsal_status_t cachefs_lookup(struct fsal_obj_handle *parent, const char *path,
			     struct fsal_obj_handle **handle);
// constants 16MB
#define PCACHE_MAXWRITE 16777216
#define PCACHE_MAXREAD 16777216
#define MB10 (10 << 20)

#endif
