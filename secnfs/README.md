NFS Secure Proxy

# Getting Started
Follow instructions in HOWTO


# NFS End-to-End Integrity
http://tools.ietf.org/html/draft-cel-nfsv4-end2end-data-protection-01

## Patchset of passing userspace PI to kernel
Version 1 (in pi-patchset-v1) is hacky and no longer used.
http://thread.gmane.org/gmane.linux.kernel.aio.general/3904

We are currently using Version 2 (in pi-patchset-v2), which uses I/O extention
to pass PI and is thus more elegant.
https://marc.info/?l=linux-mm&m=139567816228628&w=2
