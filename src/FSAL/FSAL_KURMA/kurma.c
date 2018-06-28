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
/**
 * vim:noexpandtab:shiftwidth=8:tabstop=8:
 */

#include "kurma.h"
#include "fsal.h"
#include "xxhash.h"
#include <pthread.h>

// FIXME: each thread maintains a client?
__thread KurmaServiceIf *client = NULL;
__thread ThriftProtocol *protocol;
__thread ThriftSocket *tsocket;
__thread ThriftTransport *transport;

static char *kurma_server;
static int kurma_port;
static GByteArray *sessionid;

static pthread_t rpc_renewer_thread;

static uint32_t xxhash(GByteArray *data)
{
	return XXH32(data->data, data->len, 0);
}

static inline void kurma_result_to_chgtime(const KurmaResult *result,
					   struct timespec *chgtime)
{
	if (result->new_attrs->__isset_remote_change_time) {
		nsecs_to_timespec((nsecs_elapsed_t)result->new_attrs->remote_change_time,
				  chgtime);
	} else {
		chgtime->tv_sec = 0;
		chgtime->tv_nsec = 0;
	}
}

static inline void kurma_result_to_fsal_attrs(const KurmaResult *result,
					      struct attrlist *attrs)
{
	kurma_attrs_to_fsal(result->new_attrs, attrs);
}

int rpc_init(kurma_specific_initinfo_t *params)
{
	kurma_st st;

	kurma_server = gsh_strdup(params->server);
	kurma_port = params->port;

	st = rpc_create_session(params->clientid, params->volumeid);
	if (st != KURMA_OKAY) {
		KURMA_ERR("fail to create_session for '%s, %s'",
			  params->clientid, params->volumeid);
	} else {
		int err;
		err = pthread_create(&rpc_renewer_thread, NULL,
				     rpc_sessionid_renewer, params);
		if (err) {
			KURMA_ERR("Cannot create sessionid renewer thread - %s",
				  strerror(err));
			st = KURMA_INVALID;
		}
	}

	return st;
}
// TODO uninit

KurmaServiceIf *get_client()
{
	// TODO may need to reconnect
	if (client)
		return client;

	GError *error = NULL;
	tsocket = g_object_new(THRIFT_TYPE_SOCKET, "hostname", kurma_server,
			       "port", kurma_port, NULL);
	transport = g_object_new(THRIFT_TYPE_BUFFERED_TRANSPORT, "transport",
				 tsocket, NULL);
	protocol = g_object_new(THRIFT_TYPE_BINARY_PROTOCOL, "transport",
				transport, NULL);

	thrift_transport_open(transport, &error);
	if (error) {
		KURMA_ERR("%s", error->message);
		g_clear_error(&error);
	}

	client = g_object_new(TYPE_KURMA_SERVICE_CLIENT, "input_protocol",
			      protocol, "output_protocol", protocol, NULL);
	return client;
}

int rpc_create_session(char *clientid, const char *volumeid)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	GByteArray *g_clientid;
	kurma_st st = KURMA_RPC_ERR;

	g_clientid = g_byte_array_new_take(clientid, strlen(clientid));

	if (kurma_service_if_create_session(get_client(), &result, g_clientid,
					    volumeid, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			assert(result->__isset_sessionid);
			assert(result->sessionid->len > 0);
			sessionid = g_byte_array_new_take(
			    g_strndup(result->sessionid->data,
				      result->sessionid->len),
			    result->sessionid->len);
			KURMA_I("sessionid returned: %08x", xxhash(sessionid));
		} else {
			KURMA_RPC_STATUS("CREATE_SESSION", result);
		}
	} else {
		/* empty error occurs when required field of thrift is unset */
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

	g_byte_array_free(g_clientid, FALSE); /* do not free actual byte */
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

void *rpc_sessionid_renewer(void *arg)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_specific_initinfo_t *params = arg;

	if (params->renew_interval == 0) {
		KURMA_I("will not renew session_id (used for debug ONLY)");
		return NULL;
	}

	for (;;) {
		sleep(params->renew_interval);
		if (kurma_service_if_renew_session(get_client(), &result,
						   sessionid, &error)) {
			if (result->status->errcode != 0)
				KURMA_RPC_STATUS("RENEW_SESSION", result);
		} else {
			KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
		}
		g_clear_error(&error);
	}

	g_object_unref(result);
	return NULL;
}

// TODO may pass obj_attrs so that lookup returns attrs as well, depending on
// implementation on server side.
int rpc_lookup(const ObjectID *dir_oid, const char *path, ObjectID *oid,
	       struct attrlist *attrs, int *block_shift)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	ObjectID *e_dir_oid = (ObjectID *)dir_oid;
	kurma_st st = KURMA_RPC_ERR;

	if (dir_oid == NULL) { /* root path lookup */
		e_dir_oid = g_object_new(TYPE_OBJECT_I_D, NULL);
		set_root_oid(e_dir_oid);
	}

	if (kurma_service_if_lookup(get_client(), &result, sessionid, e_dir_oid,
				    path, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			assert(result->dir_data->len == 1);
			DirEntry *dentry = result->dir_data->pdata[0];
			copy_object_id(oid, dentry->oid);
			if (attrs) {
				kurma_result_to_fsal_attrs(result, attrs);
				FSAL_TEST_MASK(attrs->mask, ATTR_TYPE);
				attrs->type = extract_object_type(oid);
			}
			if (block_shift) {
				*block_shift = result->new_attrs->block_shift;
			}
		} else {
			KURMA_RPC_STATUS("LOOKUP", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	if (dir_oid == NULL) {
		g_object_unref(e_dir_oid);
	}
	return st;
}

int rpc_getattrs(const ObjectID *oid, struct attrlist *attrs, int *block_shift)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_getattrs(get_client(), &result, sessionid, oid,
				      &error)) {
		st = result->status->errcode;
		if (st == 0) {
			kurma_result_to_fsal_attrs(result, attrs);
			if (block_shift) {
				*block_shift = result->new_attrs->block_shift;
			}
		} else {
			KURMA_RPC_STATUS("GETATTRS", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_take_snapshot(const ObjectID *oid, const char *snapshot_name,
		      int *snapshot_id, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_take_snapshot(get_client(), &result, sessionid,
					   oid, snapshot_name, NULL, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			kurma_result_to_fsal_attrs(result, attrs);
			millisecond_to_timespec(result->snapshot_time, &attrs->ctime);
			if (snapshot_id) {
				*snapshot_id = result->snapshot_id;
			}
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_lookup_snapshot(const ObjectID *dir_oid, const char *snapshot_name,
			int *snapshot_id, ObjectID *oid, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_lookup_snapshot(
		get_client(), &result, sessionid, dir_oid, snapshot_name,
		(snapshot_id ? *snapshot_id : 0), &error)) {
		st = result->status->errcode;
		if (st == 0) {
			kurma_result_to_fsal_attrs(result, attrs);
			millisecond_to_timespec(result->snapshot_time, &attrs->ctime);
			if (oid) {
				copy_object_id(oid, dir_oid);
			}
			if (snapshot_id) {
				*snapshot_id = result->snapshot_id;
			}
			KURMA_D("snapshot %s found: ID=%d, size=%zu, type=%d",
				(snapshot_name ? snapshot_name : "NULL"),
				result->snapshot_id,
				result->new_attrs->filesize,
				(oid ? extract_object_type(oid) : -1));
		} else {
			KURMA_RPC_STATUS("LOOKUP_SNAPSHOT", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_delete_snapshot(const ObjectID *oid, const char *snapshot_name,
			struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_delete_snapshot(get_client(), &result, sessionid,
					     oid, snapshot_name, 0, &error)) {
		st = result->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("DELETE_SNAPSHOT", result);
		} else if (attrs) {
			kurma_result_to_fsal_attrs(result, attrs);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_list_snapshots(const ObjectID *oid, KurmaResult **result,
		       struct attrlist *attrs)
{
	*result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_list_snapshots(get_client(), result, sessionid,
					    oid, &error)) {
		st = (*result)->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("LIST_SNAPSHOTS", *result);
		} else if (attrs) {
			kurma_result_to_fsal_attrs(*result, attrs);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	return st;
}

int rpc_restore_snapshot(const ObjectID *oid, const char *snapshot_name,
			 int *snapshot_id, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_restore_snapshot(
		get_client(), &result, sessionid, oid, snapshot_name,
		(snapshot_id ? *snapshot_id : 0), &error)) {
		st = result->status->errcode;
		if (st == 0) {
			kurma_result_to_fsal_attrs(result, attrs);
			if (snapshot_id) {
				*snapshot_id = result->snapshot_id;
			}
		} else {
			KURMA_RPC_STATUS("RESTORE_SNAPSHOT", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

// IN/OUT attrs
int rpc_setattrs(const ObjectID *oid, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;
	ObjectAttributes *k_attrs = g_object_new(TYPE_OBJECT_ATTRIBUTES, NULL);

	fsal_attrs_to_kurma(attrs, k_attrs);

	if (kurma_service_if_setattrs(get_client(), &result, sessionid, oid,
				      k_attrs, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			kurma_result_to_fsal_attrs(result, attrs);
		} else {
			KURMA_RPC_STATUS("SETATTRS", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	g_object_unref(k_attrs);
	return st;
}

/* caller is responsible to free the result regardless of return value of rpc
 */
int rpc_listdir(const ObjectID *oid, KurmaResult **result,
		struct attrlist *attrs)
{
	*result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_listdir(get_client(), result, sessionid, oid,
				     &error)) {
		st = (*result)->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("LISTDIR", *result);
		} else if (attrs) {
			kurma_result_to_fsal_attrs(*result, attrs);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	return st;
}

/**
 * Open the specified Kurma file and set "chgtime" to the latest change time by
 * remote Kurma middlewares.
 */
int rpc_open(const ObjectID *oid, fsal_openflags_t flags,
	     struct timespec *chgtime, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_open(get_client(), &result, sessionid, oid, flags,
				  &error)) {
		st = result->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("OPEN", result);
		} else {
			kurma_result_to_chgtime(result, chgtime);
			if (attrs) {
				kurma_result_to_fsal_attrs(result, attrs);
			}
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_close(const ObjectID *oid, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_close(get_client(), &result, sessionid, oid,
				   &error)) {
		st = result->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("CLOSE", result);
		} else if (attrs) {
			kurma_result_to_fsal_attrs(result, attrs);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_unlink(const ObjectID *dir_oid, const char *name, ObjectID *oid,
	       struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_unlink(get_client(), &result, sessionid, dir_oid,
				    name, &error)) {
		st = result->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("UNLINK", result);
		} else {
			if (oid) {
				copy_object_id(oid, result->oid);
			}
			if (attrs) {
				kurma_result_to_fsal_attrs(result, attrs);
			}
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

/* attrs IN/OUT */
int rpc_mkdir(const ObjectID *dir_oid, const char *name, struct attrlist *attrs,
	      ObjectID *oid)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;
	ObjectAttributes *k_attrs = g_object_new(TYPE_OBJECT_ATTRIBUTES, NULL);
	fsal_attrs_to_kurma(attrs, k_attrs);

	if (kurma_service_if_mkdir(get_client(), &result, sessionid, dir_oid,
				   name, k_attrs, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			assert(result->__isset_oid);
			copy_object_id(oid, result->oid);
			/* overwrite attrs */
			kurma_result_to_fsal_attrs(result, attrs);
		} else {
			KURMA_RPC_STATUS("MKDIR", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	g_object_unref(k_attrs);
	return st;
}

/* attrs IN/OUT */
int rpc_create(const ObjectID *dir_oid, const char *name,
	       struct attrlist *attrs, ObjectID *oid)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;
	ObjectAttributes *k_attrs = g_object_new(TYPE_OBJECT_ATTRIBUTES, NULL);
	fsal_attrs_to_kurma(attrs, k_attrs);

	if (kurma_service_if_create(get_client(), &result, sessionid, dir_oid,
				    name, k_attrs, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			assert(result->__isset_oid);
			copy_object_id(oid, result->oid);
			/* overwrite attrs */
			kurma_result_to_fsal_attrs(result, attrs);
		} else {
			KURMA_RPC_STATUS("MKDIR", result);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	g_object_unref(k_attrs);
	return st;
}

int rpc_write(const ObjectID *file_oid, const int64_t offset,
	      const size_t buffer_size, void *buffer, size_t *write_amount,
	      uint32_t *block_shift, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;
	GByteArray *data = g_byte_array_new_take(buffer, buffer_size);
        uint64_t block_size;

        assert(block_shift && *block_shift);
        block_size = 1 << *block_shift;

	assert(is_aligned(offset, block_size));

	if (kurma_service_if_write(get_client(), &result, sessionid, file_oid,
				offset, data, &error)) {
		st = result->status->errcode;
		if (st == 0) {
			*write_amount = buffer_size;
			/* Assign to kurma server block_shift value */
			*block_shift = result->new_attrs->block_shift;
			if (attrs) {
				kurma_result_to_fsal_attrs(result, attrs);
			}
		}
		else {
			*write_amount = 0;
			KURMA_RPC_STATUS("WRITE", result);
		}
	}
	else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	g_byte_array_free(data, FALSE); /* do not free actual buffer */
	return st;
}

int rpc_read(const ObjectID *file_oid, const int64_t offset,
	     const size_t buffer_size, void *buffer, size_t *read_amount,
	     bool *end_of_file, uint32_t block_shift, struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;
	int filesize;
        uint64_t block_size;

        assert(block_shift);
        block_size = 1 << block_shift;

	assert(is_aligned(offset, block_size));
	assert(is_aligned(buffer_size, block_size));

	if (kurma_service_if_read(get_client(), &result, sessionid, file_oid,
				  offset, buffer_size, &error)) {
		st = result->status->errcode;
		if (st != 0) {
			*read_amount = 0;
			KURMA_RPC_STATUS("READ", result);
			goto out;
		}

		if (attrs) {
			kurma_result_to_fsal_attrs(result, attrs);
		}

		assert(result->new_attrs->__isset_filesize);
		filesize = result->new_attrs->filesize;
		if (offset >= filesize) {
			*end_of_file = true;
			*read_amount = 0;
		} else {
			assert(result->__isset_file_data);
			*read_amount = result->file_data->len;
			if (offset + *read_amount > filesize)
				KURMA_ERR("Inconsistent size");
			memcpy(buffer, result->file_data->data, *read_amount);
			*end_of_file = (offset + *read_amount == filesize);
		}
		assert(*read_amount <= buffer_size);
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_rename(const ObjectID *src_dir_oid, const char *src_name,
	       const ObjectID *dst_dir_oid, const char *dst_name,
	       struct attrlist *attrs)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_rename(get_client(), &result, sessionid,
				    src_dir_oid, src_name, dst_dir_oid,
				    dst_name, &error)) {
		st = result->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("RENAME", result);
		} else if (attrs) {
			kurma_result_to_fsal_attrs(result, attrs);
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

int rpc_get_dynamic_info(DynamicInfo *dinfo)
{
	KurmaResult *result = g_object_new(TYPE_KURMA_RESULT, NULL);
	GError *error = NULL;
	kurma_st st = KURMA_RPC_ERR;

	if (kurma_service_if_get_dynamic_info(get_client(), &result,
					      sessionid, &error)) {
		st = result->status->errcode;
		if (st != 0) {
			KURMA_RPC_STATUS("RENAME", result);
			dinfo->bytes = 0;
			dinfo->files = 0;
		} else {
			dinfo->bytes = result->dynamic_info->bytes;
			dinfo->files = result->dynamic_info->files;
		}
	} else {
		KURMA_ERR("%s", error ? error->message : "GLIB ERROR?");
	}

out:
	g_clear_error(&error);
	g_object_unref(result);
	return st;
}

/*
struct _ObjectAttributes
{
  gint64 filesize;
  gint32 owner_id;
  gint32 group_id;
  gint32 mode;
  gint32 nlinks;
  gint64 create_time;
  gint64 access_time;
  gint64 modify_time;
  gint64 change_time;
  gint64 nblocks;
  gint64 block_shift;
  gint64 rawdev;
  GPtrArray * acl;
};
*/
void fsal_attrs_to_kurma(const struct attrlist *attrs,
			 ObjectAttributes *k_attrs)
{
	int64_t ms;

	if (FSAL_TEST_MASK(attrs->mask, ATTR_SIZE)) {
		assert(attrs->filesize <= INT64_MAX); /* for uint64 to int64 */
		g_object_set(k_attrs, "filesize", attrs->filesize, NULL);
		assert(k_attrs->__isset_filesize == TRUE);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_OWNER)) {
		g_object_set(k_attrs, "owner_id", attrs->owner, NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_GROUP)) {
		g_object_set(k_attrs, "owner_id", attrs->group, NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_MODE)) {
		g_object_set(k_attrs, "mode", attrs->mode, NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_NUMLINKS)) {
		g_object_set(k_attrs, "nlinks", attrs->numlinks, NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_CREATION)) {
		g_object_set(k_attrs, "create_time", attrs->creation.tv_sec,
			     NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_ATIME) &&
	    timespec_to_ms(&attrs->atime, &ms)) {
		g_object_set(k_attrs, "access_time", ms, NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_MTIME) &&
	    timespec_to_ms(&attrs->mtime, &ms)) {
		g_object_set(k_attrs, "modify_time", ms, NULL);
	}
	if (FSAL_TEST_MASK(attrs->mask, ATTR_CTIME) &&
	    timespec_to_ms(&attrs->ctime, &ms)) {
		g_object_set(k_attrs, "change_time", ms, NULL);
	}
	// if (FSAL_TEST_MASK(attrs->mask, ATTR_SPACEUSED)) not setable
	if (FSAL_TEST_MASK(attrs->mask, ATTR_RAWDEV)) {
		g_object_set(k_attrs, "rawdev", attrs->rawdev.major, NULL);
	}

	// Setting hints flag
	g_object_set(k_attrs, "hints", attrs->hints, NULL);
	if(attrs->hints)
		KURMA_D("+++ Setting hints flag");
}

void kurma_attrs_to_fsal(const ObjectAttributes *k_attrs,
			 struct attrlist *attrs)
{
	// Check hints for symbolic link 
	if (k_attrs->hints & KURMA_HINT_SYMLINK) {
		FSAL_SET_MASK(attrs->mask, ATTR_TYPE);
		attrs->type = SYMBOLIC_LINK;
		attrs->hints = k_attrs->hints;
		KURMA_D("+++  Hints flag is enabled, its a symbolic link");
	}

	attrs->mask = 0;
	if (k_attrs->__isset_filesize) {
		FSAL_SET_MASK(attrs->mask, ATTR_SIZE);
		assert(k_attrs->filesize >= 0);
		attrs->filesize = k_attrs->filesize; // int64 to uint64
	}
	if (k_attrs->__isset_owner_id) {
		FSAL_SET_MASK(attrs->mask, ATTR_OWNER);
		attrs->owner = k_attrs->owner_id;
	}
	if (k_attrs->__isset_group_id) {
		FSAL_SET_MASK(attrs->mask, ATTR_GROUP);
		attrs->group = k_attrs->group_id;
	}
	if (k_attrs->__isset_mode) {
		assert(k_attrs->mode >= 0);
		FSAL_SET_MASK(attrs->mask, ATTR_MODE);
		attrs->mode = k_attrs->mode;
	}
	if (k_attrs->__isset_nlinks) {
		FSAL_SET_MASK(attrs->mask, ATTR_NUMLINKS);
		assert(k_attrs->nlinks >= 0);
		attrs->numlinks = k_attrs->nlinks;
	}
	if (k_attrs->__isset_create_time) {
		FSAL_SET_MASK(attrs->mask, ATTR_CREATION);
		millisecond_to_timespec(k_attrs->create_time, &attrs->creation);
	}
	if (k_attrs->__isset_access_time) {
		FSAL_SET_MASK(attrs->mask, ATTR_ATIME);
		millisecond_to_timespec(k_attrs->access_time, &attrs->atime);
	}
	if (k_attrs->__isset_modify_time) {
		FSAL_SET_MASK(attrs->mask, ATTR_MTIME);
		millisecond_to_timespec(k_attrs->modify_time, &attrs->mtime);
	}
	if (k_attrs->__isset_change_time) {
		FSAL_SET_MASK(attrs->mask, ATTR_CTIME);
		millisecond_to_timespec(k_attrs->change_time, &attrs->ctime);
	}
	if (k_attrs->__isset_remote_change_time) {
		FSAL_SET_MASK(attrs->mask, ATTR_CHGTIME);
		millisecond_to_timespec(k_attrs->remote_change_time, &attrs->chgtime);
	}
	if (k_attrs->__isset_nblocks && k_attrs->__isset_block_shift) {
		FSAL_SET_MASK(attrs->mask, ATTR_SPACEUSED);
		attrs->spaceused =
		    (1 << k_attrs->block_shift) * k_attrs->nblocks;
	}
	if (k_attrs->__isset_rawdev) {
		FSAL_SET_MASK(attrs->mask, ATTR_RAWDEV);
		attrs->rawdev.major = k_attrs->rawdev;
		attrs->rawdev.minor = 0; // TODO
	}
	if (k_attrs->__isset_hints) {
		attrs->hints = k_attrs->hints;
	} else {
		attrs->hints = 0;
	}
	// TODO how to use nblocks, block_shift for getattrs()
	// TODO acl
}
