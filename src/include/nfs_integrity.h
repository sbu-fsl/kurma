/*
 * vim:noexpandtab:shiftwidth=8:tabstop=8:
 *
 * Copyright (C) Stony Brook University 2014
 * by Ming Chen <v.mingchen@gmail.com>
 */

/**
 * @file    nfs_integrity.h
 * @brief   NFS end-to-end integrity routines
 *
 */

#ifndef _NFS_INTEGRITY_H
#define _NFS_INTEGRITY_H

#include "nfsv41.h"
#include "nfs_dix.h"
#include "fsal_api.h"

static inline char* io_info_to_pi_data(struct io_info *info) {
	switch (info->io_content.what) {
	case NFS4_CONTENT_DATA:
		return NULL;
	case NFS4_CONTENT_PROTECTED_DATA:
		return info->io_content.pdata.pd_info.pd_info_val;
	case NFS4_CONTENT_PROTECT_INFO:
		return info->io_content.pinfo.pi_data.pi_data_val;
	default:
		LogFatal(COMPONENT_FSAL, "unsupported io_content type: %d",
			 info->io_content.what);
		return 0;
	}
}

static inline off_t io_info_to_offset(struct io_info *info) {
	switch (info->io_content.what) {
	case NFS4_CONTENT_DATA:
		return info->io_content.data.d_offset;
	case NFS4_CONTENT_PROTECTED_DATA:
		return info->io_content.pdata.pd_offset;
	case NFS4_CONTENT_PROTECT_INFO:
		return info->io_content.pinfo.pi_offset;
	default:
		LogFatal(COMPONENT_FSAL, "unsupported io_content type: %d",
			 info->io_content.what);
		return 0;
	}
}

static inline size_t io_info_to_pi_dlen(struct io_info *info) {
	switch (info->io_content.what) {
	case NFS4_CONTENT_DATA:
		return 0;
	case NFS4_CONTENT_PROTECTED_DATA:
		return info->io_content.pdata.pd_info.pd_info_len;
	case NFS4_CONTENT_PROTECT_INFO:
		return info->io_content.pinfo.pi_data.pi_data_len;
	default:
		LogFatal(COMPONENT_FSAL, "unsupported io_content type: %d",
			 info->io_content.what);
		return 0;
	}
}

static inline size_t io_info_to_file_dlen(struct io_info *info) {
	switch (info->io_content.what) {
	case NFS4_CONTENT_DATA:
		return info->io_content.data.d_data.data_len;
	case NFS4_CONTENT_PROTECTED_DATA:
		return info->io_content.pdata.pd_data.pd_data_len;
	default:
		LogFatal(COMPONENT_FSAL, "unsupported io_content type: %d",
			 info->io_content.what);
		return 0;
	}
}

static inline char* io_info_to_file_data(struct io_info *info) {
	switch (info->io_content.what) {
	case NFS4_CONTENT_DATA:
		return info->io_content.data.d_data.data_val;
	case NFS4_CONTENT_PROTECTED_DATA:
		return info->io_content.pdata.pd_data.pd_data_val;
	default:
		LogFatal(COMPONENT_FSAL, "unsupported io_content type: %d",
			 info->io_content.what);
		return 0;
	}
}

/* initialize io_info for NFS4_CONTENT_PROTECTED_DATA */
static inline void io_info_set_content(struct io_info *info, uint64_t offset,
				       size_t pi_size, void *pi_buf,
				       size_t pd_size, void *pd_buf) {
        info->io_content.what = NFS4_CONTENT_PROTECTED_DATA;
        info->io_content.pdata.pd_type.pi_type = NFS_PI_TYPE5;
        info->io_content.pdata.pd_type.pi_other_data = 1;
        info->io_content.pdata.pd_offset = offset;
        info->io_content.pdata.pd_allocated = 1;
        info->io_content.pdata.pd_info.pd_info_len = pi_size;
        info->io_content.pdata.pd_info.pd_info_val = pi_buf;
        info->io_content.pdata.pd_data.pd_data_len = pd_size;
        info->io_content.pdata.pd_data.pd_data_val = pd_buf;
}

static inline void io_info_set_content_data(struct io_info *info,
					    uint64_t offset, size_t data_size,
					    void *data)
{
	info->io_content.what = NFS4_CONTENT_DATA;
	info->io_content.data.d_offset = offset;
	info->io_content.data.d_allocated = 1;
	info->io_content.data.d_data.data_val = data;
	info->io_content.data.d_data.data_len = data_size;
}

static inline void dump_pi_buf(uint8_t *pi_buf, size_t pi_size)
{
	char *pi_hex, *curr;
	int i, hex_len;
	hex_len = pi_size * 2 + pi_size / 8;
	pi_hex = gsh_malloc(hex_len);
	for (i = 0, curr = pi_hex; i < pi_size; i++) {
		sprintf(curr, "%02x", *(pi_buf + i));
		curr += 2;
		if (i % 8 == 7) {
			*curr = ' ';
			curr += 1;
		}
	}
	*(curr - 1) = '\0';
	LogDebug(COMPONENT_FSAL, "=secnfs=pi_buf: %s", pi_hex);
	gsh_free(pi_hex);
}

#endif				/* _NFS_INTEGRITY_H */
