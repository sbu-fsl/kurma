include "Namespace.thrift"

namespace java edu.stonybrook.kurma.records
namespace cpp secnfs.proto

struct GarbageBlockJournalRecord {
  1: Namespace.GatewayID gwid;
  2: Namespace.ObjectID oid;
  3: i64 offset;
  4: i64 block_version;
  5: i32 length;
  6: string kvs_ids;
  7: string block_key
  8: i64 timestamp;
  9: optional Namespace.GatewayID remotegwid;
  10: optional i64 last_response_time;
}

enum ZKOperationType {
  CREATE;
  REMOVE;
  UPDATE;
  COMMIT;
}

struct MetaUpdateJournalRecord {
  1: i64 transactionID;
  2: ZKOperationType type;
  3: optional string zpath;
  4: optional binary data;
  5: optional bool ignoreVersion;
  6: optional i32 version;
}
