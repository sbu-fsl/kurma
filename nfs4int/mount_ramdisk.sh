#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

if [[ $# -ne 1 ]]; then
  echo "usage: $0 [size]";
  exit 1
fi

size=${1:0:-1}
unit=${1:${#size}}

if [[ $unit =~ [kK] ]]; then
  size=$(( size * 1024 ))
elif [[ $unit =~ [mM] ]]; then
  size=$(( size * 1024 * 1024 ))
elif [[ $unit =~ [gG] ]]; then
  size=$(( size * 1024 * 1024 * 1024 ))
else
  echo "size unit should be k, m, or g"
  exit 1
fi

mounted=$(mount -l | grep -c vfs-ganesha)

set -e
set -x

if [[ $mounted -gt 0 ]]; then
  umount -f /vfs-ganesha/
fi

sed -e "s/\"size\": .*,/\"size\": $size,/" ${SCRIPT_DIR}/pi-10GB-device-config.json > tmp-pi-${1}.json
targetcli restoreconfig tmp-pi-${1}.json clear_existing

dev=$(fdisk -l | grep 'Disk /dev/sd' | sort | tail -n 1 | tr -d ':' | awk '{printf $2;}')
echo "creating $dev of size $size"

yes | mkfs.ext4 $dev
mount -t ext4 $dev  /vfs-ganesha/
