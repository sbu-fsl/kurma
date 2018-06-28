#!/bin/bash

set -x
mkdir -p /vfs-ganesha
umount /vfs-ganesha/
targetcli restoreconfig pi-100MB-device-config.json clear_existing
yes | mkfs.ext4 /dev/sdb
mount -t ext4 /dev/sdb  /vfs-ganesha/
#tmux -2

