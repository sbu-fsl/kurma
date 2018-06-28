// Kurma namespace metadata that are saved in ZooKeeper.

namespace java edu.stonybrook.kurma.meta
namespace cpp secnfs.proto

typedef i16 GatewayID
typedef i32 CRC

enum VolumeOptions {
  VO_NO_TRUNC,
  VO_CHOWN_RESTRICTED,
  VO_CASE_INSENSITIVE,
  VO_CASE_PRESERVING,
  VO_LINK_SUPPORT,
  VO_SYMLINK_SUPPORT,
  VO_LOCK_SUPPORT,
  VO_LOCK_SUPPORT_OWNER,
  VO_LOCK_SUPPORT_ASYNC_BLOCK,
  VO_NAMED_ATTR,
  VO_UNIQUE_HANDLES,
  VO_CANSETTIME,
  VO_HOMOGENOUS,
  VO_AUTH_EXPORTPATH_XDEV,
  VO_DELEGATIONS,
  VO_ACCESSCHECK_SUPPORT,
  VO_SHARE_SUPPORT,
  VO_SHARE_SUPPORT_OWNER,
  VO_PNFS_DS_SUPPORTED,
  VO_REOPEN_METHOD,
  VO_ACL_SUPPORT,
}


// Znode path: /<volumeid>
struct VolumeInfo {
  // The ID should be global unique among all gateways.  Kurma strictly does not
  // enforce this (it is possible for multiple gateways to create volumes with
  // the same name) , so it should be enforced externally by policy.
  1: string id;
  2: GatewayID creator;
  3: i64 create_time;
  4: i64 options;     // bit field of VolumeOptions
  5: i32 max_file_size_gb = 1024;
  6: i32 max_read_size = 1048576;
  7: i32 max_write_size = 1048576;
  8: i32 max_links = 1024;
  9: i32 max_name_len = 256;
  10: i32 max_path_len = 1024;
  11: i32 umask = 2;
  12: i64 object_count = 0;  // number of files and directories
}

// copied from NFS-Ganesha object_file_type_t
enum ObjectType {
  NO_FILE_TYPE = 0;         // sanity check to ignore type
  REGULAR_FILE = 1;
  CHARACTER_FILE = 2;
  BLOCK_FILE = 3;
  SYMBOLIC_LINK = 4;
  SOCKET_FILE = 5;
  FIFO_FILE = 6;
  DIRECTORY = 7;
  EXTENDED_ATTR = 8;
}

enum FileFlags {
  INLINED = 1;                // tiny files saved inline
  PRIVATE = 2;                // private files not shared among users
  LOCAL = 4;                  // files not shared among gateways
  SNAPSHOT = 8;               // is a snapshot of a regular file?
  EXTRA = 16;                 // has extra znodes for blockmap or/and snapshots?
}

// Record the maximum id we have used so far.
//
// Znode path: /<volumeid>/ID_CURSOR
struct Int128 {
  1: i64 id1;
  2: i64 id2;
}

// Unique file object ID (oid) within a volume.
//
// Also as a generator for all FS objects within a volume.
struct ObjectID {
  1: Int128 id;
  2: GatewayID creator;
  3: byte type;           // ObjectType (use byte to save space)
  4: byte flags;          // bit field of FileFlags
}

// Access Control Entry
struct ACE {
  1: i32 principal_id;       // uid or gid
  2: i32 type;
  3: i32 perm;
}

// Part of version vector of all gateways
struct GatewayVersion {
  1: GatewayID gwid;
  2: i64 version;
  3: i64 timestamp;
}

struct DirEntry {
  1: string name;            // last component of the name
  2: ObjectID oid;           // unique ID of the child file or dir

  // time when this entry is added to the local gateway (not shared among
  // gateways)
  3: i64 timestamp;
}

// Bit masks for ObjectAttributes::hints
enum KurmaHint {
  IS_SYMLINK = 1;
  HAS_SNAPSHOTS = 2;
}

struct ObjectAttributes {
  1: optional i64 filesize;
  2: optional i32 owner_id;     // unsigned
  3: optional i32 group_id;     // unsigned
  4: optional i32 mode;         // unsigned
  5: optional i32 nlinks;
  6: optional i64 create_time;
  7: optional i64 access_time;
  8: optional i64 modify_time;
  9: optional i64 change_time;
  10: optional i64 nblocks;
  11: optional i32 block_shift;  // [16, 20], i.e., 64K and 1M block size
  12: optional i64 rawdev;
  13: optional list<ACE> acl;
  14: optional i64 datasize;
  15: optional i32 hints;		// Bitmap for file specific hint (i.e symlink)
  16: optional i64 remote_change_time;
}

// Represents a directory. Using TZlibTransport, which has internal checksum.
//
// Znode path:  /<volumeid>/ROOT (for volume root)
//              /<volumeid>/<creator-gw>/xx/yy/zz
// where xx is id2, yy is the 6 most significant bytes of id1, and zz is the 2
// least significant bytes of id1.
struct Directory {
  1: string name;
  2: ObjectID oid;
  3: ObjectID parent_oid;

  4: ObjectAttributes attrs;

  5: list<DirEntry> entries;

  // version vector of directory content
  6: list<GatewayVersion> vv;
}

// The file contains the file key encrypted by each gateway's public key.
//
// Znode path: <file-znode>/KEYMAP
struct KeyMap {
  1: map<GatewayID, binary> keymap;
  2: CRC crc;
}

// Block map of a regular file. The file data are stored in cloud(s), where
// each block is a <key, value> pair.  The key is the encrypted ciphertext of
// <offset + version + last_modifier>; the value is the block data.
//
// Each block map contains at most MAX_BLOCK_MAP_LENGTH (2^16) blocks.
//
// Znode path: <file-znode>/BLOCKMAP.n, where n is the index within all
// blockmaps of the file
struct BlockMap {
  // The offset (relative to the whole file) of the first block in this
  // BlockMap.
  1: i64 offset;

  // The number of blocks in this BlockMap. This can be different from
  // File::nblocks because we never truncate the block mapping to avoid replay
  // attacks.
  2: i64 length;

  // compressed array of versions; 0 versions mean file holes
  //
  // TODO: check if zlib compression is efficient enough, otherwise we need a
  // purpose-built compression algorithm.
  3: list<i64> versions;

  // The ID of the gateway that makes the latest change to the block (the one
  // who bumped the block's version to the value in "versions".
  //
  // block_version alone is enough to detect conflicts among gateways, but it
  // does not stop gateways from overwrite conflicting data in question.
  // With this last_modifier, the keys of all versions of blocks are unique
  // despite conflicts.
  //
  // It is compressed in the same way as the above "versions".
  4: list<i16> last_modifier;

  5: CRC crc;
}

// Represents a file.  Using TZlibTransport, which has internal checksum.
//
// Znode path: /<volumeid>/<id2>/<creator-gw>-<id1>
struct File {
  1: string name;
  2: ObjectID oid;
  3: ObjectID parent_oid;

  4: ObjectAttributes attrs;

  6: list<GatewayVersion> vv;    // version vector of file meta

  // gateway private seq number this znode that synchronized up to
  7: i64 synced_seq_number;

  8: optional GatewayID gw_master;     // gateway that now owns this file

  9: optional i32 block_map_count;

  10: optional binary key;

  11: optional string kvs_type;

  12: optional string kvs_ids;

  13: optional BlockMap blocks;
}

// Znode path: <file-znode>/SNAPSHOTS/snapshort_name
struct Snapshot {
  1: File saved_file;
  2: BlockMap blocks;
  3: KeyMap keys;
  4: i64 create_time;
  5: i64 update_time;
  6: string description;
  7: i32 id;
}
struct TestJournalObj {
	1: i32 number;
}

// vim:sw=2:ts=2:sts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
