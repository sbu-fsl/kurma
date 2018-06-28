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
#include "config.h"

#include "fsal.h"
#include "fsal_handle_syscalls.h"
#include <libgen.h>		/* used for 'dirname' */
#include <pthread.h>
#include <string.h>
#include <sys/types.h>
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "secnfs_methods.h"
#include <os/subr.h>

#define MAKE_SECNFS_FH(func, parent, handle, args...)                       \
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(parent);         \
        struct fsal_obj_handle *next_hdl;                                   \
        fsal_status_t st;                                                   \
        st = next_ops.obj_ops->func(hdl->next_handle, ## args, &next_hdl);  \
        if (FSAL_IS_ERROR(st)) {                                            \
                LogCrit(COMPONENT_FSAL, "secnfs " #func " failed");         \
                return st;                                                  \
        }                                                                   \
        return make_handle_from_next(op_ctx->fsal_export, next_hdl, handle, false);


extern struct next_ops next_ops;

static fsal_status_t secnfs_getattrs_and_header(struct fsal_obj_handle *obj_hdl);

/************************* helpers **********************/

// TODO remove exp: to use op_ctx->export
static struct secnfs_fsal_obj_handle *alloc_handle(struct fsal_export *exp,
                                                   const struct attrlist *attr)
{
        struct secnfs_fsal_module *secnfs_fsal = secnfs_module(exp->fsal);
        struct secnfs_fsal_obj_handle *hdl;
        fsal_status_t st;

        hdl = gsh_calloc(1, sizeof(*hdl));
        if (hdl == NULL)
                return NULL;

        fsal_obj_handle_init(&hdl->obj_handle, exp, attr->type);

        hdl->obj_handle.attributes = *attr;
        hdl->info = &secnfs_info;

        return hdl;
}

static const attrmask_t SECNFS_TIMESTAMPS_MASK = ATTR_ATIME | ATTR_MTIME | ATTR_CHANGE;

/**
 * Create a SECNFS obj handle from a corresponding handle of the next layer.
 *
 * @param[IN]       exp      SECNFS export
 * @param[IN/OUT]   next_hdl handle of the next layer
 * @param[OUT]      handle   resultant SECNFS handle
 *
 * NOTE: next_hdl will be released on failure!
 */
static fsal_status_t make_handle_from_next(struct fsal_export *exp,
                                           struct fsal_obj_handle *next_hdl,
                                           struct fsal_obj_handle **handle,
                                           bool load_header)
{
        struct secnfs_fsal_obj_handle *secnfs_hdl;
        fsal_status_t st;

        secnfs_hdl = alloc_handle(exp, &next_hdl->attributes);
        if (!secnfs_hdl) {
                LogMajor(COMPONENT_FSAL, "cannot allocate secnfs handle");
                next_ops.obj_ops->release(next_hdl);
                return fsalstat(ERR_FSAL_NOMEM, 0);
        }

        secnfs_hdl->next_handle = next_hdl;
        *handle = &secnfs_hdl->obj_handle;

        if (next_hdl->type == REGULAR_FILE) {
                if (!(secnfs_hdl->range_lock = secnfs_alloc_blockmap()) ||
                        !(secnfs_hdl->holes = secnfs_alloc_blockmap())) {
                        st = fsalstat(ERR_FSAL_NOMEM, 0);
                        goto err;
                }

                if (next_hdl->attributes.filesize > 0 && load_header) {
                        SECNFS_D("hdl = %x; reading header\n", secnfs_hdl);
                        st = secnfs_getattrs_and_header(*handle);
                        if (FSAL_IS_ERROR(st))
                                goto err;
                        SECNFS_D("hdl = %x; file encrypted: %d\n",
                                 secnfs_hdl, secnfs_hdl->encrypted);
                }
        }

        return fsalstat(ERR_FSAL_NO_ERROR, 0);

err:
        LogMajor(COMPONENT_FSAL, "cannot allocate secnfs handle");
        secnfs_release_blockmap(&secnfs_hdl->range_lock);
        secnfs_release_blockmap(&secnfs_hdl->holes);
        gsh_free(secnfs_hdl);
        next_ops.obj_ops->release(next_hdl);

        return st;
}

char* encrypt_name(const char *name)
{
        char *encrypted_name = (char *)malloc(strlen(name)*sizeof(char)+5);
	strcpy(encrypted_name, name);
	strcat(encrypted_name,"_xx");
	return encrypted_name;
}

char* decrypt_name(const char *name)
{
        char *decrypted_name = (char *)malloc(strlen(name)*sizeof(char)-2);
	strncpy(decrypted_name, name, strlen(name)-3);
	decrypted_name[strlen(name)-3] = '\0';
	return decrypted_name;
}

/************************* handle methods **********************/

/* lookup
 * deprecated NULL parent && NULL path implies root handle
 */
static fsal_status_t secnfs_lookup(struct fsal_obj_handle *parent,
				   const char *path,
				   struct fsal_obj_handle **handle)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(parent);
        struct fsal_obj_handle *next_hdl;
        fsal_status_t st;
        char *encrypted_path = encrypt_name(path);

	SECNFS_D("old_path %s", path);
	SECNFS_D("new_path %s", encrypted_path);

	__sync_fetch_and_add(&sn_counters.nr_lookups, 1);
	st = next_ops.obj_ops->lookup(hdl->next_handle, encrypted_path, &next_hdl);
        if (FSAL_IS_ERROR(st)) {
                LogCrit(COMPONENT_FSAL, "secnfs lookup failed");
                return st;
        }

	free(encrypted_path);

        return make_handle_from_next(op_ctx->fsal_export, next_hdl, handle, false);
}

static fsal_status_t secnfs_parse_header(struct secnfs_fsal_obj_handle *hdl,
					 void *header_buf, size_t header_size)
{
        secnfs_s ret;
        uint32_t header_len;

        if (header_size != FILE_HEADER_SIZE) {
                SECNFS_ERR("wrong header size: %llu", header_size);
                return fsalstat(ERR_FSAL_SERVERFAULT, 0);
        }

        /* TODO: Grab a lock of secnfs_fsal_obj_handle? */
	ret = secnfs_read_header(hdl->info,
                                 header_buf,
                                 header_size,
                                 &hdl->fk,
                                 &hdl->iv,
                                 &hdl->filesize,
                                 &hdl->encrypted,
                                 hdl->holes,
                                 &hdl->modify_time,
                                 &hdl->change_time,
                                 &hdl->change,
                                 &header_len,
                                 &hdl->kf_cache);
        if (ret != SECNFS_OKAY) {
                SECNFS_ERR("failed to parse header");
                return fsalstat(ERR_FSAL_SERVERFAULT, 0);
        }

        hdl->key_initialized = 1;

        return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

fsal_status_t read_header(struct fsal_obj_handle *fsal_hdl)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(fsal_hdl);
        fsal_status_t st;
        void *buf;
        uint32_t buf_size;
        size_t n, read_amount = 0;
        bool end_of_file = false;

	__sync_fetch_and_add(&sn_counters.nr_read_headers, 1);

        buf_size = FILE_HEADER_SIZE;
        buf = gsh_malloc(buf_size);
        if (!buf) {
                SECNFS_ERR("out of memory when allocating header");
                return fsalstat(ERR_FSAL_NOMEM, 0);
        }

        do {
                st = next_ops.obj_ops->read(hdl->next_handle,
                                            read_amount,
                                            buf_size - read_amount,
                                            buf + read_amount,
                                            &n,
                                            &end_of_file);
                if (FSAL_IS_ERROR(st)) {
                        SECNFS_ERR("cannot read secnfs header");
                        goto out;
                }

                read_amount += n;

                if (read_amount < FILE_HEADER_SIZE && end_of_file) {
                        st = fsalstat(ERR_FSAL_IO, 0);
                        SECNFS_ERR("invalid secnfs header size");
                        goto out;
                }
        } while (read_amount < FILE_HEADER_SIZE);

        st = secnfs_parse_header(hdl, buf, buf_size);
        if (FSAL_IS_ERROR(st)) {
                SECNFS_ERR("failed to parse header");
                goto out;
        }

out:
        gsh_free(buf);
        return st;
}

fsal_status_t write_header(struct fsal_obj_handle *fsal_hdl)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(fsal_hdl);
        fsal_status_t st;
        uint32_t buf_size;
        size_t write_amount = 0;
        void *buf;
        int ret;
        bool stable;
        struct io_info info;
        struct attrlist attrs;

        ret = secnfs_create_header(hdl->info, &hdl->fk, &hdl->iv,
                                   get_filesize(hdl),
                                   hdl->encrypted,
                                   hdl->holes,
                                   &hdl->modify_time,
                                   &hdl->change_time,
                                   hdl->change,
                                   &buf, &buf_size, &hdl->kf_cache);
        if (ret != SECNFS_OKAY || buf_size != FILE_HEADER_SIZE) {
		SECNFS_ERR("failed to create header: buf_size=%u, ret=%d",
			   buf_size, ret);
		return fsalstat(ERR_FSAL_SERVERFAULT, 0);
        }

        io_info_set_content_data(&info, 0, buf_size, buf);

        attrs.mask = SECNFS_TIMESTAMPS_MASK;
	st =
	    next_ops.obj_ops->write_plus(hdl->next_handle, 0, buf_size, buf,
					 &write_amount, &stable, &info, &attrs);
	if (FSAL_IS_ERROR(st)) {
                SECNFS_ERR("cannot write secnfs header");
                goto out;
        }
        if (!stable || write_amount != buf_size) {
                SECNFS_ERR("failed to write header: stable=%d, size=%d",
                           stable, write_amount);
                goto out;
        }

        hdl->server_change = attrs.change;
        hdl->has_dirty_meta = 0;

out:
        free(buf);
        return st;
}

static fsal_status_t secnfs_create(struct fsal_obj_handle *dir_hdl,
				   const char *name, struct attrlist *attrib,
				   struct fsal_obj_handle **handle)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(dir_hdl);
        struct fsal_obj_handle *next_hdl;
        struct secnfs_fsal_obj_handle *new_hdl;
        fsal_status_t st;
	char *encrypted_name = encrypt_name(name);
        secnfs_key_t key, iv;
        void *header_buf;
        uint32_t header_size;
        size_t header_wrote;
        int ret;
        void *holes;
        void *kf_cache = NULL;

	SECNFS_D("CREATING '%s' in dir hdl (%x) type: %d", encrypted_name, hdl,
		 attrib->type);

	__sync_fetch_and_add(&sn_counters.nr_creates, 1);

        generate_key_and_iv(&key, &iv);
        holes = secnfs_alloc_blockmap();
        if (!holes) {
                SECNFS_ERR("failed to allocate hole blockmap of '%s'", name);
                return fsalstat(ERR_FSAL_NOMEM, 0);
        }
	ret = secnfs_create_header(hdl->info, &key, &iv, 0, hdl->encrypted,
				   holes, &hdl->modify_time, &hdl->change_time,
				   hdl->change, &header_buf, &header_size,
				   &kf_cache);
	if (ret != SECNFS_OKAY) {
                SECNFS_ERR("failed to initial the header of '%s'", name);
                secnfs_release_blockmap(&holes);
                return fsalstat(ERR_FSAL_SERVERFAULT, 0);
        }
	st = next_ops.obj_ops->create_plus(hdl->next_handle, encrypted_name, attrib,
					   header_size, header_buf, &next_hdl,
					   &header_wrote);
	
	if (FSAL_IS_ERROR(st)) {
                SECNFS_ERR("create_plus failed when creating '%s'", encrypted_name);
		goto err;
	}

        assert(attrib->type == REGULAR_FILE);
        st = make_handle_from_next(op_ctx->fsal_export, next_hdl, handle, false);
        if (FSAL_IS_ERROR(st)) {
		SECNFS_ERR("cannot create secnfs handle for '%s'", name);
		goto err;
        }
        (*handle)->attributes.filesize = 0;

        new_hdl = secnfs_handle(*handle);
        new_hdl->key_initialized = 1;
        new_hdl->has_dirty_meta = 0;
        new_hdl->encrypted = new_hdl->info->file_encryption;
        new_hdl->fk = key;
        new_hdl->iv = iv;
        new_hdl->kf_cache = kf_cache;

        free(header_buf);
        secnfs_release_blockmap(&holes);
        return st;
err:
	free(encrypted_name);
        free(header_buf);
        secnfs_release_blockmap(&holes);
        secnfs_release_keyfile_cache(&kf_cache);
        return st;
}

static fsal_status_t secnfs_mkdir(struct fsal_obj_handle *dir_hdl,
				  const char *name, struct attrlist *attrib,
				  struct fsal_obj_handle **handle)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(dir_hdl);
        struct fsal_obj_handle *next_hdl;
        fsal_status_t st;
        char *encrypted_name = encrypt_name(name);
        
	__sync_fetch_and_add(&sn_counters.nr_mkdirs, 1);
	st = next_ops.obj_ops->mkdir(hdl->next_handle, encrypted_name, attrib, &next_hdl);
        if (FSAL_IS_ERROR(st)) {           
                LogCrit(COMPONENT_FSAL, "secnfs makedir failed");
                return st;
        }

	free(encrypted_name);
        return make_handle_from_next(op_ctx->fsal_export, next_hdl, handle, false);
}

static fsal_status_t secnfs_mknode(struct fsal_obj_handle *dir_hdl,
				   const char *name,
				   object_file_type_t nodetype, /* IN */
				   fsal_dev_t *dev,		/* IN */
				   struct attrlist *attrib,
				   struct fsal_obj_handle **handle)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(dir_hdl);
        struct fsal_obj_handle *next_hdl;
        fsal_status_t st;
        char *encrypted_name = encrypt_name(name);
        
	st = next_ops.obj_ops->mknode(hdl->next_handle, encrypted_name,
				nodetype, dev, attrib, &next_hdl);
        if (FSAL_IS_ERROR(st)) {
                LogCrit(COMPONENT_FSAL, "secnfs mknode failed");
                return st;
        }

	free(encrypted_name);

        return make_handle_from_next(op_ctx->fsal_export, next_hdl, handle, false);
}

/** secnfs_symlink
 *  Note that we do not set mode bits on symlinks for Linux/POSIX
 *  They are not really settable in the kernel and are not checked
 *  anyway (default is 0777) because open uses that target's mode
 */

static fsal_status_t secnfs_symlink(struct fsal_obj_handle *dir_hdl,
				    const char *name, const char *link_path,
				    struct attrlist *attrib,
				    struct fsal_obj_handle **handle)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(dir_hdl);
        struct fsal_obj_handle *next_hdl;
        fsal_status_t st;
	char *encrypted_name = encrypt_name(name);
	char *encrypted_link_path = encrypt_name(link_path);
        
	st = next_ops.obj_ops->symlink(hdl->next_handle, encrypted_name,
				 encrypted_link_path, attrib, &next_hdl);
        if (FSAL_IS_ERROR(st)) {
                LogCrit(COMPONENT_FSAL, "secnfs makesymlink failed");
                return st;
        }

	free(encrypted_name);
	free(encrypted_link_path);

        return make_handle_from_next(op_ctx->fsal_export, next_hdl, handle, false);
}

static fsal_status_t secnfs_readlink(struct fsal_obj_handle *obj_hdl,
				     struct gsh_buffdesc *link_content,
				     bool refresh)
{
	fsal_status_t st;
	int len = 0;
	char *decrypted_name, *tmp = NULL;
        SECNFS_D("before reading link content : %s\n", (char *)link_content->addr);
        st = next_ops.obj_ops->readlink(next_handle(obj_hdl),
                                          link_content, refresh);
	decrypted_name = decrypt_name((char *)link_content->addr);
	tmp = (char *)link_content->addr;
	link_content->addr = decrypted_name;
	link_content->len -= 3;
	//len = link_content->len;
	//link_content->addr[len] = '\0';
	free(tmp);
        SECNFS_D("after reading link content : %s\n", (char *)link_content->addr);
	return st;
}

static fsal_status_t secnfs_link(struct fsal_obj_handle *obj_hdl,
				 struct fsal_obj_handle *destdir_hdl,
				 const char *name)
{
	char *encrypted_name;
	fsal_status_t st;

        SECNFS_D("hard link old name : %s\n", name);
	encrypted_name = encrypt_name(name);
        SECNFS_D("hard link new name : %s\n", encrypted_name);
        st = next_ops.obj_ops->link(next_handle(obj_hdl),
                                      next_handle(destdir_hdl), encrypted_name);
	free(encrypted_name);
	return st;
}

struct vishnu_state {
	void *origin_state;
	fsal_readdir_cb cb;
};

bool my_readdir_cb(const char *name, void *dir_state, fsal_cookie_t cookie) 
{
	struct vishnu_state *buf = (struct vishnu_state *)dir_state;
	bool res;
	char *new_name;
	
	new_name = decrypt_name(name);

	//new_name = strdup(name);
	SECNFS_D("old_name %s", name);
	//new_name[strlen(name)-3] = '\0';
	SECNFS_D("new_name %s", new_name);
	res = buf->cb(new_name, buf->origin_state, cookie);
	free(new_name);
	return res;
}


/**
 * secnfs_readdir
 * read the directory and call through the callback function for
 * each entry.
 * @param dir_hdl [IN] the directory to read
 * @param whence [IN] where to start (next)
 * @param dir_state [IN] pass thru of state to callback
 * @param cb [IN] callback function
 * @param eof [OUT] eof marker true == end of dir
 */

static fsal_status_t secnfs_readdir(struct fsal_obj_handle *dir_hdl,
				    fsal_cookie_t *whence, void *dir_state,
				    fsal_readdir_cb cb, bool *eof)
{
	fsal_status_t st;
	struct vishnu_state buf = {
			.origin_state = dir_state,
			.cb = cb,
	};
	
	SECNFS_D("In READDIR function\n");
	__sync_fetch_and_add(&sn_counters.nr_readdirs, 1);
        st = next_ops.obj_ops->readdir(next_handle(dir_hdl), whence,
					(void*)&buf, my_readdir_cb, eof);
	return st;
}

static fsal_status_t secnfs_rename(struct fsal_obj_handle *olddir_hdl,
				   const char *old_name,
				   struct fsal_obj_handle *newdir_hdl,
				   const char *new_name)
{
	fsal_status_t st;
	char *encrypted_old_name, *encrypted_new_name;

	encrypted_old_name = encrypt_name(old_name);
	encrypted_new_name = encrypt_name(new_name);

	__sync_fetch_and_add(&sn_counters.nr_renames, 1);
        st =  next_ops.obj_ops->rename(next_handle(olddir_hdl),
                                        encrypted_old_name,
                                        next_handle(newdir_hdl),
                                        encrypted_new_name);
	free(encrypted_old_name);
	free(encrypted_new_name);

	return st;
}

/**
 * Read file header and attributes of the server file, but do NOT set
 * attributes of the fsal_obj_handle.
 */
static fsal_status_t read_attrs_and_header(struct secnfs_fsal_obj_handle *hdl,
					   struct attrlist *attrs)
{
        fsal_status_t st;
        size_t read_amount = 0;
        void *header = NULL;
        struct io_info info;
        bool end_of_file;

	__sync_fetch_and_add(&sn_counters.nr_read_headers, 1);

        header = gsh_malloc(FILE_HEADER_SIZE);
        if (!header) {
                LogCrit(COMPONENT_FSAL, "out of memory");
                return fsalstat(ERR_FSAL_NOMEM, 0);
        }

        io_info_set_content_data(&info, 0, FILE_HEADER_SIZE, header);
        st = next_ops.obj_ops->read_plus(hdl->next_handle, 0, FILE_HEADER_SIZE,
                                         header, &read_amount, &end_of_file,
                                         &info, attrs);
        if (FSAL_IS_ERROR(st) || read_amount != FILE_HEADER_SIZE) {
                SECNFS_ERR("getattrs_plus failed to read header: err=%d, amount=%llu",
                           st.major, read_amount);
                goto out;
        }

        st = secnfs_parse_header(hdl, header, read_amount);
        if (FSAL_IS_ERROR(st)) {
                SECNFS_ERR("failed to parse header");
                goto out;
        }

out:
        gsh_free(header);
        return st;
}

/**
 * Set attributes by reading the file header and attributes of the server file.
 * The caller should not the lock that protects obj_hdl's attributes.
 */
static fsal_status_t secnfs_getattrs_and_header(struct fsal_obj_handle *obj_hdl)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);
        struct attrlist attrs;
        fsal_status_t st;

        attrs.mask = 0;
        st = read_attrs_and_header(hdl, &attrs);
        if (!FSAL_IS_ERROR(st)) {
                /* update attributes */
                update_attributes(&attrs, &obj_hdl->attributes);
                obj_hdl->attributes.filesize = hdl->filesize;
                obj_hdl->attributes.mtime = hdl->modify_time;
                obj_hdl->attributes.ctime = hdl->change_time;
                hdl->server_change = attrs.change;
                hdl->change = attrs.change;
        }

        return st;
}

static fsal_status_t secnfs_getattrs_impl(struct secnfs_fsal_obj_handle *hdl)
{
	struct fsal_obj_handle *next_hdl = hdl->next_handle;
	fsal_status_t st;

	st = next_ops.obj_ops->getattrs(next_hdl);
	if (FSAL_IS_ERROR(st)) {
                return st;
        }

	if (next_hdl->attributes.type == REGULAR_FILE) {
		update_attributes(&next_hdl->attributes,
				  &hdl->obj_handle.attributes);
                // TODO: lock secnfs_fsal_obj_handle
		hdl->obj_handle.attributes.filesize = hdl->filesize;
		if (next_hdl->attributes.change > hdl->server_change) {
			/**
                         * The file has been changed by other proxies.  We set
                         * all timestamps to the latest one, and consequently
                         * causing the client's cache to be invalidated.
                         * TODO: does this have any side effect?
                         */
                        hdl->modify_time = next_hdl->attributes.mtime;
                        hdl->change_time = next_hdl->attributes.ctime;
                        hdl->server_change = next_hdl->attributes.change;
                        hdl->change = next_hdl->attributes.change;
		} else {
                        /* The file has not been changed by other proxies. */
                        hdl->obj_handle.attributes.mtime = hdl->modify_time;
                        hdl->obj_handle.attributes.ctime = hdl->change_time;
                        hdl->obj_handle.attributes.change = hdl->change;
		}
	} else {
		update_attributes(&next_hdl->attributes,
				  &hdl->obj_handle.attributes);
	}

        return st;
}

static fsal_status_t secnfs_getattrs(struct fsal_obj_handle *obj_hdl)
{
        fsal_status_t st;
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);

	__sync_fetch_and_add(&sn_counters.nr_getattrs, 1);

	if (obj_hdl->type == REGULAR_FILE && !hdl->key_initialized) {
		st = secnfs_getattrs_and_header(obj_hdl);
	} else {
		st = secnfs_getattrs_impl(hdl);
	}

	return st;
}

/*
 * NOTE: this is done under protection of the attributes rwlock in the cache entry.
 */
static fsal_status_t secnfs_setattrs(struct fsal_obj_handle *obj_hdl,
				     struct attrlist *attrs)
{
        struct secnfs_fsal_obj_handle *hdl = secnfs_handle(obj_hdl);
        size_t new_filesize = attrs->filesize;
        fsal_status_t st;

	__sync_fetch_and_add(&sn_counters.nr_setattrs, 1);
        /*
         * We use the "obj_hdl->type", instead of "attrs->type" because,
         * sometimes, "attrs->type" is NO_FILE_TYPE.  For example, when
         * we "truncate" a file.
         */
        if ((attrs->mask & ATTR_SIZE) && obj_hdl->type == REGULAR_FILE) {
                fsal_status_t st;
                st = secnfs_truncate(obj_hdl, new_filesize);
                if (FSAL_IS_ERROR(st)) {
                        LogCrit(COMPONENT_FSAL, "truncate failed");
                        return st;
                }
		attrs->filesize = pi_round_up(new_filesize) + FILE_HEADER_SIZE;
	}

        st = next_ops.obj_ops->setattrs(hdl->next_handle, attrs);

        return st;
}

/*
 * unlink the named file in the directory
 */
static fsal_status_t secnfs_unlink(struct fsal_obj_handle *dir_hdl,
				   const char *name)
{
	fsal_status_t st;
        char *encrypted_name = encrypt_name(name);

	SECNFS_D("Unlink %s", encrypted_name);
	__sync_fetch_and_add(&sn_counters.nr_unlinks, 1);
        st = next_ops.obj_ops->unlink(next_handle(dir_hdl), encrypted_name);

	free(encrypted_name);

	return st;
}


/**
 * fill in the opaque f/s file handle part.
 * we zero the buffer to length first.  This MAY already be done above
 * at which point, remove memset here because the caller is zeroing
 * the whole struct.
 */
static fsal_status_t secnfs_handle_digest(const struct fsal_obj_handle *obj_hdl,
					  fsal_digesttype_t output_type,
					  struct gsh_buffdesc *fh_desc)
{
        struct fsal_obj_handle *hdl = (struct fsal_obj_handle *)obj_hdl;

        return next_ops.obj_ops->handle_digest(next_handle(hdl),
                                               output_type, fh_desc);
}


/**
 * return a handle descriptor into the handle in this object handle
 * @TODO reminder.  make sure things like hash keys don't point here
 * after the handle is released.
 */
static void secnfs_handle_to_key(struct fsal_obj_handle *obj_hdl,
				 struct gsh_buffdesc *fh_desc)
{
        return next_ops.obj_ops->handle_to_key(next_handle(obj_hdl), fh_desc);
}


/*
 * release our export first so they know we are gone
 */
static void secnfs_release(struct fsal_obj_handle *obj_hdl)
{
        struct secnfs_fsal_obj_handle *secnfs_hdl = secnfs_handle(obj_hdl);
        struct fsal_obj_handle *next_hdl = secnfs_hdl->next_handle;

        fsal_obj_handle_uninit(obj_hdl);

        secnfs_release_keyfile_cache(&secnfs_hdl->kf_cache);
        secnfs_release_blockmap(&secnfs_hdl->range_lock);
        secnfs_release_blockmap(&secnfs_hdl->holes);
        gsh_free(secnfs_hdl);

	next_ops.obj_ops->release(next_hdl);
}


void secnfs_handle_ops_init(struct fsal_obj_ops *ops)
{
	ops->release = secnfs_release;
	ops->lookup = secnfs_lookup;
	ops->readdir = secnfs_readdir;
	ops->create = secnfs_create;
	ops->mkdir = secnfs_mkdir;
	ops->mknode = secnfs_mknode;
	ops->symlink = secnfs_symlink;
	ops->readlink = secnfs_readlink;
	ops->test_access = fsal_test_access;
	ops->getattrs = secnfs_getattrs;
	ops->setattrs = secnfs_setattrs;
	ops->link = secnfs_link;
	ops->rename = secnfs_rename;
	ops->unlink = secnfs_unlink;
	ops->open = secnfs_open;
	ops->status = secnfs_status;
	ops->read = secnfs_read;
	ops->write = secnfs_write;
	ops->commit = secnfs_commit;
	ops->lock_op = secnfs_lock_op;
	ops->close = secnfs_close;
	ops->lru_cleanup = secnfs_lru_cleanup;
	ops->handle_digest = secnfs_handle_digest;
	ops->handle_to_key = secnfs_handle_to_key;

	/* xattr related functions */
	ops->list_ext_attrs = secnfs_list_ext_attrs;
	ops->getextattr_id_by_name = secnfs_getextattr_id_by_name;
	ops->getextattr_value_by_name = secnfs_getextattr_value_by_name;
	ops->getextattr_value_by_id = secnfs_getextattr_value_by_id;
	ops->setextattr_value = secnfs_setextattr_value;
	ops->setextattr_value_by_id = secnfs_setextattr_value_by_id;
	ops->getextattr_attrs = secnfs_getextattr_attrs;
	ops->remove_extattr_by_id = secnfs_remove_extattr_by_id;
	ops->remove_extattr_by_name = secnfs_remove_extattr_by_name;

}


/**
 * modeled on old api except we don't stuff attributes.
 * KISS
 */
fsal_status_t secnfs_lookup_path(struct fsal_export *exp_hdl,
                                 const char *path,
                                 struct fsal_obj_handle **handle)
{
        struct secnfs_fsal_export *exp = secnfs_export(exp_hdl);
        struct fsal_obj_handle *next_hdl;
        fsal_status_t st;

        st = next_ops.exp_ops->lookup_path(exp->next_export, path, &next_hdl);
        if (FSAL_IS_ERROR(st)) {
                return st;
        }

        return make_handle_from_next(exp_hdl, next_hdl, handle, false);
}


/**
 * Does what original FSAL_ExpandHandle did (sort of)
 * returns a ref counted handle to be later used in cache_inode etc.
 * NOTE! you must release this thing when done with it!
 * BEWARE! Thanks to some holes in the *AT syscalls implementation,
 * we cannot get an fd on an AF_UNIX socket, nor reliably on block or
 * character special devices.  Sorry, it just doesn't...
 * we could if we had the handle of the dir it is in, but this method
 * is for getting handles off the wire for cache entries that have LRU'd.
 * Ideas and/or clever hacks are welcome...
 */
fsal_status_t secnfs_create_handle(struct fsal_export *exp,
                                   struct gsh_buffdesc *hdl_desc,
                                   struct fsal_obj_handle **handle)
{
        fsal_status_t st;
        struct fsal_obj_handle *next_hdl;
        struct secnfs_fsal_export *secnfs_exp = secnfs_export(exp);

        st = next_ops.exp_ops->create_handle(secnfs_exp->next_export,
                                             hdl_desc, &next_hdl);
        if (FSAL_IS_ERROR(st)) {
                SECNFS_ERR("cannot create next handle (%d, %d)",
                           st.major, st.minor);
                return st;
        }

        SECNFS_F("handle created by secnfs_create_handle\n");

        return make_handle_from_next(exp, next_hdl, handle, false);
}
