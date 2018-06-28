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

#pragma once

#include <fcntl.h>
#include <sys/stat.h>

#include <stdio.h>
#include <glib-object.h>
#include <assert.h>

#include <thrift/c_glib/protocol/thrift_binary_protocol.h>
#include <thrift/c_glib/transport/thrift_buffered_transport.h>
#include <thrift/c_glib/transport/thrift_socket.h>

#include "gen-c_glib/kurma_service.h"

#include "fsal_types.h"
#include "common_utils.h"
#include "log.h"
#define KURMA_ERR(fmt, args...) LogCrit(COMPONENT_FSAL, "=kurma=" fmt, ##args)
#define KURMA_I(fmt, args...) LogInfo(COMPONENT_FSAL, "=kurma=" fmt, ##args)
#define KURMA_D(fmt, args...) LogDebug(COMPONENT_FSAL, "=kurma=" fmt, ##args)
#define KURMA_F(fmt, args...)                                                  \
	LogFullDebug(COMPONENT_FSAL, "=kurma=" fmt, ##args)

#define KURMA_RESULT_ERRMSG(result)                                            \
	(result)->status->__isset_errmsg ? (result)->status->errmsg : ""

#define KURMA_RPC_STATUS(name, result)                                         \
	KURMA_I("RPC_" name " status: %d %s", (result)->status->errcode,       \
		KURMA_RESULT_ERRMSG(result))

#define KURMA_ATTRS_EXPIRE_SEC 5

#define KURMA_HINT_SYMLINK 1

typedef enum {
	KURMA_OKAY = 0,
	KURMA_INVALID,
	KURMA_CONN_ERR,
	KURMA_RPC_ERR,
	KURMA_IO_ERR,
	// the errcodes below should also be available
	// KURMA_ERROR_SESSION_NOT_EXIST = 10001,
	// KURMA_ERROR_OBJECTID_INVALID = 10002,
	// KURMA_ERROR_OBJECT_NOT_FOUND = 10003,
	// KURMA_ERROR_ZOOKEEPER_ERROR = 10004
} kurma_st;

typedef struct client_params
{
	char *server;	/* address of thrift server */
	int port;	/* port of thrift server */
	char *clientid;	/* kurmafs client id */
	GByteArray *g_clientid;
	char *volumeid;	/* kurmafs volumeid */
	int renew_interval; /* sessionid renew interval */
	bool enable_snapshot; /* enable snapshotting */
} kurma_specific_initinfo_t;

int rpc_init(struct client_params *params);

/**
 * @param[in]	  dir_oid   can be NULL if absolute path is provided
 * @param[in]	  path
 * @param[in/out] oid
 */
int rpc_lookup(const ObjectID *dir_oid, const char *path, ObjectID *oid,
	       struct attrlist *attrs, int *block_shift);
int rpc_getattrs(const ObjectID *oid, struct attrlist *attrs, int *block_shift);
int rpc_unlink(const ObjectID *dir_oid, const char *name, ObjectID *oid,
	       struct attrlist *attrs);
void fsal_attrs_to_kurma(const struct attrlist *attrs, ObjectAttributes *k_attrs);
void kurma_attrs_to_fsal(const ObjectAttributes *k_attrs, struct attrlist *attrs);
void *rpc_sessionid_renewer(void *arg);

int rpc_create_session(char *clientid, const char *volumeid);
int rpc_open(const ObjectID *oid, fsal_openflags_t flags,
	     struct timespec *chgtime, struct attrlist *attrs);
int rpc_close(const ObjectID *oid, struct attrlist *attrs);
int rpc_read(const ObjectID *file_oid, const int64_t offset,
	     const size_t buffer_size, void *buffer, size_t *read_amount,
	     bool *end_of_file, uint32_t block_shift, struct attrlist *attrs);
int rpc_write(const ObjectID *file_oid, const int64_t offset,
	      const size_t buffer_size, void *buffer, size_t *write_amount,
	      uint32_t *block_shift, struct attrlist *attrs);
int rpc_create(const ObjectID *dir_oid, const char *name,
	       struct attrlist *attrs, ObjectID *oid);
int rpc_mkdir(const ObjectID *dir_oid, const char *name, struct attrlist *attrs,
	      ObjectID *oid);
int rpc_listdir(const ObjectID *oid, KurmaResult **result,
		struct attrlist *attrs);
int rpc_rename(const ObjectID *src_dir_oid, const char *src_name,
	       const ObjectID *dst_dir_oid, const char *dst_name,
	       struct attrlist *attrs);
int rpc_setattrs(const ObjectID *oid, struct attrlist *attrs);

int rpc_get_dynamic_info(DynamicInfo *dinfo);

int rpc_take_snapshot(const ObjectID *oid, const char *snapshot_name,
		      int *snapshot_id, struct attrlist *attrs);

/**
 * Lookup a snapshot in the snapshot directory specified by "dir_oid".
 * @param[in]	dir_oid	Snapshot directory OID
 * @param[in]	snapshot_name Snapshot name
 * @param[in/out] snapshot_id Snapshot ID
 * @param[out]	oid	OID of the found snapshot
 * @param[out]	attrs	file attributes
 */
int rpc_lookup_snapshot(const ObjectID *dir_oid, const char *snapshot_name,
			int *snapshot_id, ObjectID *oid,
			struct attrlist *attrs);

/**
 * Restore a file back to a snapshot.
 *
 * @param[in]	oid	ObjectID of the file.
 * @param[in]	snapshot_name
 * @param[in/out] snapshot_id
 * @param[out]	attrs	Attributes of the restored file.
 */
int rpc_restore_snapshot(const ObjectID *oid, const char *snapshot_name,
			 int *snapshot_id, struct attrlist *attrs);

int rpc_delete_snapshot(const ObjectID *oid, const char *snapshot_name,
			struct attrlist *attrs);

int rpc_list_snapshots(const ObjectID *oid, KurmaResult **result,
		       struct attrlist *attrs);

#define THRIFT_SET_FIELD(dst, src, field) \
	(dst)->field = (src)->field; \
	(dst)->__isset_##field = (src)->__isset_##field

static inline int copy_object_id(ObjectID *dst, const ObjectID *src)
{
	THRIFT_SET_FIELD(dst->id, src->id, id1);
	THRIFT_SET_FIELD(dst->id, src->id, id2);

	dst->__isset_id = src->__isset_id;
	// THRIFT_SET_FIELD(dst, src, id);  // This overwrites "id".

	THRIFT_SET_FIELD(dst, src, creator);
	THRIFT_SET_FIELD(dst, src, type);
	THRIFT_SET_FIELD(dst, src, flags);
	return 0;
}

static inline bool is_aligned(uint64_t n, uint64_t alignment)
{
	assert((alignment & (alignment - 1)) == 0);
	return (n & (alignment - 1)) == 0;
}

static inline uint64_t round_down(uint64_t n, uint64_t alignment)
{
	assert((alignment & (alignment - 1)) == 0);
	return n & ~(alignment - 1);
}

static inline uint64_t round_up(uint64_t n, uint64_t alignment)
{
	assert((alignment & (alignment - 1)) == 0);
	return (n + alignment - 1) & ~(alignment - 1);
}

static inline uint64_t get_block_count(uint64_t n, uint64_t block_shift)
{
	return (n + (1 << block_shift) - 1) >> block_shift;
}

static inline object_file_type_t extract_object_type(const ObjectID *oid)
{
	return oid->type;
}

static inline void millisecond_to_timespec(int64_t ms, struct timespec *tm)
{
	tm->tv_sec = ms / 1000;			  // ms to sec
	tm->tv_nsec = (ms % 1000) * 1000 * 1000;  // ms to ns
}

/**
 * Convert timespec to millisecond.
 *
 * Return false in case of UTIME_OMIT, otherwise, true.
 */
static inline bool timespec_to_ms(const struct timespec *tm, int64_t *ms)
{
	bool present = false;
	struct timespec mytm = *tm;

	if (tm->tv_nsec != UTIME_OMIT) {
		if (tm->tv_nsec == UTIME_NOW) {
			now(&mytm);
		}
		*ms = mytm.tv_sec * 1000 + (mytm.tv_nsec / 1000000);
		present = true;
	}

	return present;
}

static inline void set_root_oid(ObjectID *oid)
{
	oid->id->id1 = 0;
	oid->id->id2 = 1;
}

static inline bool is_same_file(const ObjectID *o1, const ObjectID *o2)
{
	return o1->id->id1 == o2->id->id1 && o1->id->id2 == o2->id->id2 &&
	       o1->creator == o2->creator;
}
