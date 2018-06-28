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
#ifndef WB_MGR_H
#define WB_MGR_H
#include "avltree.h"
#include "ganesha_list.h"
#include "fsal.h"
#include "time.h"
#include "capi/common.h"

typedef void (*writebackfunc)(struct fsal_obj_handle *obj_hdl);

fsal_status_t register_wb_file(struct fsal_obj_handle *obj_hdl);

fsal_status_t deregister_wb_file(struct fsal_obj_handle *obj_hdl);

fsal_status_t wb_mgr_init(writebackfunc wb);

fsal_status_t wb_mgr_destroy();

#endif // WB_MGR_H
