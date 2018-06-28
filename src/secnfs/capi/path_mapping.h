// Copyright (c) 2013-2018 Ming Chen
// Copyright (c) 2016-2016 Praveen Kumar Morampudi
// Copyright (c) 2016-2016 Harshkumar Patel
// Copyright (c) 2017-2017 Rushabh Shah
// Copyright (c) 2013-2014 Arun Olappamanna Vasudevan 
// Copyright (c) 2013-2014 Kelong Wang
// Copyright (c) 2013-2018 Erez Zadok
// Copyright (c) 2013-2018 Stony Brook University
// Copyright (c) 2013-2018 The Research Foundation for SUNY
// This file is released under the GPL.
// @file  path_mapping.h
// @brief The C interface of PathMapping to NFS-Ganesha code.

#pragma once

#include <stdlib.h>

#include "capi/common.h"
#include "util/slice.h"

#ifdef __cplusplus
extern "C" {
#endif

// Return 0 if success, or a negative error code.
int pm_init();

// Insert a mapping between an file object's full path and its handle.
// @path [IN]: the full path of the inserted object
// @fh [IN]: file handle of the object
int pm_insert(const char* path, const_buffer_t fh);

// Insert a named object into a directory.
// @dir_fh [IN]: NFS file handle of the parent directory
// @name [IN]: the name of the inserted object
// @fh [IN]: NFS file handle of the inserted object
// @path [OUT]: full path of the inserted file object
// Return 0 if success, or a negative error code.
int pm_insert_at(const_buffer_t dir_fh, const char* name, const_buffer_t fh,
                 buffer_t* path);

// Delete a name from a directory.
// @dir_fh [IN]: NFS file handle of the parent directory
// @name [IN]: the name of the deleted object
// @path [OUT]: full path of the deleted file object
// Return 0 if success, or a negative error code.
int pm_delete(const_buffer_t dir_fh, const char* name, buffer_t* path);

// Lookup the NFS file handle of a named object.
// @dir_fh [IN]: NFS file handle of the parent directory
// @name [IN]: the name of target object
// @result_fh [OUT]: NFS file handle of the object found
// Return 0 if success, or a negative error code.
int pm_lookup_handle(const_buffer_t dir_fh, const char* name,
                     buffer_t* result_fh);

// Translate a path to a NFS file handle.
// @path [IN]: full path
// @result_fh [OUT]: the translated NFS file handle
// Returns 0 if success, or a negative error code.
int pm_path_to_handle(const char* path, buffer_t* result_fh);

// Translate a NFS file handle to path(s).
// @fh [IN]: full path
// @npath [IN]: the number of buffer allocated
// @paths [OUT]: the translated path(s)
// Return the number of paths filled into the array of buffer, or a negative
// error code.
int pm_handle_to_paths(const_buffer_t fh, size_t npath, buffer_t* paths);

void pm_destroy();

#ifdef __cplusplus
}
#endif

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
