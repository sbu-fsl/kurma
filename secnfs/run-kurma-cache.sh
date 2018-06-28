#!/bin/bash -
# Launch the NFS kurma.
#
# This script should be placed in the build directory.
#
#       cp run-kurma.sh <root-to-nfs-ganesha>/<build-directory>
#
# Usage 1 (executed in the directory):
#
#       cd <root-to-nfs-ganesha>/<build-directory>
#       ./run-kurma.sh
#
# Usage 2 (executed using full path):
#
#       <root-to-nfs-ganesha>/<build-directory>/run-kurma.sh

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

PATHPROG=MainNFSD/ganesha.nfsd

running_mode="${1:-release}"

if [[ ${running_mode} == 'debug' ]]; then
  CONFFILE=/etc/ganesha/kurma_pcache.ganesha.conf
  LOGLEVEL='NIV_FULL_DEBUG'
  export GLOG_v=2
  export GLOG_logtostderr=1
  export GLOG_stderrthreshold=0
  export GLOG_minloglevel=0
elif [[ ${running_mode} == 'release' ]]; then
  CONFFILE=/etc/ganesha/kurma_pcache.release.ganesha.conf
  LOGLEVEL='NIV_EVENT'
  export GLOG_v=0
  export GLOG_logtostderr=1
else
  echo "unknown running mode: ${running_mode}"
  echo "usage: $0 [debug|release]"
  exit 1
fi

LOGFILE=/var/log/kurma.ganesha.log
LOGSTDOUT=/var/log/stdout.ganesha.log

# clear log file
> $LOGFILE
> $LOGSTDOUT

# Library path of libclamav.so
export LD_LIBRARY_PATH=/usr/local/lib64/:/usr/local/lib

prog=ganesha.nfsd
PID_FILE=${PID_FILE:=/var/run/${prog}.pid}
LOCK_FILE=${LOCK_FILE:=/var/lock/subsys/${prog}}

# create directories for cache
pcache=/kurma-data/pcache
meta_dir=$pcache/meta
data_dir=$pcache/data
if [ ! -e $pcache ]
then
    echo "$pcache does not exist. creating." >> $LOGSTDOUT
    mkdir -p $pcache
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
