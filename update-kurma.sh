#!/bin/bash -
#=============================================================================
# Update and restart Kurma server and KurmaFSAL, which run in the same machine.
#
# Usage: update-kurma.sh [cache]
#
# by Ming Chen, v.mingchen@gmail.com
#=============================================================================

set -o nounset                          # treat unset variables as an error
set -o errexit                          # stop script if command fail
IFS=$' \t\n'                            # reset IFS
unset -f unalias                        # make sure unalias is not a function
\unalias -a                             # unset all aliases
ulimit -H -c 0 --                       # disable core dump
hash -r                                 # clear the command path hash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
WD=$(pwd)
cd $DIR

CONFIG="${1:-basic}"
BUILD="${2:-release}"

if [[ $# < 1 ]]; then
  echo "usage: $0 [basic|cache] [debug|release]"
  exit 0
fi

if [[ "$1" != "basic" && "$1" != "cache" || "$2" != "debug" && "$2" != "release" ]]; then
  echo "usage: $0 [basic|cache] [debug|release]"
  exit 0
fi

timestamp=$(date +%Y%m%d-%H%M%S)
STDERR=/var/log/update-kurma-stderr.log
KURMA_LOG=/var/log/kurma.log
GANESHA_LOG=/var/log/kurma.ganesha.log

# usage: killdaemon name [yes]
function killdaemon() {
  local name=$1
  local full=""
  if [[ ${2:-no} = "yes"  ]]; then
    full="-f"
  fi
  echo "======== killing $name ======="
  while pgrep $full "$name"; do
    if ! pkill -9 $full "$name"; then
      echo "failed to kill $name: $?"
    fi
  done
  echo "======== $name killed ======="
}

function cp_if_not_exists() {
  local srcfile="$1"
  local dstfile="$2"
  if [ ! -f "$dstfile" ]; then
    cp "${srcfile}" "${dstfile}"
  fi
}

echo "======= stderr redirected to $STDERR ========"
if [ -f $STDERR ]; then
  mv $STDERR ${STDERR}.$timestamp
fi
exec 2>$STDERR

# update code
git pull

echo "======== updating Kurma ======="
src/secnfs/proto/gen.sh
mvn compile

killdaemon KurmaServer yes
killdaemon startserver.sh
cp java/conf/log4j.release.properties target/classes/log4j.properties
if [ -f $KURMA_LOG ]; then
  mv $KURMA_LOG ${KURMA_LOG}.$timestamp
fi
nohup java/scripts/startserver.sh &
echo "======== Kurma updated and restarted ======="

sleep 10  # wait for the daemon to initialize
echo "======== Creating Kurma volume ======="
python/prototest/testkurma.py   # create the volume
echo "======== Kurma volume created ======="

echo "======== updating KurmaFSAL ======="
ulimit -c 'unlimited'           # enable core dump
cd $DIR
if [ ! -d "${BUILD}" ]; then
  echo "${BUILD} not exists, creating..."
  mkdir -p "${BUILD}"
  cd "${BUILD}"
  cp ../secnfs/run-kurma-cache.sh .
  cp ../secnfs/run-kurma-fsal.sh .
else
  cd "${BUILD}"
fi
killdaemon ganesha
killdaemon "run-kurma-fsal.sh"

# reset proxy cache directory
rm -rf /pcache
mkdir -p /pcache/{data,meta}

# build and install KurmaFSAL
cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo ../src
make
make install

# set up config file
if [[ "${BUILD}" = "debug" ]]; then
  cp_if_not_exists $DIR/secnfs/config/kurma.ganesha.conf \
      /etc/ganesha/kurma.ganesha.conf
  cp_if_not_exists $DIR/secnfs/config/kurma_pcache.ganesha.conf \
      /etc/ganesha/kurma_pcache.ganesha.conf
elif [[ "${BUILD}" = "release" ]]; then
  cp_if_not_exists $DIR/secnfs/config/kurma.release.ganesha.conf \
      /etc/ganesha/kurma.release.ganesha.conf
  cp_if_not_exists $DIR/secnfs/config/kurma_pcache.release.ganesha.conf \
      /etc/ganesha/kurma_pcache.release.ganesha.conf
fi
echo "======== KurmaFSAL updated ======="

if [ -f $GANESHA_LOG ]; then
  mv $GANESHA_LOG $GANESHA_LOG.$timestamp
fi

sleep 20
echo "======== starting Kurma FSAL ======="
if [[ $CONFIG == "basic" ]]; then
  nohup ./run-kurma-fsal.sh "${BUILD}" &
elif [[ $CONFIG == "cache" ]]; then
  nohup ./run-kurma-cache.sh "${BUILD}" &
fi
echo "======== Kurma FSAL started ======="

cd $DIR
sleep 30
python/prototest/fstest.py      # basic tests

cd $WD
