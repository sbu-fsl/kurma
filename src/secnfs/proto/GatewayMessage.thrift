// Inter-Gateway communication messages
include "Namespace.thrift"
include "Kurma.thrift"

namespace java edu.stonybrook.kurma.message
namespace cpp secnfs.proto

typedef Kurma.KurmaStatus Status

// Service that replicate FS metadata among gateways.
enum OperationType {
  CREATE_DIR;
  REMOVE_DIR;
  CREATE_FILE;
  UNLINK_FILE;
  UPDATE_FILE;
  SET_ATTRIBUTES;
  RENAME;
  COMMIT;
  CLAIM_MASTER;
  RENOUNCE_MASTER;
  ACK_MASTER;
  CREATE_VOLUME;
  REMOVE_VOLUME;
  UPDATE_STALE_ACK;
  CREATE_FILE_KEY;
}

enum ReplicationFlags {
  CREATE = 1;         // create file/dir that did not exist in any gateway
  COPY = 2;           // create by copying existing file/dir from a gateway
  FLUSH = 4;
}

struct CreateVolume {
  1: Namespace.VolumeInfo volume,
}

struct RemoveVolume {
  1: Namespace.VolumeInfo volume,
}

struct CreateDir {
  1: Namespace.Directory dir;
}

struct RemoveDir {
  1: Namespace.ObjectID parent_oid;
  2: Namespace.ObjectID oid;
  3: string name;
}

struct CreateFile {
  1: Namespace.File file;
  2: Namespace.KeyMap keymap;       // set only if flags is COPY
  3: Namespace.BlockMap blockmap;   // set only if flags is COPY
}

struct UnlinkFile {
  1: Namespace.ObjectID parent_oid;
  2: Namespace.ObjectID oid;
  3: string name;
}

struct UpdateFile {
  1: Namespace.ObjectID file_oid;
  2: i64 offset;
  3: i64 length;
  4: list<i64> new_versions;
  5: Namespace.ObjectAttributes new_attrs;
}

// change file attributes and info
struct SetAttributes {
  1: Namespace.ObjectID oid;
  2: Namespace.ObjectAttributes old_attrs;
  3: Namespace.ObjectAttributes new_attrs;
}

struct Rename {
  1: Namespace.ObjectID src_dir_oid;
  2: Namespace.ObjectID src_oid;
  3: string src_name;
  4: Namespace.ObjectID dst_dir_oid;
  5: string dst_name;
}

enum CommitType {
  COMMIT;
  ABORT;
}

// Commit an operation.
struct Commit {
  1: i64 op_seq;   // the seq_number of the operation to be committed
  2: i32 crc;     // CRC of the committed operation
  3: i32 type;    // CommitType
}

// claim mastership of a file
struct ClaimMaster {
  1: Namespace.ObjectID file_oid;
  2: i32 timeout;           // lease time (seconds)
  3: binary cookie;         // a cookie that identify this claim
}

// renounce mastership of a file
// TODO: support more types of mastership (lease) as FARSITE does
struct RenounceMaster {
  1: Namespace.ObjectID file_oid;
  2: binary cookie;         // a cookie that identify this renounce
}

// acknowledge mastership claim or renounce
struct AckMaster {
  1: binary cookie;         // identify the op being acknowledged
}

struct TakeSnapshot {
  1: Namespace.ObjectID file_oid;
  2: string snapshot_name;
  3: i64 create_time;
  4: i64 update_time;
  5: Namespace.ObjectAttributes attrs;
  6: string description;
}

struct RestoreSnapshot {
  1: Namespace.ObjectID file_oid;
  2: string snapshot_name;
}

struct UpdateSnapshot {
  1: Namespace.ObjectID file_oid;
  2: string snapshot_name;
  3: i64 update_time;
  4: string description;
}

struct DeleteSnapshot {
  1: Namespace.ObjectID file_oid;
  2: string snapshot_name;
}

struct UpdateDeletes {
  1: string block_key;	
  2: list<Namespace.GatewayID> gwids;
 }

enum AckStatus {
  SUCCESS;
  FAILURE;
}

struct CreateFileAck {
  1: AckStatus ack;
  2: Namespace.File file;
}

struct UnlinkFileAck {
  1: AckStatus ack;
  2: Namespace.ObjectID parent_oid;
  3: Namespace.ObjectID oid;
  4: string name;
}

struct UpdateFileAck {
  1: AckStatus ack;
  2: Namespace.ObjectID file_oid;
  3: i64 offset;
  4: i64 length;
  5: list<i64> new_versions;
  6: Namespace.ObjectAttributes new_attrs;
}

union KurmaOperation {
  1: CreateVolume create_volume;
  2: RemoveVolume remove_volume;
  3: CreateDir create_dir;
  4: RemoveDir remove_dir;
  5: CreateFile create_file;
  6: UnlinkFile unlink_file;
  7: UpdateFile update_file;
  8: SetAttributes set_attrs;
  9: Rename rename;
  10: Commit commit;
  11: ClaimMaster claim_master;
  12: RenounceMaster renounce_master;
  13: AckMaster ack_master;
  14: TakeSnapshot take_snapshot;
  15: RestoreSnapshot restore_snapshot;
  16: DeleteSnapshot delete_snapshot;
  17: UpdateDeletes	update_deletes;
}

// TODO: How to initialize a newly added gateway
struct GatewayMessage {
  1: Namespace.GatewayID gwid;
  2: string volumeid;
  3: i64 timestamp;
  4: ReplicationFlags flags;
  5: OperationType op_type;
  6: KurmaOperation op;
  7: i64 seq_number;
}

// vim:sw=2:ts=2:sts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
