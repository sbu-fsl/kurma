Java code that provides Kurma's file system services.

It uses Zookeeper as metadata store, and the cloud KVS (key-value service) as
data store.

Its metadata format is specified in src/secnfs/proto/Namespace.thrift,
and its FS API is specified in src/secnfs/proto/Kurma.thrift.  We need to run
src/secnfs/proto/gen.sh to generate code from those thrift files.

Run <git-root>/secnfs/kurma/install-kurma-server.sh to set up Kurma including
Jerasure dependency.

See <git-root>/secnfs/kurma/mac-jerasure/README to install Jerasure on Mac.

# dependency
- thrift-0.9.2
