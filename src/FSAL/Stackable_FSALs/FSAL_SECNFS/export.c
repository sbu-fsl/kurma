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
/* export.c
 * SECNFS FSAL export object
 */

#include "config.h"

#include "fsal.h"
#include <pthread.h>
#include <string.h>
#include <sys/types.h>
#include <os/mntent.h>
#include <os/quota.h>
#include <dlfcn.h>
#include "config_parsing.h"
#include "fsal_convert.h"
#include "FSAL/fsal_commonlib.h"
#include "FSAL/fsal_config.h"
#include "fsal_handle_syscalls.h"
#include "secnfs_methods.h"
#include "export_mgr.h"
#include "nfs_exports.h"

/* helpers to/from other SECNFS objects
 */

struct fsal_staticfsinfo_t *secnfs_staticinfo(struct fsal_module *hdl);
extern struct next_ops next_ops;

/********************** export object methods ********************/

static void release(struct fsal_export *export)
{
        struct secnfs_fsal_export *exp = secnfs_export(export);
        struct fsal_module *sub_fsal = exp->next_export->fsal;

        next_ops.exp_ops->release(exp->next_export);
        fsal_put(sub_fsal);

        fsal_detach_export(export->fsal, &export->exports);
        free_export_ops(export);

        gsh_free(exp);
}

static fsal_status_t secnfs_get_dynamic_info(struct fsal_export *exp_hdl,
					     struct fsal_obj_handle *obj_hdl,
					     fsal_dynamicfsinfo_t *infop)
{
        return next_ops.exp_ops->get_fs_dynamic_info(next_export(exp_hdl),
                                                     next_handle(obj_hdl),
                                                     infop);
}

static bool fs_supports(struct fsal_export *exp_hdl,
			fsal_fsinfo_options_t option)
{
        return next_ops.exp_ops->fs_supports(next_export(exp_hdl), option);
}

static uint64_t fs_maxfilesize(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_maxfilesize(next_export(exp_hdl));
}

static uint32_t fs_maxread(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_maxread(next_export(exp_hdl));
}

static uint32_t fs_maxwrite(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_maxwrite(next_export(exp_hdl));
}

static uint32_t fs_maxlink(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_maxlink(next_export(exp_hdl));
}

static uint32_t fs_maxnamelen(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_maxnamelen(next_export(exp_hdl));
}

static uint32_t fs_maxpathlen(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_maxpathlen(next_export(exp_hdl));
}

static struct timespec fs_lease_time(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_lease_time(next_export(exp_hdl));
}

static fsal_aclsupp_t fs_acl_support(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_acl_support(next_export(exp_hdl));
}

static attrmask_t fs_supported_attrs(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_supported_attrs(next_export(exp_hdl));
}

static uint32_t fs_umask(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_umask(next_export(exp_hdl));
}

static uint32_t fs_xattr_access_rights(struct fsal_export *exp_hdl)
{
        return next_ops.exp_ops->fs_xattr_access_rights(next_export(exp_hdl));
}

/* get_quota
 * return quotas for this export.
 * path could cross a lower mount boundary which could
 * mask lower mount values with those of the export root
 * if this is a real issue, we can scan each time with setmntent()
 * better yet, compare st_dev of the file with st_dev of root_fd.
 * on linux, can map st_dev -> /proc/partitions name -> /dev/<name>
 */

static fsal_status_t get_quota(struct fsal_export *exp_hdl,
                               const char *filepath, int quota_type,
                               fsal_quota_t * pquota)
{
        return next_ops.exp_ops->get_quota(next_export(exp_hdl), filepath,
                                           quota_type, pquota);
}

/* set_quota
 * same lower mount restriction applies
 */

static fsal_status_t set_quota(struct fsal_export *exp_hdl,
                               const char *filepath, int quota_type,
                               fsal_quota_t * pquota, fsal_quota_t * presquota)
{
        return next_ops.exp_ops->set_quota(next_export(exp_hdl), filepath,
                                           quota_type, pquota,
                                           presquota);
}

/* extract a file handle from a buffer.
 * do verification checks and flag any and all suspicious bits.
 * Return an updated fh_desc into whatever was passed.  The most
 * common behavior, done here is to just reset the length.  There
 * is the option to also adjust the start pointer.
 */

static fsal_status_t secnfs_extract_handle(struct fsal_export *exp_hdl,
					   fsal_digesttype_t in_type,
					   struct gsh_buffdesc *fh_desc)
{
        return next_ops.exp_ops->extract_handle(next_export(exp_hdl),
                                                in_type, fh_desc);
}

static bool secnfs_get_supports(struct fsal_export *exp_hdl,
				fsal_fsinfo_options_t option)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_supports(&pm->fs_info, option);
}

static uint64_t secnfs_get_maxfilesize(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_maxfilesize(&pm->fs_info);
}

static uint32_t secnfs_get_maxread(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_maxread(&pm->fs_info);
}

static uint32_t secnfs_get_maxwrite(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_maxwrite(&pm->fs_info);
}

static uint32_t secnfs_get_maxlink(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_maxlink(&pm->fs_info);
}

static uint32_t secnfs_get_maxnamelen(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_maxnamelen(&pm->fs_info);
}

static uint32_t secnfs_get_maxpathlen(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_maxpathlen(&pm->fs_info);
}

static struct timespec secnfs_get_lease_time(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_lease_time(&pm->fs_info);
}

static fsal_aclsupp_t secnfs_get_acl_support(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_acl_support(&pm->fs_info);
}

static attrmask_t secnfs_get_supported_attrs(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_supported_attrs(&pm->fs_info);
}

static uint32_t secnfs_get_umask(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_umask(&pm->fs_info);
}

static uint32_t secnfs_get_xattr_access_rights(struct fsal_export *exp_hdl)
{
	struct secnfs_fsal_module *pm =
	    container_of(exp_hdl->fsal, struct secnfs_fsal_module, fsal);
	return fsal_xattr_access_rights(&pm->fs_info);
}

/* secnfs_export_ops_init
 * overwrite vector entries with the methods that we support
 */

void secnfs_export_ops_init(struct export_ops *ops)
{
	ops->release = release;
	ops->lookup_path = secnfs_lookup_path;
	ops->extract_handle = secnfs_extract_handle;
	ops->create_handle = secnfs_create_handle;
	ops->get_fs_dynamic_info = secnfs_get_dynamic_info;
	ops->fs_supports = secnfs_get_supports;
	ops->fs_maxfilesize = secnfs_get_maxfilesize;
	ops->fs_maxread = secnfs_get_maxread;
	ops->fs_maxwrite = secnfs_get_maxwrite;
	ops->fs_maxlink = secnfs_get_maxlink;
	ops->fs_maxnamelen = secnfs_get_maxnamelen;
	ops->fs_maxpathlen = secnfs_get_maxpathlen;
	ops->fs_lease_time = secnfs_get_lease_time;
	ops->fs_acl_support = secnfs_get_acl_support;
	ops->fs_supported_attrs = secnfs_get_supported_attrs;
	ops->fs_umask = secnfs_get_umask;
	ops->fs_xattr_access_rights = secnfs_get_xattr_access_rights;
	ops->get_quota = get_quota;
	ops->set_quota = set_quota;
}

void secnfs_handle_ops_init(struct fsal_obj_ops *ops);

extern struct fsal_up_vector fsal_up_top;

struct secnfsfsal_args {
        struct subfsal_args subfsal;
};

/* used by next_fsal to create its export */
static struct config_item sub_fsal_params[] = {
	CONF_ITEM_STR("name", 1, 10, NULL, subfsal_args, name),
	CONFIG_EOL
};

static struct config_item export_params[] = {
	CONF_ITEM_NOOP("name"),
	CONF_ITEM_BLOCK("FSAL", sub_fsal_params,
			 noop_conf_init, subfsal_commit,
			 secnfsfsal_args, subfsal),
	CONFIG_EOL
};

static struct config_block export_param = {
	.dbus_interface_name = "org.ganesha.nfsd.config.fsal.secnfs-export%d",
	.blk_desc.name = "FSAL",
	.blk_desc.type = CONFIG_BLOCK,
	.blk_desc.u.blk.init = noop_conf_init,
	.blk_desc.u.blk.params = export_params,
	.blk_desc.u.blk.commit = noop_conf_commit
};

/* create_export
 * Create an export point and return a handle to it to be kept
 * in the export list.
 * First lookup the fsal, then create the export and then put the fsal back.
 * returns the export with one reference taken.
 */
fsal_status_t secnfs_create_export(struct fsal_module *fsal_hdl,
				   void *parse_node,
                                   struct fsal_up_vector *up_ops)
{
        fsal_status_t st;
        struct secnfs_fsal_export *exp;
        struct fsal_module *next_fsal;
	struct secnfsfsal_args secnfsfsal;
	struct config_error_type err_type;
	int retval;

	retval = load_config_from_node(parse_node,
				       &export_param,
				       &secnfsfsal,
				       true,
				       &err_type);
	if (retval != 0)
		return fsalstat(ERR_FSAL_INVAL, 0);

        next_fsal = lookup_fsal(secnfsfsal.subfsal.name);
        if (next_fsal == NULL) {
		LogMajor(COMPONENT_FSAL,
			 "secnfs_create_export: failed to lookup for FSAL %s",
			 secnfsfsal.subfsal.name);
		return fsalstat(ERR_FSAL_INVAL, EINVAL);
	}

        exp = gsh_calloc(1, sizeof(*exp));
        if (!exp) {
                LogMajor(COMPONENT_FSAL, "Out of Memory");
                return fsalstat(ERR_FSAL_NOMEM, ENOMEM);
        }

        st = next_fsal->ops->create_export(next_fsal,
                                           secnfsfsal.subfsal.fsal_node,
                                           up_ops);
        fsal_put(next_fsal);
        if (FSAL_IS_ERROR(st)) {
                LogMajor(COMPONENT_FSAL,
                         "Failed to call create_export on underlying FSAL %s",
                          secnfsfsal.subfsal.name);
                gsh_free(exp);
                return st;
        }

        /* op_ctx->fsal_export is set during next_fsal create_export */
        exp->next_export = op_ctx->fsal_export;
        /* Init next_ops structure */
        /* FIXME are the memory released? It is okay for now as next_ops is a
         * static variable with only one instance. */
        next_ops.exp_ops = gsh_malloc(sizeof(struct export_ops));
        next_ops.obj_ops = gsh_malloc(sizeof(struct fsal_obj_ops));
        next_ops.ds_ops = gsh_malloc(sizeof(struct fsal_ds_ops));

        memcpy(next_ops.exp_ops,
               exp->next_export->ops,
               sizeof(struct export_ops));
        memcpy(next_ops.obj_ops,
               exp->next_export->obj_ops,
               sizeof(struct fsal_obj_ops));
        memcpy(next_ops.ds_ops,
               exp->next_export->ds_ops,
               sizeof(struct fsal_ds_ops));
        next_ops.up_ops = up_ops;

        retval = fsal_export_init(&exp->export);
        if (retval) {
                gsh_free(exp);
                return fsalstat(ERR_FSAL_NOMEM, ENOMEM);
        }
        secnfs_export_ops_init(exp->export.ops);
        secnfs_handle_ops_init(exp->export.obj_ops);
        exp->export.up_ops = up_ops;
        exp->export.fsal = fsal_hdl;

        op_ctx->fsal_export = &exp->export;
        SECNFS_D("secnfs export created.\n");

        return fsalstat(ERR_FSAL_NO_ERROR, 0);
}
