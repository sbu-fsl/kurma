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
/* Utilities
 */
#ifndef AVFS_UTILS_H
#define AVFS_UTILS_H

#include <stdint.h>
#include <stdio.h>
#include <fsal_api.h>

static const char *const prefix = "temp_";
static const int filename_len = sizeof(prefix)
    + 48 /* fsid 128 bits and inode number 64 bits in hex*/ + 1;

struct temp_file_t {
    char *_filename;
    FILE *_fd;
    bool _eof;
    int _error;
};

int temp_file_init(struct temp_file_t *obj, struct fsal_obj_handle *obj_hdl) {
    obj->_fd = NULL;
    obj->_filename = (char*) gsh_malloc(sizeof(char) * filename_len);
    obj->_eof = false;
    obj->_error = 0;
    if(!obj->_filename) {
            LogCrit(COMPONENT_FSAL, "Out of memory");
            return -1;
    }
    sprintf(obj->_filename, "%s%lx%lx%lx", prefix, obj_hdl->attributes.fsid.major,
            obj_hdl->attributes.fsid.minor, obj_hdl->attributes.fileid);
    return 0;
}

int temp_file_destroy(struct temp_file_t *obj) {
    gsh_free(obj->_filename);
    if(obj->_fd) {
        fclose(obj->_fd);
    }
}

int temp_file_exists(struct temp_file_t *obj) {
    FILE *file;
    if(file = fopen(obj->_filename, "r")) {
        fclose(file);
        return 0;
    }
    LogDebug(COMPONENT_FSAL, "File does not exist");
    return -1;
}

int temp_file_exists_open(struct temp_file_t *obj) {
    if(obj->_fd = fopen(obj->_filename, "r+")) {
        return 0;
    }
    LogDebug(COMPONENT_FSAL, "File does not exist");
    return -1;
}

int temp_file_create_new(struct temp_file_t *obj) {
    if(obj->_fd = fopen(obj->_filename, "w+")) {
        return 0;
    }
    LogDebug(COMPONENT_FSAL, "Failed to create new file");
    return -1;
}

size_t temp_file_read(struct temp_file_t *obj, void *ptr, size_t size) {
    if(!obj->_fd) {
        return 0;
    }
    obj->_error = 0;
    obj->_eof = false;
    size_t bytes_read = 0;
    size_t bytes_left = size;
    while(bytes_left) {
        clearerr(obj->_fd);
        size_t b = fread(ptr, 1, bytes_left, obj->_fd);
        bytes_read += b;
        bytes_left -= b;
        ptr += b;
        if(feof(obj->_fd)) {
            obj->_eof = true;
            obj->_error = ferror(obj->_fd);
            break;
        }
        if(obj->_error = ferror(obj->_fd)) {
            break;
        }
    }
    return bytes_read;
}

size_t temp_file_write(struct temp_file_t *obj, const void *ptr, size_t size) {
    if(!obj->_fd) {
        return 0;
    }
    obj->_error = 0;
    size_t bytes_written = 0;
    size_t bytes_left = size;
    while(bytes_left) {
        clearerr(obj->_fd);
        size_t b = fwrite(ptr, 1, bytes_left, obj->_fd);
        bytes_written += b;
        bytes_left -= b;
        ptr += b;
        if(obj->_error = ferror(obj->_fd)) {
            LogCrit(COMPONENT_FSAL, "File write failed with error %d", obj->_error);
            break;
        }
    }
    return bytes_written;
}

int temp_file_seek(struct temp_file_t *obj, long offset, int whence) {
    if(!obj->_fd) {
        return 0;
    }
    obj->_error = 0;
    int ret = fseek(obj->_fd, offset, whence);
    if(ret == -1) {
        obj->_error = errno;
        LogCrit(COMPONENT_FSAL, "File seek failed with error %d", errno);
    }
    return ret;
}

void temp_file_close(struct temp_file_t *obj) {
    fclose(obj->_fd);
    obj->_fd = NULL;
}

size_t temp_file_size(struct temp_file_t *obj) {
    if(!obj->_fd) {
        return 0;
    }
    size_t cur_pos = ftell(obj->_fd);
    fseek(obj->_fd, 0L, SEEK_END);
    size_t file_size = ftell(obj->_fd);
    fseek(obj->_fd, cur_pos, SEEK_SET);
    return file_size;
}

#endif // AVFS_UTILS_H
