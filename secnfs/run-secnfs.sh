#!/bin/bash -
# Launch the secnfs proxy.
#
# This script should be placed in the build directory.
#
#       cp run-secnfs.sh <root-to-nfs-ganesha>/<build-directory>
#
# Usage 1 (executed in the directory):
#
#       cd <root-to-nfs-ganesha>/<build-directory>
#       ./run-secnfs.sh
#
# Usage 2 (executed using full path):
#
#       <root-to-nfs-ganesha>/<build-directory>/run-secnfs.sh

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

PATHPROG=$DIR/MainNFSD/ganesha.nfsd

running_mode="${1:-release}"

if [[ ${running_mode} == 'debug' ]]; then
  CONFFILE=/etc/ganesha/secnfs.ganesha.conf
  LOGLEVEL='NIV_FULL_DEBUG'
  export GLOG_v=2
  export GLOG_logtostderr=1
  export GLOG_stderrthreshold=0
  export GLOG_minloglevel=0
elif [[ ${running_mode} == 'release' ]]; then
  CONFFILE=/etc/ganesha/secnfs.release.ganesha.conf
  LOGLEVEL='NIV_EVENT'
  export GLOG_v=0
  export GLOG_logtostderr=1
else
  echo "unknown running mode: ${running_mode}"
  echo "usage: $0 [debug|release]"
  exit 1
fi

LOGFILE=/var/log/secnfs.ganesha.log
LOGSTDOUT=/var/log/stdout.ganesha.log

prog=ganesha.nfsd
PID_FILE=${PID_FILE:=/var/run/${prog}.pid}
LOCK_FILE=${LOCK_FILE:=/var/lock/subsys/${prog}}

# clear log file
> $LOGFILE
> $LOGSTDOUT

# Library path of libclamav.so
export LD_LIBRARY_PATH=/usr/local/lib64/:/usr/local/lib

$PATHPROG -L $LOGFILE -f $CONFFILE -N ${LOGLEVEL} &>> $LOGSTDOUT
