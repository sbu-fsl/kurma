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

#include "config.h"

#include "fsal.h"
#include "fsal_up.h"
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "kurma_methods.h"
#include "kurma.h"
#include "city.h"
#include "nfs_file_handle.h"
#include "display.h"

#include "perf_stats.h"

/* Atomic uint64_t that is used to generate inode numbers in the Pseudo FS */
uint64_t inode_number;

#define V4_FH_OPAQUE_SIZE                                                      \
	(sizeof(struct alloc_file_handle_v4) - sizeof(struct file_handle_v4))

bool snapshot_enabled;

/* The latested deleted object to save unnecessary lookup following the
 * deletion. */
/*__thread ObjectID deleted_oid;*/  /* incomplete object */
__thread int64_t deleted_obj_id1;
__thread int64_t deleted_obj_id2;
__thread int16_t deleted_obj_creator;

/**
 * @brief Construct the fs opaque part of a kurma nfsv4 handle with ObjectID
 *
 * Given the components of a kurma nfsv4 handle, the nfsv4 handle is
 * created by concatenating the components. This is the fs opaque piece
 * of struct file_handle_v4 and what is sent over the wire.
 *
 * 8 + 8 _+ 4 +_4 + 4  + 4 = 32 Bytes
 *
 * Note: assume little-endian
 */
static void package_kurma_handle(char *buf, const ObjectID *oid,
				 int snapshot_id)
{
	int opaque_bytes_used = 0;

	memcpy(buf, &oid->id->id1, sizeof(oid->id->id1));
	opaque_bytes_used += sizeof(oid->id->id1);

	memcpy(buf + opaque_bytes_used, &oid->id->id2, sizeof(oid->id->id2));
	opaque_bytes_used += sizeof(oid->id->id2);

	memcpy(buf + opaque_bytes_used, &oid->creator, sizeof(oid->creator));
	opaque_bytes_used += sizeof(oid->creator);

	memcpy(buf + opaque_bytes_used, &oid->type, sizeof(oid->type));
	opaque_bytes_used += sizeof(oid->type);

	memcpy(buf + opaque_bytes_used, &oid->flags, sizeof(oid->flags));
	opaque_bytes_used += sizeof(oid->flags);

	memcpy(buf + opaque_bytes_used, &snapshot_id, sizeof(snapshot_id));
	opaque_bytes_used += sizeof(snapshot_id);

	assert(opaque_bytes_used <= V4_FH_OPAQUE_SIZE);
}

static void extract_kurma_handle(const char *buf, ObjectID *oid,
				 int *snapshot_id)
{
	memcpy(&oid->id->id1, buf, sizeof(oid->id->id1));
	buf += sizeof(oid->id->id1);

	memcpy(&oid->id->id2, buf, sizeof(oid->id->id2));
	buf += sizeof(oid->id->id2);

	memcpy(&oid->creator, buf, sizeof(oid->creator));
	buf += sizeof(oid->creator);

	memcpy(&oid->type, buf, sizeof(oid->type));
	buf += sizeof(oid->type);

	memcpy(&oid->flags, buf, sizeof(oid->flags));
	buf += sizeof(oid->flags);

	memcpy(snapshot_id, buf, sizeof(*snapshot_id));
}

/**
 * @brief Concatenate a number of kurma tokens into a string
 *
 * When reading kurma paths from export entries, we divide the
 * path into tokens. This function will recombine a specific number
 * of those tokens into a string.
 *
 * @param[in/out] pathbuf Must be not NULL. Tokens are copied to here.
 * @param[in] node for which a full kurmapath needs to be formed.
 * @param[in] maxlen maximum number of chars to copy to pathbuf
 *
 * @return void
 *
 * Pseudofs routine for debug, usage:
 *  char path[MAXPATHLEN];
 *  struct display_buffer pathbuf = { sizeof(path), path, path };
 *  rc = fullpath(&pathbuf, hdl);
 */
static int fullpath(struct display_buffer *pathbuf,
		    struct kurma_fsal_obj_handle *this_node)
{
	int b_left;

	if (this_node->parent != NULL)
		b_left = fullpath(pathbuf, this_node->parent);
	else
		b_left = display_start(pathbuf);

	/* Add slash for all but root node */
	if (b_left > 0 && this_node->parent != NULL)
		b_left = display_cat(pathbuf, "/");

	/* Append the node's name.
	 * Note that a Pseudo FS root's name is it's full path.
	 */
	if (b_left > 0)
		b_left = display_cat(pathbuf, this_node->name);

	return b_left;
}

/* set default attrs by kurma, to be revised */
static void set_default_attrs(struct kurma_fsal_obj_handle *hdl)
{
	/* Fills the output struct */
	struct attrlist *attrs = &hdl->obj_handle.attributes;
	struct timespec tm_now;

	memset(attrs, 0, sizeof(*attrs));

	attrs->type = hdl->obj_handle.type;
	FSAL_SET_MASK(attrs->mask, ATTR_TYPE);

	attrs->filesize = 0;
	FSAL_SET_MASK(attrs->mask, ATTR_SIZE);

	/* fsid will be supplied later */
	attrs->fsid.major = 0;
	attrs->fsid.minor = 0;
	FSAL_SET_MASK(attrs->mask, ATTR_FSID);

	attrs->fileid = atomic_postinc_uint64_t(&inode_number);
	FSAL_SET_MASK(attrs->mask, ATTR_FILEID);

	/*
	 * Allow attributes to be cached for 5 seconds.
	 * TODO: add expire time as a required extended attribute in Kurma, and
	 * then read and set expire_time_attr from Kurma instead of using a
	 * fixed value.
	 */
	attrs->expire_time_attr = KURMA_ATTRS_EXPIRE_SEC;

	attrs->mode = unix2fsal_mode((mode_t)0755);
	FSAL_SET_MASK(attrs->mask, ATTR_MODE);

	attrs->numlinks = 2;
	FSAL_SET_MASK(attrs->mask, ATTR_NUMLINKS);

	hdl->block_shift = 0;

	attrs->owner = op_ctx->creds->caller_uid;
	FSAL_SET_MASK(attrs->mask, ATTR_OWNER);

	attrs->group = op_ctx->creds->caller_gid;
	FSAL_SET_MASK(attrs->mask, ATTR_GROUP);

	/* Use full timer resolution */
	now(&tm_now);
	attrs->atime = attrs->ctime = attrs->mtime = tm_now;
	FSAL_SET_MASK(attrs->mask, ATTR_ATIME);
	FSAL_SET_MASK(attrs->mask, ATTR_CTIME);
	FSAL_SET_MASK(attrs->mask, ATTR_MTIME);

	/* We leave chgtime unset by default. */
	/*
	attrs->chgtime = attrs->atime;
	attrs->change =
	    timespec_to_nsecs(&attrs->chgtime);
	FSAL_SET_MASK(attrs->mask, ATTR_CHGTIME);
	*/

	attrs->spaceused = 0;
	FSAL_SET_MASK(attrs->mask, ATTR_SPACEUSED);

	attrs->rawdev.major = 0;
	attrs->rawdev.minor = 0;
	FSAL_SET_MASK(attrs->mask, ATTR_RAWDEV);
}

/* override attrs in hdl
 * existing ones (not in @attrs) are preserved.
 * */

static void set_attrs_override(struct kurma_fsal_obj_handle *khdl,
			       const struct attrlist *attrs)
{
	struct attrlist *hdl_attrs = &khdl->obj_handle.attributes;

	// Check hints for setting symbolic link
	if (attrs->hints & KURMA_HINT_SYMLINK) {
		khdl->obj_handle.type = SYMBOLIC_LINK;
		hdl_attrs->type = SYMBOLIC_LINK;
		KURMA_D("+++ Asigning file type to symbolic link in kurma_fsal_obj_handle");
	}

	// TODO sanitize attrs in this routine?
	if (FSAL_TEST_MASK(attrs->type, ATTR_TYPE)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_TYPE);
		hdl_attrs->type = attrs->type;
		khdl->obj_handle.type = attrs->type;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_SIZE)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_SIZE);
		hdl_attrs->filesize = attrs->filesize;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_OWNER)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_OWNER);
		hdl_attrs->owner = attrs->owner;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_GROUP)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_GROUP);
		hdl_attrs->group = attrs->group;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_MODE)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_MODE);
		hdl_attrs->mode = attrs->mode;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_NUMLINKS)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_NUMLINKS);
		hdl_attrs->numlinks = attrs->numlinks;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_CREATION)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_CREATION);
		hdl_attrs->creation = attrs->creation;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_ATIME)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_ATIME);
		hdl_attrs->atime = attrs->atime;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_MTIME)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_MTIME);
		hdl_attrs->mtime = attrs->mtime;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_CTIME)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_CTIME);
		hdl_attrs->ctime = attrs->ctime;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_CHGTIME)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_CHGTIME);
		hdl_attrs->chgtime = attrs->chgtime;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_SPACEUSED)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_SPACEUSED);
		hdl_attrs->spaceused = attrs->spaceused;
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_RAWDEV)) {
		FSAL_SET_MASK(hdl_attrs->mask, ATTR_RAWDEV);
		hdl_attrs->rawdev = attrs->rawdev;
	}
}

/**
 * Allocate and fill in a Kurma FSAL handle.
 *
 * @snapshot_id should be 0 if the handle corresponds to a snapshot directory.
 */
static struct kurma_fsal_obj_handle *
alloc_handle(struct kurma_fsal_obj_handle *parent, const char *name,
	     struct fsal_export *exp_hdl, ObjectID *oid, struct attrlist *attrs,
	     int snapshot_id)
{
	struct kurma_fsal_obj_handle *khdl;

	assert(oid);
	khdl = gsh_calloc(1, sizeof(struct kurma_fsal_obj_handle) +
				 V4_FH_OPAQUE_SIZE);
	if (!khdl) {
		KURMA_ERR("Could not allocate handle");
		return NULL;
	}

	khdl->oid = oid;
	/* Establish tree details for this directory */
	khdl->parent = parent;
	khdl->name = gsh_strdup(name);
	khdl->snapshot_id = snapshot_id;
	if (!khdl->name) {
		KURMA_ERR("Could not allocate name");
		goto err;
	}

	/* Create the opaque part of nfsv4 handle */
	/* TODO: build the content on the fly whenever it is needed? */
	khdl->opaque = (char *)&khdl[1]; /* point to second part we allocated */
	package_kurma_handle(khdl->opaque, khdl->oid, snapshot_id);
	KURMA_D(
	    "new handle created for %s with key %llx snapshot_id=%d type=%d",
	    name, CityHash64(khdl->opaque, V4_FH_OPAQUE_SIZE), snapshot_id,
	    extract_object_type(oid));

	fsal_obj_handle_init(&khdl->obj_handle, exp_hdl,
			     extract_object_type(oid));
	set_default_attrs(khdl);
	if (attrs) /* kurma_lookup_path and kurma_create_handle pass NULL */
		set_attrs_override(khdl, attrs);
	khdl->openflags = FSAL_O_CLOSED; // necessary?

	return khdl;

err:
	gsh_free(khdl->name);
	gsh_free(khdl);
	return NULL;
}

static const char SNAPSHOT_DIR_PREFIX[] = "._KURMA_SNAPSHOTS_";
static const size_t SNAPSHOT_DIR_PREFIX_LEN = sizeof(SNAPSHOT_DIR_PREFIX) - 1;

/**
 * Return the file name corresponding to the specified SNAPSHOT dir name.
 */
static const char *get_snapshot_filename(const char *dirname)
{
	return dirname + SNAPSHOT_DIR_PREFIX_LEN;
}

static bool is_snapshot_dirname(const char *name)
{
	return strlen(name) > SNAPSHOT_DIR_PREFIX_LEN &&
	       strncmp(name, SNAPSHOT_DIR_PREFIX, SNAPSHOT_DIR_PREFIX_LEN) == 0;
}

static void set_snapshot_dir_attrs(struct kurma_fsal_obj_handle *khdl)
{
	FSAL_SET_MASK(khdl->obj_handle.attributes.mask, ATTR_TYPE | ATTR_MODE);
	khdl->obj_handle.type = DIRECTORY;
	khdl->obj_handle.attributes.type = DIRECTORY;
	/* Enable directory listing */
	khdl->obj_handle.attributes.mode |= (S_IXOTH | S_IXGRP | S_IXUSR);
	khdl->snapshot_id = SNAPSHOT_DIR;
}

void kurma_try_update_attributes(struct kurma_fsal_obj_handle *khdl,
				 const struct attrlist *attrs)
{
	if (pthread_rwlock_trywrlock(&khdl->obj_handle.lock) == 0) {
		set_attrs_override(khdl, attrs);
		khdl->attrs_timestamp = time(NULL);
		pthread_rwlock_unlock(&khdl->obj_handle.lock);
	}
}

/* lookup a name within directory
 * not used for root handle lookup
 */
static fsal_status_t kurma_lookup(struct fsal_obj_handle *dir_hdl,
				  const char *name,
				  struct fsal_obj_handle **handle)
{
	struct kurma_fsal_obj_handle *parent, *dir_khdl, *khdl;
	ObjectID *oid;
	struct attrlist attrs = { .mask = 0 };
	kurma_st st;
	fsal_status_t ret;
	const char *real_name;
	int snapshot_id = 0;
	int block_shift = 0;
	PERF_DECLARE_COUNTER(klookup);

	if (!dir_hdl->ops->handle_is(dir_hdl, DIRECTORY)) {
		KURMA_ERR("Parent handle is not a directory. hdl=%p", dir_hdl);
		return fsalstat(ERR_FSAL_NOTDIR, 0);
	}

	dir_khdl =
	    container_of(dir_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("LOOKUP '%s' in '%s'", name, dir_khdl->name);
	PERF_START_COUNTER(klookup);

	oid = g_object_new(TYPE_OBJECT_I_D, NULL);

	if (strcmp(name, ".") == 0) {
		parent = dir_khdl->parent;
	} else if (strcmp(name, "..") == 0) {
		parent = NULL;
	} else {
		parent = dir_khdl;
	}

	if (is_snapshot_dir(dir_khdl->snapshot_id)) {
		/* Looking up inside a snapshot directory */
		if (strcmp(name, "..") == 0) {  /* looking for parent */
			memcpy(&attrs, &dir_khdl->parent->obj_handle.attributes,
			       sizeof(attrs));
			copy_object_id(oid, dir_khdl->parent->oid);
			st = KURMA_OKAY;
		} else if (strcmp(name, ".") == 0) {
			memcpy(&attrs, &dir_khdl->obj_handle.attributes, sizeof(attrs));
			copy_object_id(oid, dir_khdl->oid);
			snapshot_id = SNAPSHOT_DIR;
			st = KURMA_OKAY;
		} else {
			st = rpc_lookup_snapshot(dir_khdl->oid, name,
						 &snapshot_id, oid, &attrs);
		}
		real_name = name;
	} else {
		if (is_snapshot_dirname(name)) {
			/* looking for the snapshot dir of a file */
			FSAL_SET_MASK(attrs.mask, ATTR_TYPE);
			attrs.type = DIRECTORY;
			snapshot_id = SNAPSHOT_DIR;
			real_name = get_snapshot_filename(name);
		} else {
			/* looking for object unrelated to snapshot */
			real_name = name;
		}
		st = rpc_lookup(dir_khdl->oid, real_name, oid, &attrs,
				&block_shift);
	}

	if (st != KURMA_OKAY) {
		KURMA_I("fail to lookup '%s'", name);
		ret = kurma_to_fsal_status(st);
		goto err_oid;
	}

	khdl = alloc_handle(parent, name, op_ctx->fsal_export, oid, &attrs,
			    snapshot_id);
	if (!khdl) {
		ret = fsalstat(ERR_FSAL_NOMEM, ENOMEM);
		goto err_oid;
	}

	kurma_try_update_attributes(khdl, &attrs);
	khdl->block_shift = block_shift;
	if (is_snapshot_dirname(name)) {
		set_snapshot_dir_attrs(khdl);
		if (attrs.hints & KURMA_HINT_HAS_SNAPSHOTS) {
			khdl->snapshot_count = -1;
		} else {
			khdl->snapshot_count = 0;
		}
	}
	*handle = &khdl->obj_handle;

	KURMA_D("Found %s/%s hdl=%p size=%zu", dir_khdl->name, name, khdl,
		attrs.filesize);

	PERF_STOP_COUNTER(klookup, 1, true);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);

err_oid:
	g_object_unref(oid);
	PERF_STOP_COUNTER(klookup, 1, false);
	return ret;
}


static void invalidate_dir_contents(struct kurma_fsal_obj_handle *dir_khdl);

// same as makedir, may use one routine
static fsal_status_t kurma_create(struct fsal_obj_handle *dir_hdl,
				  const char *name, struct attrlist *attrs,
				  struct fsal_obj_handle **handle)
{
	struct kurma_fsal_obj_handle *dir_khdl, *khdl;
	ObjectID *oid;
	kurma_st st;
	fsal_status_t ret;
	mode_t unix_mode;
	int snapshot_id = 0;
	PERF_DECLARE_COUNTER(kcreate);

	*handle = NULL; /* poison it */

	if (!dir_hdl->ops->handle_is(dir_hdl, DIRECTORY)) {
		KURMA_ERR("Parent handle is not a directory. hdl = %p",
			  dir_hdl);
		return fsalstat(ERR_FSAL_NOTDIR, 0);
	}

	dir_khdl =
	    container_of(dir_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("CREAT '%s' in '%s'", name, dir_khdl->name);
	PERF_START_COUNTER(kcreate);

	oid = g_object_new(TYPE_OBJECT_I_D, NULL);

	if (is_snapshot_dir(dir_khdl->snapshot_id)) {
		copy_object_id(oid, dir_khdl->oid);
		st = rpc_take_snapshot(oid, name, &snapshot_id, attrs);
		KURMA_D("status: %d snapshot ID=%d name=%s", st, snapshot_id,
			name);
	} else {
		st = rpc_create(dir_khdl->oid, name, attrs, oid);
	}
	if (st != KURMA_OKAY) {
		KURMA_I("fail to create '%s'", name);
		ret = kurma_to_fsal_status(st);
		goto err_oid;
	}

	/* allocate an obj_handle and fill it up */
	khdl = alloc_handle(dir_khdl, name, op_ctx->fsal_export, oid, attrs,
			    snapshot_id);
	if (!khdl) {
		ret = fsalstat(ERR_FSAL_NOMEM, ENOMEM);
		goto err_oid;
	}
	kurma_try_update_attributes(khdl, attrs);

	if (is_snapshot_dir(dir_khdl->snapshot_id)) {
		atomic_inc_int32_t(&dir_khdl->snapshot_count);
	} else if (extract_object_type(oid) == REGULAR_FILE) {
		invalidate_dir_contents(dir_khdl);
	}
	*handle = &khdl->obj_handle;

	PERF_STOP_COUNTER(kcreate, 1, true);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);

err_oid:
	g_object_unref(oid);
	PERF_STOP_COUNTER(kcreate, 1, false);
	return ret;
}

static fsal_status_t kurma_makedir(struct fsal_obj_handle *dir_hdl,
				   const char *name, struct attrlist *attrs,
				   struct fsal_obj_handle **handle)
{
	struct kurma_fsal_obj_handle *dir_khdl, *khdl;
	ObjectID *oid;
	kurma_st st;
	fsal_status_t ret;
	mode_t unix_mode;
	PERF_DECLARE_COUNTER(kmkdir);

	*handle = NULL; /* poison it */

	if (!dir_hdl->ops->handle_is(dir_hdl, DIRECTORY)) {
		KURMA_ERR("Parent handle is not a directory. hdl = %p", dir_hdl);
		return fsalstat(ERR_FSAL_NOTDIR, 0);
	}

	dir_khdl =
	    container_of(dir_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("MKDIR '%s' in '%s'", name, dir_khdl->name);
	PERF_START_COUNTER(kmkdir);

	if (is_snapshot_obj(dir_khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	/* TODO apply umask to attrs->mode?
	unix_mode = fsal2unix_mode(attrs->mode) &
		    ~op_ctx->fsal_export->ops->fs_umask(op_ctx->fsal_export);
	*/

	oid = g_object_new(TYPE_OBJECT_I_D, NULL);
	st = rpc_mkdir(dir_khdl->oid, name, attrs, oid);
	if (st != KURMA_OKAY) {
		KURMA_I("fail to mkdir '%s'", name);
		ret = kurma_to_fsal_status(st);
		goto err_oid;
	}

	/* allocate an obj_handle and fill it up */
	khdl = alloc_handle(dir_khdl, name, op_ctx->fsal_export, oid, attrs, 0);
	if (!khdl) {
		ret = fsalstat(ERR_FSAL_NOMEM, ENOMEM);
		goto err_oid;
	}

	kurma_try_update_attributes(khdl, attrs);
	*handle = &khdl->obj_handle;

	PERF_STOP_COUNTER(kmkdir, 1, true);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);

err_oid:
	g_object_unref(oid);
	PERF_STOP_COUNTER(kmkdir, 1, false);
	return ret;
}

static fsal_status_t kurma_makenode(struct fsal_obj_handle *dir_hdl,
				    const char *name,
				    object_file_type_t nodetype,
				    fsal_dev_t *dev, struct attrlist *attrib,
				    struct fsal_obj_handle **handle)
{
	/* KURMA doesn't support non-directory inodes */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

/**
 *  Note that we do not set mode bits on symlinks for Linux/POSIX
 *  They are not really settable in the kernel and are not checked
 *  anyway (default is 0777) because open uses that target's mode
 */

static fsal_status_t kurma_makesymlink(struct fsal_obj_handle *dir_hdl,
				       const char *name, const char *link_path,
				       struct attrlist *attrib,
				       struct fsal_obj_handle **handle)
{
	fsal_status_t ret;
	size_t write_amount;
	bool fsal_stable;
	fsal_openflags_t openflags = FSAL_O_RDWR;
	struct kurma_fsal_obj_handle *dir_khdl;

	dir_khdl =
	    container_of(dir_hdl, struct kurma_fsal_obj_handle, obj_handle);
	if (is_snapshot_obj(dir_khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	// Setting hints flag, as it is a symbolic link
	attrib->hints = attrib->hints | KURMA_HINT_SYMLINK;

	ret = kurma_create(dir_hdl, name, attrib, handle);
	if (ret.major != ERR_FSAL_NO_ERROR) {
		KURMA_ERR("Failed to create symlink file");
		return ret;
	}
	ret = kurma_open(*handle, openflags);
	if (ret.major != ERR_FSAL_NO_ERROR) {
		KURMA_ERR("Failed to open symlink file");
		return ret;
	}
	ret = kurma_write(*handle, 0, strlen(link_path), (void *)link_path,
			&write_amount, &fsal_stable);
	if (ret.major != ERR_FSAL_NO_ERROR) {
		KURMA_ERR("Failed to write into symlink file");
		goto out;
	}
	KURMA_I("Number of bytes written to symlink file: %zu",
		write_amount);
out:
	if (*handle)
		kurma_close(*handle);

	return ret;
}

static fsal_status_t kurma_readsymlink(struct fsal_obj_handle *obj_hdl,
				       struct gsh_buffdesc *link_content,
				       bool refresh)
{
	fsal_status_t ret;
	size_t read_amount;
	bool end_of_file;
	fsal_openflags_t openflags = FSAL_O_RDWR;
	struct kurma_fsal_obj_handle *khdl;

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	if (is_snapshot_obj(khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	//Reading symlink
	ret = kurma_open(obj_hdl, openflags);
	if (ret.major != ERR_FSAL_NO_ERROR) {
		KURMA_ERR("Failed to open symlink file");
		return ret;
	}
	ret = kurma_read(obj_hdl, 0, 1024, (void *)link_content,
			&read_amount, &end_of_file);
	if (ret.major != ERR_FSAL_NO_ERROR) {
		KURMA_ERR("Failed to read symlink file");
		goto out;
	}
	KURMA_ERR("Number of bytes read from symlink file: %zu",
		read_amount);

out:
	kurma_close(obj_hdl);
	return ret;
}

static fsal_status_t kurma_link(struct fsal_obj_handle *obj_hdl,
				struct fsal_obj_handle *destdir_hdl,
				const char *name)
{
	/* KURMA doesn't support non-directory inodes */
	KURMA_ERR("Invoking unsupported FSAL operation");
	return fsalstat(ERR_FSAL_NOTSUPP, ENOTSUP);
}

/**
 * Get the name of the snapshot directory for file with the given name.
 * TODO: avoid duplicate for long file names close to NAME_MAX.
 */
static char* get_snapshot_dirname(const char *filename, char *buf)
{
	assert(buf);
	strcpy(buf, SNAPSHOT_DIR_PREFIX);
	strcat(buf, filename);
	return buf;
}

/**
 * read_dirents
 * read the directory and call through the callback function for
 * each entry.
 * @param dir_hdl [IN] the directory to read
 * @param whence [IN] where to start (next)
 * @param dir_state [IN] pass thru of state to callback
 * @param cb [IN] callback function
 * @param eof [OUT] eof marker true == end of dir
 */
static fsal_status_t kurma_readdir(struct fsal_obj_handle *dir_hdl,
				   fsal_cookie_t *whence, void *dir_state,
				   fsal_readdir_cb cb, bool *eof)
{
	struct kurma_fsal_obj_handle *dir_khdl, *hdl;
	// fsal_cookie_t seekloc; // TODO support whence?
	KurmaResult *result = NULL;
	kurma_st st;
	int i;
	char *snapshot_dirname = NULL;
	bool listing_snapshots;  // listing snapshots or directories?
	struct attrlist attrs = {0};
	PERF_DECLARE_COUNTER(kreaddir);

	*eof = true;
	dir_khdl =
	    container_of(dir_hdl, struct kurma_fsal_obj_handle, obj_handle);

	listing_snapshots = is_snapshot_dir(dir_khdl->snapshot_id);
	KURMA_D("listing %s: hdl=%p, name=%s",
		(listing_snapshots ? "SNAPSHOTS" : "DIRS"), dir_khdl,
		dir_khdl->name);
	PERF_START_COUNTER(kreaddir);

	if (listing_snapshots) {
		/* Most files does not have snapshots so their SNAPSHOT
		 * directory is empty. */
		if (dir_khdl->snapshot_count == 0) {
			*eof = true;
			return fsalstat(ERR_FSAL_NO_ERROR, 0);
		}
		st = rpc_list_snapshots(dir_khdl->oid, &result, &attrs);
	} else {
		st = rpc_listdir(dir_khdl->oid, &result, &attrs);
	}
	if (st != KURMA_OKAY)
		goto out;
	kurma_try_update_attributes(dir_khdl, &attrs);

	KURMA_D("listed %d entries", result->dir_data->len);
	atomic_store_int32_t(&dir_khdl->snapshot_count, result->dir_data->len);
	for (i = 0; i < result->dir_data->len; i++) {
		DirEntry *dentry = result->dir_data->pdata[i];
		if (!cb(dentry->name, dir_state, 0)) {
			/* cb = populate_dirent() which does lookup*/
			break;
		}
		if (snapshot_enabled && !listing_snapshots &&
		    (dentry->oid->type == REGULAR_FILE)) {
			snapshot_dirname = get_snapshot_dirname(
			    dentry->name, realloc(snapshot_dirname, NAME_MAX));
			if (!cb(snapshot_dirname, dir_state, 0)) {
				break;
			}
		}
	}
	*eof = (i == result->dir_data->len);

out:
	if (result) g_object_unref(result);
	free(snapshot_dirname);
	PERF_STOP_COUNTER(kreaddir, 1, st == KURMA_OKAY);
	return kurma_to_fsal_status(st);
}

static void invalidate_object_attributes(struct fsal_module *fsal,
					 const ObjectID *oid, int snapshot_id)
{
	char buf[V4_FH_OPAQUE_SIZE];
	struct gsh_buffdesc obj = {
		.len = V4_FH_OPAQUE_SIZE,
		.addr = buf,
	};

	KURMA_D("Invalidating attributes of id1=%llu; id2=%llu; type=%d; "
		"snapshot_id=%d",
		oid->id->id1, oid->id->id2, oid->type, snapshot_id);

	package_kurma_handle(buf, oid, snapshot_id);
	op_ctx->fsal_export->up_ops->invalidate(fsal, &obj,
						CACHE_INODE_INVALIDATE_ATTRS);
}

static fsal_status_t kurma_restore_snapshot(
    struct kurma_fsal_obj_handle *src_dir, const char *old_name,
    struct kurma_fsal_obj_handle *dst_dir, const char *new_name)
{
	ObjectID *oid;
	kurma_st st;
	fsal_status_t ret;
	struct attrlist attrs;

	oid = g_object_new(TYPE_OBJECT_I_D, NULL);
	st = rpc_lookup(dst_dir->oid, new_name, oid, &attrs, NULL);
	if (st != KURMA_OKAY) {
		KURMA_I("fail to lookup '%s'", new_name);
		ret = kurma_to_fsal_status(st);
		goto out;
	}

	if (!is_same_file(src_dir->oid, oid)) {
		KURMA_I("snapshot file and target have different oid");
		ret = fsalstat(ERR_FSAL_INVAL, 0);
		goto out;
	}

	st = rpc_restore_snapshot(oid, old_name, NULL, &attrs);
	if (st != KURMA_OKAY) {
		KURMA_I("fail to restore SNAPSHOT '%s'", old_name);
		ret = kurma_to_fsal_status(st);
		goto out;
	}

	invalidate_object_attributes(dst_dir->obj_handle.fsal, oid, 0);
	invalidate_dir_contents(src_dir);
	ret = fsalstat(ERR_FSAL_EXIST, 0);
out:
	g_object_unref(oid);
	return ret;
}

static fsal_status_t kurma_rename(struct fsal_obj_handle *olddir_hdl,
				  const char *old_name,
				  struct fsal_obj_handle *newdir_hdl,
				  const char *new_name)
{
	struct kurma_fsal_obj_handle *src_dir_khdl, *dst_dir_khdl;
	kurma_st st;
	struct attrlist attrs = {0};
	PERF_DECLARE_COUNTER(krename);

	src_dir_khdl =
	    container_of(olddir_hdl, struct kurma_fsal_obj_handle, obj_handle);
	dst_dir_khdl =
	    container_of(newdir_hdl, struct kurma_fsal_obj_handle, obj_handle);

	KURMA_D(
	    "RENAME src_dir_hdl=%p; src_name=%s; dst_dir_hdl=%p; dst_name=%s",
	    src_dir_khdl, old_name, dst_dir_khdl, new_name);
	PERF_START_COUNTER(krename);

	if (is_snapshot_dir(src_dir_khdl->snapshot_id)) {
		KURMA_D("restoring file '%s' from snapshot '%s'", new_name,
			old_name);
		return kurma_restore_snapshot(src_dir_khdl, old_name,
					      dst_dir_khdl, new_name);
	}

	// does not handle parent/name field (to be removed) in obj_handle
	st = rpc_rename(src_dir_khdl->oid, old_name, dst_dir_khdl->oid,
			new_name, &attrs);
	if (st != KURMA_OKAY) {
		KURMA_ERR("fail to rename '%s' to '%s': %d", old_name, new_name,
			  st);
	}
	kurma_try_update_attributes(dst_dir_khdl, &attrs);

	PERF_STOP_COUNTER(krename, 1, st == KURMA_OKAY);
	return kurma_to_fsal_status(st);
}

static fsal_status_t kurma_getattrs(struct fsal_obj_handle *obj_hdl)
{
	struct kurma_fsal_obj_handle *khdl;
	struct attrlist attrs = {0};
	kurma_st st;
	int block_shift = 0;
	PERF_DECLARE_COUNTER(kgetattrs);

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	PERF_START_COUNTER(kgetattrs);

	if (time(NULL) - khdl->attrs_timestamp <= KURMA_ATTRS_EXPIRE_SEC) {
		st = KURMA_OKAY;
		goto exit;
	}

	if (khdl->oid->id->id1 == deleted_obj_id1 &&
	    khdl->oid->id->id2 == deleted_obj_id2 &&
	    khdl->oid->creator == deleted_obj_creator) {
		st = KURMA_ERROR_OBJECT_NOT_FOUND;
		goto exit;
	}

	if (is_snapshot_obj(khdl->snapshot_id)) {
		if (obj_hdl->type == DIRECTORY) {
			assert(khdl->obj_handle.type == DIRECTORY);
			st = rpc_getattrs(khdl->oid, &attrs, &block_shift);
		} else {
			assert(khdl->snapshot_id != 0);
			st = rpc_lookup_snapshot(
			    khdl->oid, NULL, &khdl->snapshot_id, NULL, &attrs);
		}
	} else {
		st = rpc_getattrs(khdl->oid, &attrs, &block_shift);
	}

	if (st == KURMA_OKAY) {
		khdl->attrs_timestamp = time(NULL);
		set_attrs_override(khdl, &attrs);
		khdl->block_shift = block_shift;
		if (is_snapshot_dir(khdl->snapshot_id)) {
			set_snapshot_dir_attrs(khdl);
			atomic_store_int32_t(&khdl->snapshot_count, -1);
		}
	} else {
		KURMA_I("fail to getattrs; hdl=%p", khdl);
	}

exit:
	PERF_STOP_COUNTER(kgetattrs, 1, st == KURMA_OKAY);
	KURMA_D("GETATTRS hdl=%p; name=%s; ctime=%ld", khdl, khdl->name,
			timespec_to_nsecs(&attrs.ctime));

	return kurma_to_fsal_status(st);
}

/*
 * NOTE: this is done under protection of the attributes rwlock
 *       in the cache entry.
 */
static fsal_status_t kurma_setattrs(struct fsal_obj_handle *obj_hdl,
				    struct attrlist *attrs)
{
	struct kurma_fsal_obj_handle *khdl;
	kurma_st st;
	PERF_DECLARE_COUNTER(ksetattrs);

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("SETATTRS hdl=%p; name=%s", khdl, khdl->name);
	PERF_START_COUNTER(ksetattrs);

	if (is_snapshot_obj(khdl->snapshot_id)) {
		return fsalstat(ERR_FSAL_PERM, 0);
	}

	st = rpc_setattrs(khdl->oid, attrs);
	if (st == KURMA_OKAY) {
		/* Even though client may issue a subsequent getattrs,
		 * for consistency, we update attrs here before that call.
		 * Assume server will return updated attrs. */
		set_attrs_override(khdl, attrs);
	} else {
		KURMA_I("fail to setattrs, hdl=%p", khdl);
	}
	kurma_try_update_attributes(khdl, attrs);

	PERF_STOP_COUNTER(ksetattrs, 1, st == KURMA_OKAY);
	return kurma_to_fsal_status(st);
}

static void invalidate_dir_contents(struct kurma_fsal_obj_handle *dir_khdl)
{
	struct gsh_buffdesc obj;

	KURMA_D("Invalidating directory content");

	obj.len = V4_FH_OPAQUE_SIZE;
	obj.addr = dir_khdl->opaque;

	op_ctx->fsal_export->up_ops->invalidate(
	    dir_khdl->obj_handle.fsal, &obj,
	    CACHE_INODE_INVALIDATE_ATTRS | CACHE_INODE_INVALIDATE_CONTENT);
}

/*
 * unlink the named file in the directory
 */
static fsal_status_t kurma_unlink(struct fsal_obj_handle *dir_hdl,
				  const char *name)
{
	struct kurma_fsal_obj_handle *dir_khdl;
	kurma_st st;
	ObjectID *oid;
	PERF_DECLARE_COUNTER(kunlink);
	struct attrlist attrs = {0};

	dir_khdl =
	    container_of(dir_hdl, struct kurma_fsal_obj_handle, obj_handle);

	KURMA_D("UNLINK hdl=%p; name=%s", dir_khdl, name);
	PERF_START_COUNTER(kunlink);

	if (is_snapshot_dirname(name)) { // Deleting snapshot directory is a no-op.
		return fsalstat(ERR_FSAL_NO_ERROR, 0);
	}

	oid = g_object_new(TYPE_OBJECT_I_D, NULL);
	if (is_snapshot_dir(dir_khdl->snapshot_id)) {
		st = rpc_delete_snapshot(dir_khdl->oid, name, &attrs);
	} else {
		st = rpc_unlink(dir_khdl->oid, name, oid, &attrs);
	}
	if (st != KURMA_OKAY) {
		KURMA_I("fail to getattrs; hdl=%p", dir_khdl);
		goto out;
	}
	if (!is_snapshot_dir(dir_khdl->snapshot_id)) {
	    deleted_obj_id1 = oid->id->id1;
	    deleted_obj_id2 = oid->id->id2;
	    deleted_obj_creator = oid->creator;
	}
	kurma_try_update_attributes(dir_khdl, &attrs);

	if (snapshot_enabled) {
		if (is_snapshot_dir(dir_khdl->snapshot_id)) {
			atomic_dec_int32_t(&dir_khdl->snapshot_count);
		} else if (extract_object_type(oid) == REGULAR_FILE) {
			invalidate_dir_contents(dir_khdl);
		}
	}

out:
	g_object_unref(oid);
	PERF_STOP_COUNTER(kunlink, 1, st == KURMA_OKAY);
	return kurma_to_fsal_status(st);
}

/* handle_digest
 * fill in the opaque f/s file handle part.
 * we zero the buffer to length first.  This MAY already be done above
 * at which point, remove memset here because the caller is zeroing
 * the whole struct.
 */

static fsal_status_t handle_digest(const struct fsal_obj_handle *obj_hdl,
				   fsal_digesttype_t output_type,
				   struct gsh_buffdesc *fh_desc)
{
	const struct kurma_fsal_obj_handle *khdl;

	khdl = container_of(obj_hdl, const struct kurma_fsal_obj_handle,
			    obj_handle);

	switch (output_type) {
	case FSAL_DIGEST_NFSV3:
	case FSAL_DIGEST_NFSV4:
		if (fh_desc->len < V4_FH_OPAQUE_SIZE) {
			KURMA_ERR(
			    "Space too small for handle. need %lu, have %lu",
			    V4_FH_OPAQUE_SIZE, fh_desc->len);
			return fsalstat(ERR_FSAL_TOOSMALL, 0);
		}

		memcpy(fh_desc->addr, khdl->opaque, V4_FH_OPAQUE_SIZE);
		fh_desc->len = V4_FH_OPAQUE_SIZE;
		break;

	default:
		return fsalstat(ERR_FSAL_SERVERFAULT, 0);
	}

	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/**
 * handle_to_key
 * return a handle descriptor into the handle in this object handle
 * @TODO reminder.  make sure things like hash keys don't point here
 * after the handle is released.
 */

static void handle_to_key(struct fsal_obj_handle *obj_hdl,
			  struct gsh_buffdesc *fh_desc)
{
	struct kurma_fsal_obj_handle *khdl;

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);

	fh_desc->addr = khdl->opaque;
	fh_desc->len = V4_FH_OPAQUE_SIZE;
}

/*
 * release
 * release our export first so they know we are gone
 */

static void kurma_release(struct fsal_obj_handle *obj_hdl)
{
	struct kurma_fsal_obj_handle *khdl;

	khdl = container_of(obj_hdl, struct kurma_fsal_obj_handle, obj_handle);
	KURMA_D("Releasing hdl=%p; name=%s", khdl, khdl->name);

	fsal_obj_handle_uninit(obj_hdl);
	gsh_free(khdl->name);
	g_object_unref(khdl->oid);
	gsh_free(khdl);
}

void kurma_handle_ops_init(struct fsal_obj_ops *ops)
{
	ops->release = kurma_release;
	ops->lookup = kurma_lookup;
	ops->readdir = kurma_readdir;
	ops->create = kurma_create;
	ops->mkdir = kurma_makedir;
	ops->mknode = kurma_makenode;
	ops->symlink = kurma_makesymlink;
	ops->readlink = kurma_readsymlink;
	ops->test_access = fsal_test_access;
	ops->getattrs = kurma_getattrs;
	ops->setattrs = kurma_setattrs;
	ops->link = kurma_link;
	ops->rename = kurma_rename;
	ops->unlink = kurma_unlink;
	ops->open = kurma_open;
	ops->status = kurma_status;
	ops->read = kurma_read;
	ops->write = kurma_write;
	ops->commit = kurma_commit;
	ops->lock_op = kurma_lock_op;
	ops->close = kurma_close;
	ops->lru_cleanup = kurma_lru_cleanup;
	ops->handle_digest = handle_digest;
	ops->handle_to_key = handle_to_key;

	/* xattr related functions */
	ops->list_ext_attrs = kurma_list_ext_attrs;
	ops->getextattr_id_by_name = kurma_getextattr_id_by_name;
	ops->getextattr_value_by_name = kurma_getextattr_value_by_name;
	ops->getextattr_value_by_id = kurma_getextattr_value_by_id;
	ops->setextattr_value = kurma_setextattr_value;
	ops->setextattr_value_by_id = kurma_setextattr_value_by_id;
	ops->getextattr_attrs = kurma_getextattr_attrs;
	ops->remove_extattr_by_id = kurma_remove_extattr_by_id;
	ops->remove_extattr_by_name = kurma_remove_extattr_by_name;
}

/* export methods that create object handles
 */

/* lookup_path
 * Lookup only root path during export intialization
 */
fsal_status_t kurma_lookup_path(struct fsal_export *exp_hdl, const char *path,
				struct fsal_obj_handle **handle)
{
	struct kurma_fsal_export *myself;
	KURMA_D("LOOKUP_ROOT_PATH %s", path);

	myself = container_of(exp_hdl, struct kurma_fsal_export, export);

	if (strcmp(path, myself->export_path) != 0) {
		/* Lookup of a path other than the export's root. */
		KURMA_ERR("Attempt to lookup non-root path %s", path);
		return fsalstat(ERR_FSAL_NOENT, ENOENT);
	}

	if (myself->root_handle == NULL) {
		ObjectID *oid = g_object_new(TYPE_OBJECT_I_D, NULL);
		kurma_st st = rpc_lookup(NULL, path, oid, NULL, NULL);
		if (st != KURMA_OKAY) {
			KURMA_ERR("fail to lookup root path %s", path);
			g_object_unref(oid);
			return kurma_to_fsal_status(st);
		}

		myself->root_handle = alloc_handle(NULL, myself->export_path,
						   exp_hdl, oid, NULL, 0);
		if (!myself->root_handle) {
			/* alloc handle failed. */
			g_object_unref(oid);
			return fsalstat(ERR_FSAL_NOMEM, ENOMEM);
		}
	}

	*handle = &myself->root_handle->obj_handle;

	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/* create_handle
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

fsal_status_t kurma_create_handle(struct fsal_export *exp_hdl,
				  struct gsh_buffdesc *hdl_desc,
				  struct fsal_obj_handle **handle)
{
	struct glist_head *glist;
	struct fsal_obj_handle *hdl;
	struct kurma_fsal_obj_handle *khdl;
	ObjectID *oid;
	int snapshot_id = 0;

	*handle = NULL;

	if (hdl_desc->len != V4_FH_OPAQUE_SIZE) {
		KURMA_ERR("Invalid handle size %lu expected %lu",
			  (long unsigned)hdl_desc->len, V4_FH_OPAQUE_SIZE);

		return fsalstat(ERR_FSAL_BADHANDLE, 0);
	}

	PTHREAD_RWLOCK_rdlock(&exp_hdl->fsal->lock);

	// TODO find use case for this code, otherwise remove it
	glist_for_each(glist, &exp_hdl->fsal->handles)
	{
		hdl = glist_entry(glist, struct fsal_obj_handle, handles);
		khdl =
		    container_of(hdl, struct kurma_fsal_obj_handle, obj_handle);

		if (memcmp(khdl->opaque, hdl_desc->addr, V4_FH_OPAQUE_SIZE) ==
		    0) {
			KURMA_D("Found hdl=%p; name=%s", khdl, khdl->name);
			*handle = hdl;
			PTHREAD_RWLOCK_unlock(&exp_hdl->fsal->lock);
			return fsalstat(ERR_FSAL_NO_ERROR, 0);
		}
	}

	PTHREAD_RWLOCK_unlock(&exp_hdl->fsal->lock);

	KURMA_D("Could not find handle in memory, construct one");
	oid = g_object_new(TYPE_OBJECT_I_D, NULL);
	extract_kurma_handle(hdl_desc->addr, oid, &snapshot_id);
	khdl = alloc_handle(NULL, "RECOVERED_HANDLE", op_ctx->fsal_export, oid,
			    NULL, 0);
	if (!khdl) {
		g_object_unref(oid);
		return fsalstat(ERR_FSAL_NOMEM, ENOMEM);
	}

	*handle = &khdl->obj_handle;
	return fsalstat(ERR_FSAL_NO_ERROR, 0);

	/* we do not validate staleness here.
	 * return fsalstat(ERR_FSAL_STALE, ESTALE);*/
}
