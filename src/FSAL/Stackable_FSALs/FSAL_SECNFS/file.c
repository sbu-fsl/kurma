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
/* file.c
 * File I/O methods for SECNFS module
 */

#include "config.h"

#include <assert.h>
#include "fsal.h"
#include "FSAL/access_check.h"
#include "fsal_convert.h"
#include <unistd.h>
#include <fcntl.h>
#include "FSAL/fsal_commonlib.h"
#include "secnfs_methods.h"
#include "fsal_handle_syscalls.h"
#include "secnfs.h"

extern struct next_ops next_ops;

static bool should_read_header(const struct secnfs_fsal_obj_handle *hdl)
{
        return hdl->obj_handle.type == REGULAR_FILE
                && hdl->obj_handle.attributes.filesize > 0
                && !hdl->key_initialized;
}


/** secnfs_open
 * called with appropriate locks taken at the cache inode level
 */
fsal_status_t secnfs_open(struct fsal_obj_handle *obj_hdl,
                          fsal_openflags_t openflags)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);
        fsal_status_t st;

        SECNFS_D("hdl = %x; openflag = %d\n", hdl, openflags);
        __sync_fetch_and_add(&sn_counters.nr_opens, 1);

        st = next_ops.obj_ops->open(hdl->next_handle, openflags);

        if (!FSAL_IS_ERROR(st) && should_read_header(hdl)) {
                // read file key, iv and meta
                SECNFS_D("hdl = %x; reading header\n", hdl);
                st = read_header(obj_hdl);
        }

        return st;
}


/* secnfs_status
 * Let the caller peek into the file's open/close state.
 */
fsal_openflags_t secnfs_status(struct fsal_obj_handle *obj_hdl)
{
        return next_ops.obj_ops->status(next_handle(obj_hdl));
}


/* do_aligned_read for regular file
 * concurrency (locks) is managed by caller
 */
fsal_status_t do_aligned_read(struct secnfs_fsal_obj_handle *hdl,
                              uint64_t offset_align, size_t size_align,
                              void *buffer_align, size_t *read_amount,
                              bool *end_of_file)
{
        uint64_t next_offset; /* include file header */
        struct io_info info;
        struct secnfs_dif secnfs_dif;
        uint8_t *secnfs_dif_buf = NULL;
        uint8_t version_buf[8];
        void *pi_buf = NULL; /* protection information */
        size_t pi_size;
        fsal_status_t st;
        secnfs_s ret;
        int i;

        assert(is_pi_aligned(offset_align));
        assert(is_pi_aligned(size_align));

        next_offset = offset_align + FILE_HEADER_SIZE;

        /* To use read_plus, a struct io_info need be prepared. */
        pi_size = get_pi_size(size_align);
        pi_buf = gsh_malloc(pi_size);
        if (pi_buf == NULL) {
                st = fsalstat(ERR_FSAL_NOMEM, 0);
                goto out;
        }

        io_info_set_content(&info, next_offset,
                            pi_size, pi_buf,
                            size_align, buffer_align);

        st = next_ops.obj_ops->read_plus(hdl->next_handle,
                                         next_offset, size_align,
                                         buffer_align, read_amount,
                                         end_of_file,
                                         &info, NULL);
        if (FSAL_IS_ERROR(st)) {
                SECNFS_D("hdl = %x; read_plus failed: %u", hdl, st.major);
                goto out;
        }

        SECNFS_D("hdl = %x; read_amount = %u", hdl, *read_amount);
        if (*read_amount != pi_round_down(*read_amount)) {
                *read_amount = pi_round_down(*read_amount);
                end_of_file = 0;
        }
        SECNFS_D("hdl = %x; read_amount_align = %u", hdl, *read_amount);
        SECNFS_D("hdl = %x; pd_info_len = %u", hdl,
                 io_info_to_pi_dlen(&info));
        // dump_pi_buf(pi_buf, io_info_to_pi_dlen(&info));

        secnfs_dif_buf = gsh_malloc(PI_SECNFS_DIF_SIZE);
        if (!secnfs_dif_buf) {
                st = fsalstat(ERR_FSAL_NOMEM, 0);
                goto out;
        }

        for (i = 0; i < get_pi_count(*read_amount); i++) {
                extract_from_sd_dif(pi_buf + i * PI_SD_DIF_SIZE, secnfs_dif_buf,
                                    PI_SECNFS_DIF_SIZE, 1);
                secnfs_dif_from_buf(&secnfs_dif, secnfs_dif_buf);
                uint64_to_bytes(version_buf, secnfs_dif.version);

                //SECNFS_D("hdl = %x; ver(%u) = %llx",
                //         hdl, i + (offset_align >> PI_INTERVAL_SHIFT),
                //         secnfs_dif.version);
                SECNFS_D("hdl = %x; tag(%u) = %02x...%02x",
                         hdl, i + (offset_align >> PI_INTERVAL_SHIFT),
                         secnfs_dif.tag[0], secnfs_dif.tag[15]);

                /* may carefully decrypt to user buffer to save memcpy */
                if (hdl->encrypted)
                        ret = secnfs_verify_decrypt(
                                        hdl->fk,
                                        hdl->iv,
                                        offset_align + i * PI_INTERVAL_SIZE,
                                        PI_INTERVAL_SIZE,
                                        buffer_align + i * PI_INTERVAL_SIZE,
                                        VERSION_SIZE,
                                        version_buf,
                                        secnfs_dif.tag,
                                        buffer_align + i * PI_INTERVAL_SIZE,
                                        !hdl->encrypted);
                else /* integrity only */
                        ret = secnfs_mac_verify(
                                        hdl->fk,
                                        hdl->iv,
                                        offset_align + i * PI_INTERVAL_SIZE,
                                        PI_INTERVAL_SIZE,
                                        buffer_align + i * PI_INTERVAL_SIZE,
                                        secnfs_dif.tag);

                /* or return partial buffer ? */
                if (ret != SECNFS_OKAY) {
                        SECNFS_D("hdl = %x; ret(%u) = %d", hdl, i, ret);
                        st = secnfs_to_fsal_status(ret);
                        goto out;
                }
        }

out:
        gsh_free(pi_buf);
        gsh_free(secnfs_dif_buf);

        return st;
}


/* do_aligned_write for regular file
 * concurrency (locks) is managed by caller.
 * write_amount will be truncated (pi_round_down) if not aligned.
 * caller should maintain the file holes.
 */
fsal_status_t do_aligned_write(struct secnfs_fsal_obj_handle *hdl,
                               uint64_t offset_align, size_t size_align,
                               void *plain_align, size_t *write_amount,
                               bool *fsal_stable, struct attrlist *attrs)
{
        struct io_info info;
        uint64_t next_offset;
        size_t pi_size;
        uint8_t *pd_buf;
        uint8_t *pi_buf = NULL;
        uint8_t *secnfs_dif_buf = NULL;
        struct secnfs_dif secnfs_dif = {0};
        uint8_t version_buf[8];
        fsal_status_t st;
        secnfs_s ret;
        int i;

        assert(is_pi_aligned(offset_align));
        assert(is_pi_aligned(size_align));

        SECNFS_D("hdl = %x; do aligned write to %u (%u)\n", hdl,
                 offset_align, size_align);

        next_offset = offset_align + FILE_HEADER_SIZE;

        /* allocate buffer for ciphertext in encryption mode */
        pd_buf = plain_align;
        if (hdl->encrypted) {
                pd_buf = gsh_malloc(size_align + TAG_SIZE);
                if (!pd_buf)
                        return fsalstat(ERR_FSAL_NOMEM, 0);
        }

        /* allocate buffer for protection info (DIF) */
        pi_size = get_pi_size(size_align);
        pi_buf = gsh_malloc(pi_size);
        SECNFS_D("hdl = %x; pi_size = %u\n", hdl, pi_size);
        if (!pi_buf) {
                st = fsalstat(ERR_FSAL_NOMEM, 0);
                goto out;
        }

        /* allocate buffer for serialization of secnfs_dif_t */
        secnfs_dif_buf = gsh_malloc(PI_SECNFS_DIF_SIZE);
        if (!secnfs_dif_buf) {
                st = fsalstat(ERR_FSAL_NOMEM, 0);
                goto out;
        }

        secnfs_dif.version = 0x1234567890abcdef;
        uint64_to_bytes(version_buf, secnfs_dif.version);

        for (i = 0; i < get_pi_count(size_align); i++) {
                if (hdl->encrypted)
                        ret = secnfs_auth_encrypt(
                                        hdl->fk,
                                        hdl->iv,
                                        offset_align + i * PI_INTERVAL_SIZE,
                                        PI_INTERVAL_SIZE,
                                        plain_align + i * PI_INTERVAL_SIZE,
                                        VERSION_SIZE,
                                        version_buf,
                                        pd_buf + i * PI_INTERVAL_SIZE,
                                        secnfs_dif.tag,
                                        !hdl->encrypted);
                else /* integrity only */
                        ret = secnfs_mac_generate(
                                        hdl->fk,
                                        hdl->iv,
                                        offset_align + i * PI_INTERVAL_SIZE,
                                        PI_INTERVAL_SIZE,
                                        plain_align + i * PI_INTERVAL_SIZE,
                                        secnfs_dif.tag);

                if (ret != SECNFS_OKAY) {
                        st = secnfs_to_fsal_status(ret);
                        goto out;
                }

                //SECNFS_D("hdl = %x; ver(%u) = %llx",
                //         hdl, i + (offset_align >> PI_INTERVAL_SHIFT),
                //         secnfs_dif.version);
                SECNFS_D("hdl = %x; tag(%u) = %02x...%02x",
                         hdl, i + (offset_align >> PI_INTERVAL_SHIFT),
                         secnfs_dif.tag[0], secnfs_dif.tag[15]);

                secnfs_dif_to_buf(&secnfs_dif, secnfs_dif_buf);
                fill_sd_dif(pi_buf + i * PI_SD_DIF_SIZE, secnfs_dif_buf,
                            PI_SECNFS_DIF_SIZE, 1);
        }
        // dump_pi_buf(pi_buf, pi_size);

        /* prepare io_info for write_plus */
        io_info_set_content(&info, next_offset,
                            pi_size, pi_buf,
                            size_align, pd_buf);

        st = next_ops.obj_ops->write_plus(hdl->next_handle,
                                          next_offset, size_align,
                                          pd_buf, write_amount,
                                          fsal_stable,
                                          &info, attrs);
        if (FSAL_IS_ERROR(st)) {
                SECNFS_D("hdl = %x; write_plus failed: %u", hdl, st.major);
                /* XXX WORKAROUND for EINVAL kernel bug */
                if (!(st.major == ERR_FSAL_INVAL &&
                        secnfs_range_has_hole(hdl->holes, offset_align,
                                              size_align)))
                        goto out;
        }
        /* XXX WORKAROUND for EINVAL kernel bug */
        if (st.major == ERR_FSAL_INVAL &&
                secnfs_range_has_hole(hdl->holes, offset_align, size_align)) {
                SECNFS_D("hdl = %x; WORKAROUND write-then-write_plus", hdl);
                st = next_ops.obj_ops->write(hdl->next_handle,
                                             next_offset, size_align,
                                             pd_buf, write_amount,
                                             fsal_stable);
                if (FSAL_IS_ERROR(st)) {
                        SECNFS_D("hdl = %x; *write* failed: %u", hdl, st.major);
                        goto out;
                }
                st = next_ops.obj_ops->write_plus(hdl->next_handle,
                                                  next_offset, size_align,
                                                  pd_buf, write_amount,
                                                  fsal_stable,
                                                  &info, attrs);
                if (FSAL_IS_ERROR(st)) {
                        SECNFS_D("hdl = %x; write_plus still failed: %u",
                                 hdl, st.major);
                        goto out;
                }
        }

        *write_amount = pi_round_down(*write_amount);
        SECNFS_D("hdl = %x; write_amount_align = %u", hdl, *write_amount);
        // assert(*write_amount <= size_align);
        if (*write_amount > size_align)
                SECNFS_ERR("hdl = %x; write_amount > size_align", hdl);

out:
        if (hdl->encrypted) gsh_free(pd_buf);
        gsh_free(pi_buf);
        gsh_free(secnfs_dif_buf);

        return st;
}


/* read one remote block at file_offset to dst,
 * fill it with new content (src) at position dst_offset.
 *
 * dst should be large enough to hold one block (PI_INTERVAL_SIZE).
 * caller should hold lock to ensure the consistency.
 */
inline secnfs_s read_modify_one(uint8_t *dst, void *src,
                                uint64_t dst_offset, size_t src_size,
                                uint64_t file_offset,
                                struct secnfs_fsal_obj_handle *hdl)
{
        fsal_status_t st;
        size_t read_amount;
        bool end_of_file;

        assert(dst_offset + src_size <= PI_INTERVAL_SIZE);
        assert(is_pi_aligned(file_offset));

        if (dst_offset == 0 && src_size == PI_INTERVAL_SIZE)
                goto update;

        SECNFS_D("hdl = %x file_offset %zu get_filesize %zu", hdl, file_offset, get_filesize(hdl));

        if (file_offset < get_filesize(hdl) &&
                        !secnfs_offset_in_hole(hdl->holes, file_offset)) {
                SECNFS_D("hdl = %x; READ remote block for update", hdl);
                st = do_aligned_read(hdl, file_offset, PI_INTERVAL_SIZE,
                                     dst, &read_amount, &end_of_file);
                if (FSAL_IS_ERROR(st))
                        return SECNFS_READ_UPDATE_FAIL;

                if (read_amount == 0) {
                        if (end_of_file) /* we can still continue */
                                memset(dst, 0, PI_INTERVAL_SIZE);
                        else             /* probably partial read, abort */
                                return SECNFS_READ_UPDATE_FAIL;
                }
        } else {
                /* extending the file: need not to read, but to fill zero */
                memset(dst, 0, PI_INTERVAL_SIZE);
        }

update:
        memcpy(dst + dst_offset, src, src_size);

        return SECNFS_OKAY;
}


/* Fill the file with zero at position [left, right).
 * Caller should hold file lock or cache entry WRITE lock.
 * For simplicity, 'right' should be aligned.
 * NOTE: Effective file size will not be updated.
 *
 * ASSUMPTION: If filesize is not aligned, the last block is padded with zero.
 */
secnfs_s secnfs_fill_zero(struct secnfs_fsal_obj_handle *hdl,
                          size_t left, size_t right)
{
        size_t left_down;
        size_t size_align;
        size_t filesize_up;
        char *buffer = NULL;
        size_t size;
        size_t buffer_size;
        size_t write_amount;
        size_t fs_maxwrite;
        size_t n;
        bool stable;
        fsal_status_t st;
        secnfs_s ret;

        /* TODO limit the range, e.g., check max_filesize */
        SECNFS_D("hdl = %x; filling zero [%u, %u)", hdl, left, right);

        /* nothing to fill */
        if (left == right)
                return SECNFS_OKAY;

        left_down = pi_round_down(left);
        assert(is_pi_aligned(right));
        assert(left < right);

        filesize_up = pi_round_up(get_filesize(hdl));
        if (left >= get_filesize(hdl)) {
                /* based on assumption, already zero filled */
                if (right == filesize_up)
                        return SECNFS_OKAY;

                /* skip the last block which is already zero */
                if (left < filesize_up) {
                        left = filesize_up;
                        left_down = left;
                }
        }

        size_align = right - left_down;
        /* filling size can be arbitrarily large, allocate fixed buffer */
        fs_maxwrite = op_ctx->fsal_export->ops->fs_maxwrite(op_ctx->fsal_export);
        buffer_size = MIN(MIN(size_align, FILL_ZERO_BUFFER_SIZE), fs_maxwrite);
        buffer = gsh_calloc(1, buffer_size);

        /* need read and modify with zero */
        if (left < get_filesize(hdl)) {
                uint64_t left_moved = left - left_down;

                /* pass NULL to do read without modifying */
                ret = read_modify_one(buffer, NULL,
                                      0, 0,
                                      left_down,
                                      hdl);
                if (ret != SECNFS_OKAY)
                        goto out;

                /* modify with zero */
                memset(buffer + left_moved, 0, PI_INTERVAL_SIZE - left_moved);
        }

        SECNFS_D("hdl = %x; really filling zero [%u, %u)", hdl, left, right);

        write_amount = 0;
        size = buffer_size;
        do {
                st = do_aligned_write(hdl,
                                      left_down + write_amount,
                                      size,
                                      buffer + write_amount % buffer_size,
                                      &n,
                                      &stable,
                                      NULL);
                if (FSAL_IS_ERROR(st)) {
                        SECNFS_ERR("hdl = %x; filling zero failed at %u",
                                   hdl, left_down + write_amount);
                        ret = SECNFS_FILL_ZERO_FAIL;
                        goto out;
                }

                size -= n;
                write_amount += n;

                if (size == 0 && write_amount < size_align) {
                        if (size_align - write_amount >= buffer_size)
                                size = buffer_size;
                        else
                                size = size_align - write_amount;
                        /* buffer will be modified by encrypted aligned_write */
                        memset(buffer, 0, size);
                }
        } while (write_amount < size_align);

        ret = SECNFS_OKAY;

out:
        gsh_free(buffer);

        return ret;
}


/*
 * concurrency (locks) is managed in cache_inode_*
 */
fsal_status_t secnfs_read(struct fsal_obj_handle *obj_hdl,
                          uint64_t offset,
                          size_t buffer_size, void *buffer,
                          size_t *read_amount, bool *end_of_file)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);
        fsal_status_t st;
        uint64_t offset_align;
        uint64_t offset_moved;
        size_t size_align;
        uint64_t hole_off;
        uint64_t hole_len;
        bool should_fill_zero;
        void *buffer_align = NULL;
        bool align;

        SECNFS_D("hdl = %x; read from %u (%u)\n", hdl, offset, buffer_size);

        ops_hist_add(&sn_counters.ops_hist, offset, buffer_size);
        __sync_fetch_and_add(&sn_counters.nr_reads, 1);

	ops_hist_add(&sn_counters.ops_hist, offset, buffer_size);
	__sync_fetch_and_add(&sn_counters.nr_reads, 1);

        if (obj_hdl->type != REGULAR_FILE) {
                return next_ops.obj_ops->read(hdl->next_handle,
                                              offset,
                                              buffer_size, buffer,
                                              read_amount, end_of_file);
        }
        assert(hdl->key_initialized);

        /* skip unnecessary read */
        if (offset >= get_filesize(hdl)) {
                *read_amount = 0;
                *end_of_file = 1;
                return fsalstat(ERR_FSAL_NO_ERROR, 0);
        }

        offset_align = pi_round_down(offset);
        offset_moved = offset - offset_align;
        size_align = pi_round_up(offset + buffer_size) - offset_align;
        align = (offset == offset_align && buffer_size == size_align) ? 1 : 0;
        SECNFS_D("hdl = %x; offset_align = %u, size_align = %u",
                 hdl, offset_align, size_align);

        secnfs_hole_find_next(hdl->holes, offset_align, &hole_off, &hole_len);
        should_fill_zero = 0;
        if (hole_len > 0) {
                if (offset_align >= hole_off) { /* in hole */
                        size_align = MIN(hole_off + hole_len - offset_align,
                                         size_align);
                        should_fill_zero = 1;
                } else { /* read till next hole */
                        size_align = MIN(hole_off - offset_align, size_align);
                }
        }

        buffer_align = align ? buffer : gsh_malloc(size_align);
        if (!buffer_align) {
                st = fsalstat(ERR_FSAL_NOMEM, 0);
                goto out;
        }

        if (should_fill_zero) {
                SECNFS_D("hdl = %x; return hole (size: %u)", hdl, size_align);
                memset(buffer_align, 0, size_align);
                *read_amount = size_align;
                st = fsalstat(ERR_FSAL_NO_ERROR, 0);
        } else {
                st = do_aligned_read(hdl, offset_align, size_align,
                                buffer_align, read_amount, end_of_file);
                if (FSAL_IS_ERROR(st))
                        goto out;
        }

        /* update effective read_amount & EOF to user */
        if (*read_amount > 0) {
                /* check if read completely */
                if (offset_align + *read_amount >= offset + buffer_size)
                        *read_amount = buffer_size;
                else
                        *read_amount = *read_amount - offset_moved;

                PTHREAD_RWLOCK_rdlock(&obj_hdl->lock);
                /* buffer_size may be larger than effective amount */
                if (offset + *read_amount >= get_filesize(hdl)) {
                        *end_of_file = 1;
                        *read_amount = get_filesize(hdl) - offset;
                } else {
                        *end_of_file = 0;
                }
                PTHREAD_RWLOCK_unlock(&obj_hdl->lock);

                if (!align)
                        memcpy(buffer, buffer_align + offset_moved,
                               *read_amount);
        }

out:
        if (!align) gsh_free(buffer_align);

        return st;
}


/* prepare aligned buffer (plain_align)
 * concurrency (locks) is managed in secnfs_write
 */
secnfs_s prepare_aligned_buffer(struct secnfs_fsal_obj_handle *hdl,
                                void *buffer, void *plain_align,
                                size_t buffer_size, uint64_t size_align,
                                uint64_t offset_align, uint64_t offset_moved)
{
        uint64_t pi_count;
        secnfs_s ret;

        pi_count = get_pi_count(size_align);

        /* prepare first block */
        ret = read_modify_one(plain_align,
                        buffer,
                        offset_moved,
                        pi_count == 1 ?
                        buffer_size : PI_INTERVAL_SIZE - offset_moved,
                        offset_align,
                        hdl);

        if (ret != SECNFS_OKAY)
                return ret;

        if (pi_count > 1) {
                /* prepare last block */
                uint64_t tail_offset; /* relative offset */
                tail_offset = (pi_count - 1) * PI_INTERVAL_SIZE;

                ret = read_modify_one(
                        plain_align + tail_offset,
                        buffer - offset_moved + tail_offset,
                        0,
                        offset_moved + buffer_size - tail_offset,
                        offset_align + tail_offset,
                        hdl);

                if (ret != SECNFS_OKAY)
                        return ret;

                /* prepare intermediate blocks */
                if (pi_count > 2) {
                /* may save this copy by loop auth_encrypt carefully */
                        memcpy(plain_align + PI_INTERVAL_SIZE,
                                buffer - offset_moved + PI_INTERVAL_SIZE,
                                (pi_count - 2) * PI_INTERVAL_SIZE);
                }
        }

        return SECNFS_OKAY;
}


/* secnfs_write
 * concurrency (locks) is managed in cache_inode_*
 */
fsal_status_t secnfs_write(struct fsal_obj_handle *obj_hdl,
                           uint64_t offset,
                           size_t buffer_size, void *buffer,
                           size_t *write_amount, bool *fsal_stable)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);
        uint64_t offset_align;
        uint64_t offset_moved;
        uint64_t size_align;
        uint64_t size_align_lock;
        uint8_t *plain_align = NULL;
        fsal_status_t st;
        secnfs_s ret;
        bool align;
        struct attrlist attrs;

        SECNFS_D("hdl = %x; write to %u (%u)\n", hdl, offset, buffer_size);

        ops_hist_add(&sn_counters.ops_hist, offset, buffer_size);
        __sync_fetch_and_add(&sn_counters.nr_writes, 1);

	ops_hist_add(&sn_counters.ops_hist, offset, buffer_size);
	__sync_fetch_and_add(&sn_counters.nr_writes, 1);

        if (obj_hdl->type != REGULAR_FILE) {
                return next_ops.obj_ops->write(hdl->next_handle,
                                               offset,
                                               buffer_size, buffer,
                                               write_amount,
                                               fsal_stable);
        }
        assert(hdl->key_initialized);

        if (buffer_size == 0)
                return fsalstat(ERR_FSAL_NO_ERROR, 0);

        offset_align = pi_round_down(offset);
        offset_moved = offset - offset_align;
        size_align = pi_round_up(offset + buffer_size) - offset_align;
        align = (offset == offset_align && buffer_size == size_align) ? 1 : 0;
        SECNFS_D("hdl = %x; offset_align = %u, size_align = %u",
                 hdl, offset_align, size_align);

        size_align_lock = secnfs_range_try_lock(hdl->range_lock,
                                                offset_align, size_align);
        if (!size_align_lock) {
                SECNFS_D("hdl = %x; write delayed");
                return fsalstat(ERR_FSAL_DELAY, 0);
        }

        /* allocate buffer for plain text if non-aligned */
        plain_align = align ? buffer : gsh_malloc(size_align_lock);
        if (!plain_align) {
                st = fsalstat(ERR_FSAL_NOMEM, 0);
                goto out;
        }

        if (!align) {
                __sync_fetch_and_add(&sn_counters.nr_read_modify_update, 1);
                ret = prepare_aligned_buffer(hdl, buffer, plain_align,
                                             buffer_size, size_align_lock,
                                             offset_align, offset_moved);
                if (ret != SECNFS_OKAY) {
                        st = secnfs_to_fsal_status(ret);
                        goto out;
                }
        }

	st = do_aligned_write(hdl, offset_align, size_align_lock, plain_align,
			      write_amount, fsal_stable, &attrs);
	if (FSAL_IS_ERROR(st))
                goto out;
        if (*write_amount == 0) {
                SECNFS_D("hdl = %x; write_amount = 0\n", hdl);
                goto out;
        }

        PTHREAD_RWLOCK_wrlock(&obj_hdl->lock);

        if (secnfs_hole_remove(hdl->holes, offset_align, *write_amount))
                hdl->has_dirty_meta = 1;

        /* get effective write_amount */
        *write_amount = (*write_amount == size_align) ?
                        buffer_size : *write_amount - offset_moved;

        if (offset + *write_amount > get_filesize(hdl)) {
                uint64_t filesize_up = pi_round_up(get_filesize(hdl));
                if (offset_align >= filesize_up + PI_INTERVAL_SIZE) {
                        /* offset is beyond the current filesize, add file hole.
                        * do not add last block that is already padded with 0 */
                        SECNFS_D("hdl = %x; add file hole %u (%u)", hdl,
                                 filesize_up, offset_align - filesize_up);
                        secnfs_hole_add(hdl->holes, filesize_up,
                                        offset_align - filesize_up);
                        hdl->has_dirty_meta = 1;
                }
                update_filesize(hdl, offset + *write_amount);
        }

        SECNFS_D("hdl = %x; client_size = %d; write_amount = %d\n", hdl,
                 get_filesize(hdl), *write_amount);

        PTHREAD_RWLOCK_unlock(&obj_hdl->lock);

        /* TODO lock secnfs_fsal_obj_handle */
        /* TODO save the getattrs following the write */
        hdl->modify_time = attrs.mtime;
        hdl->change_time = attrs.ctime;
        hdl->server_change = attrs.change;
        hdl->change = attrs.change;

out:
        secnfs_range_unlock(hdl->range_lock, offset_align, size_align_lock);
        if (!align) gsh_free(plain_align);

        return st;
}


/* secnfs_truncate
 * called by setattrs (with ATTR_SIZE mask) which holds cache write lock
 */
fsal_status_t secnfs_truncate(struct fsal_obj_handle *obj_hdl,
                              uint64_t newsize)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);
        uint64_t newsize_up;
        uint64_t newsize_down;
        uint64_t filesize_up;
        secnfs_s ret;

        SECNFS_D("hdl = %x; truncating to %u (current: %u)", hdl,
                  newsize, get_filesize(hdl));

        if (newsize == get_filesize(hdl))
                return fsalstat(ERR_FSAL_NO_ERROR, 0);

        newsize_up = pi_round_up(newsize);
        if (newsize != newsize_up) {
                /* If newsize not aligned, explicitly fill zero for last
                 * block, since our file hole must be aligned. */ 
                ret = secnfs_fill_zero(hdl, newsize, newsize_up);
                if (ret != SECNFS_OKAY)
                        return secnfs_to_fsal_status(ret);
        }

        newsize_down = pi_round_down(newsize);
        filesize_up = pi_round_up(get_filesize(hdl));
        if (newsize < filesize_up) {
                /* note that explicit zero padding is not counted as hole */
                if (secnfs_hole_remove(hdl->holes, newsize_down,
                                       filesize_up - newsize_down))
                        hdl->has_dirty_meta = 1;
        } else if (newsize_down - filesize_up >= PI_INTERVAL_SIZE) {
                secnfs_hole_add(hdl->holes, filesize_up,
                                newsize_down - filesize_up);
        }

        update_filesize(hdl, newsize);

        return fsalstat(ERR_FSAL_NO_ERROR, 0);
}


/* secnfs_commit
 * Commit a file range to storage.
 * for right now, fsync will have to do.
 */
fsal_status_t secnfs_commit(struct fsal_obj_handle *obj_hdl,    /* sync */
                            off_t offset, size_t len)
{
	__sync_fetch_and_add(&sn_counters.nr_commits, 1);
	return next_ops.obj_ops->commit(next_handle(obj_hdl), offset, len);
}


/* secnfs_lock_op
 * lock a region of the file
 * throw an error if the fd is not open.  The old fsal didn't
 * check this.
 */
fsal_status_t secnfs_lock_op(struct fsal_obj_handle *obj_hdl,
                             void *p_owner,
                             fsal_lock_op_t lock_op,
                             fsal_lock_param_t *request_lock,
                             fsal_lock_param_t *conflicting_lock)
{
        return next_ops.obj_ops->lock_op(next_handle(obj_hdl), p_owner,
                                         lock_op, request_lock,
                                         conflicting_lock);
}


/* secnfs_close
 * Close the file if it is still open.
 * Yes, we ignor lock status.  Closing a file in POSIX
 * releases all locks but that is state and cache inode's problem.
 */
fsal_status_t secnfs_close(struct fsal_obj_handle *obj_hdl)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);

	__sync_fetch_and_add(&sn_counters.nr_closes, 1);

        if (obj_hdl->type == REGULAR_FILE && hdl->has_dirty_meta) {
                fsal_status_t st;

                SECNFS_D("Closing hdl = %x; writing header (filesize: %u)",
                         hdl, get_filesize(hdl));

                st = write_header(obj_hdl);

                if (FSAL_IS_ERROR(st)) {
                        /* when unlink a pinned file, fsal_close will not be
                         * called. But cache_inode_lru_clean() will eventually
                         * call this fsal_close, resulting in write_header
                         * to a nonexistent remote handle.
                         */
                        if (st.major == ERR_FSAL_STALE) {
                                SECNFS_D("stale remote handle(maybe unlinked)");
                        } else {
                                SECNFS_D("write_header failed: %d", st.major);
                                return st;
                        }
                }
        }

        return next_ops.obj_ops->close(next_handle(obj_hdl));
}


/* secnfs_lru_cleanup
 * free non-essential resources at the request of cache inode's
 * LRU processing identifying this handle as stale enough for resource
 * trimming.
 */
fsal_status_t secnfs_lru_cleanup(struct fsal_obj_handle *obj_hdl,
                                 lru_actions_t requests)
{
        return next_ops.obj_ops->lru_cleanup(next_handle(obj_hdl), requests);
}
