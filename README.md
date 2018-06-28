About
=====
This is the Kurma secure gateway project at Stony Brook University.
It use public cloud Key-Value services to provide NFS file services.
It provides a global File System namespace for Geo-Distributed offices.

Run ./update-kurma.sh to compile and run Kurma and Kurma FSAL.

See secnfs/kurma/install-kurma-server.sh to see how to install Kurma.

See secnfs/kurma/install-zk-bk-hw.sh to see how to setup Zookeeper, BookKeeper,
and Hedwig dependencies for Kurma.

To setup Jerasure, checkout "https://github.com/pviotti/hybris.git" and make.
See secnfs/kurma/mac-jerasure to see how to setup Jerasure in Mac, so that unit
tests about Jerasure can be run in Mac during development using Eclipse.

See secnfs/kurma/HOWTO for detailed information.

nfs-ganesha
===========
NFS-Ganesha is an NFSv3,v4,v4.1 fileserver that runs in user mode on most
UNIX/Linux systems.  It also supports the 9p.2000L protocol.

For more information, consult the [project wiki](https://github.com/nfs-ganesha/nfs-ganesha/wiki).
