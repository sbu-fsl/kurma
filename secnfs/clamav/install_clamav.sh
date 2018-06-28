#!/bin/bash -
# Script to - install clamav for root user
#           - download database
#
# Location  - Files created:
# `pwd`     - clamav-devel (source code)
# /usr/local/etc/ - freshclam.conf.sample, freshclam.conf,
#                   clamd.conf.sample, clamd.conf
#                   (configuration files)
# /usr/local/share/ - clamav (virus database files)
#
# Usage instructions:
#  - run as root
#  - copy this script and the patch file to folder where you want clamav src
#  - run script
#      ./install_clamav.sh  [scanbuff-patch-file]
#

set -e
DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

if [[ $# != 1 ]]; then
  echo "usage: $0 [scanbuff-patch-file]"
  echo "The patch file is 0001-libclamav-added-api-for-scanning-memory-containing-e.patch"
  echo " at <fsl-nfs-ganesha>/secnfs/clamav/"
  exit 1
fi

patch_path=$(readlink -f $1)

function copy_conf_file()
{
    local in_file="$1"
    local out_file="$2"
    while read -r line
    do
        if [[ $line =~ ^Example(.*)$ ]]; then
            echo \#Example${BASH_REMATCH[1]}>> $out_file
        elif [[ $line =~ ^\#DatabaseOwner(.*)$ ]]; then
            echo DatabaseOwner root>> $out_file
        elif [[ $line =~ ^\#User(.*)$ ]]; then
            echo User root>> $out_file
        else
            echo $line>> $out_file
        fi
    done < "$in_file"
}

yum install -y zlib zlib-devel bzip2 bzip2-devel oprofile-jit.x86_64 python \
  openssl-devel
git clone https://github.com/vrtadmin/clamav-devel.git
cd clamav-devel

git apply $patch_path
#patch -p1 < ./0001-libclamav-added-api-for-scanning-memory-containing-e.patch

./configure
make
make install

copy_conf_file /usr/local/etc/freshclam.conf.sample /usr/local/etc/freshclam.conf
copy_conf_file /usr/local/etc/clamd.conf.sample /usr/local/etc/clamd.conf

mkdir -p /usr/local/share/clamav
chmod o+rw /usr/local/share/clamav/
chmod g+rw /usr/local/share/clamav/
freshclam

