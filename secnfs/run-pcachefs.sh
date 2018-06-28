#!/bin/bash -
# Launch the caching proxy.
#
# This script should be placed in the build directory.
#
#       cp run-secnfs.sh <root-to-nfs-ganesha>/<build-directory>
#
# Usage 1 (executed in the directory):
#
#       cd <root-to-nfs-ganesha>/<build-directory>
#       ./run-pcachefs.sh
#
# Usage 2 (executed using full path):
#
#       <root-to-nfs-ganesha>/<build-directory>/run-pcachefs.sh

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

PATHPROG=$DIR/MainNFSD/ganesha.nfsd

running_mode="${1:-release}"

if [[ ${running_mode} == 'debug' ]]; then
  CONFFILE=/etc/ganesha/pcachefs.ganesha.conf
  LOGLEVEL='NIV_FULL_DEBUG'
  export GLOG_v=2
  export GLOG_logtostderr=1
  export GLOG_stderrthreshold=0
  export GLOG_minloglevel=0
elif [[ ${running_mode} == 'release' ]]; then
  CONFFILE=/etc/ganesha/pcachefs.release.ganesha.conf
  LOGLEVEL='NIV_EVENT'
  export GLOG_v=0
  export GLOG_logtostderr=1
else
  echo "unknown running mode: ${running_mode}"
  echo "usage: $0 [debug|release]"
  exit 1
fi

LOGFILE=/var/log/pcachefs.ganesha.log
LOGSTDOUT=/var/log/stdout.ganesha.log

prog=ganesha.nfsd
PID_FILE=${PID_FILE:=/var/run/${prog}.pid}
LOCK_FILE=${LOCK_FILE:=/var/lock/subsys/${prog}}

# clear log file
> $LOGFILE
> $LOGSTDOUT

# Library path of libclamav.so
export LD_LIBRARY_PATH=/usr/local/lib64/:/usr/local/lib

# create directories for cache
proxy_cache_dir=/proxy-cache
meta_dir=$proxy_cache_dir/meta
data_dir=$proxy_cache_dir/data
if [ ! -e $proxy_cache_dir ]
then
    echo "$proxy_cache_dir does not exist. creating." >> $LOGSTDOUT
    mkdir $proxy_cache_dir
fi
if [ ! -e $meta_dir ]
then
    echo "$meta_dir does not exist. creating." >> $LOGSTDOUT
    mkdir $meta_dir
else
    echo "$meta_dir exists. deleting old files." >> $LOGSTDOUT
    rm -rf $meta_dir/*
fi
if [ ! -e $data_dir ]
then
    echo "$data_dir does not exist. creating." >> $LOGSTDOUT
    mkdir $data_dir
else
    echo "$data_dir exists. deleting old files." >> $LOGSTDOUT
    rm -rf $data_dir/*
fi

# run script
$PATHPROG -L $LOGFILE -f $CONFFILE -N ${LOGLEVEL} &>> $LOGSTDOUT
