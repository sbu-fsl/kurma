#!/bin/bash - 
#=============================================================================
# Install ZooKeeper, BookKeeper, and Hedwig on a region's servers
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

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

source $DIR/hedwig-env.sh

MIRROR_ROOT='http://apache.mirrors.pair.com/zookeeper'

regions=(130.245.177.111,130.245.177.112,130.245.177.113
130.245.177.114,130.245.177.115,130.245.177.116
130.245.177.117,130.245.177.118,130.245.177.119)

if [[ $# -ne 1 ]]; then
  echo "usage: $0 [zk|bk|hw_client|hw_server|all]"
  exit 1
fi
target="${1:-hw_client}"

region_count=${#regions[@]}

ip=$(ifconfig ens3 | awk '(NR == 2) { print $2; }')
function find_region() {
  for i in $(seq 0 $(( ${region_count} - 1 ))); do
    if [[ ${regions[$i]} =~ .*${ip}.* ]]; then
      echo $i
      return 0
    fi
  done
  return 1
}
my_region_id=$(find_region)
servers=${regions[${my_region_id}]}
server_array=(${servers//,/ })

function list_servers() {
  local port=$1
  for si in ${server_array[*]}; do
    if [[ ${si} != ${server_array[0]} ]]; then
      echo -n ","
    fi
    echo -n "${si}:${port}"
  done
}
zkServers=$(list_servers 2181)

mkdir -p ${HW_ROOT}

###################
# 1. install zookeeper
###################
function install_zookeeper {
  echo "***** installing zookeeper *****"
  cd ${HW_ROOT}
  if [ ! -d ${ZK_HOME} ]; then
    wget ${MIRROR_ROOT}/${ZK_NAME}/${ZK_NAME}.tar.gz
    tar -xzf ${ZK_NAME}.tar.gz
  fi
  cd ${ZK_HOME}

  mkdir -p /zkdata
  cat > conf/zoo.cfg <<-EOF
tickTime=2000
dataDir=/zkdata
clientPort=2181
initLimit=5
syncLimit=2
EOF

  my_server_id=0
  for i in $(seq 1 ${#server_array[@]}); do
    j=$(($i - 1))
    echo "server.${i}=${server_array[$j]}:2888:3888" >> conf/zoo.cfg
    if [[ ${ip} == ${server_array[$j]} ]]; then
      my_server_id=${i}
    fi
  done
  [[ ${my_server_id} -eq 0 ]] && echo "my_server_id not set" && exit 1

  echo "${my_server_id}" > /zkdata/myid

  cd ${ZK_HOME}/src/c
  ./configure
  make
  make install
}

if [[ "${target}" =~ (all|zk) ]]; then
  install_zookeeper
fi


###################
# 2. install bookkeeper
###################
function install_bookkeeper {
  echo "***** installing bookkeeper. *****"
  cd ${HW_ROOT}
  if [ ! -d ${BK_HOME} ]; then
    wget ${MIRROR_ROOT}/bookkeeper/bookkeeper-${BK_VERSION}/${BK_NAME}-bin.tar.gz
    tar -xzf ${BK_NAME}-bin.tar.gz
  fi
  cd ${BK_HOME}
  mkdir -p /bk-data

  echo $zkServers

  mv conf/bk_server.conf conf/bk_server.conf.orig
  cp /benchmaster/bk_server.conf conf/bk_server.conf
  set_value zkServers "${zkServers}" conf/bk_server.conf

  # To format the bookie metadata in Zookeeper, execute the following command
  # once:
  # See http://zookeeper.apache.org/bookkeeper/docs/r4.3.0/bookkeeperConfig.html
  if [[ ${ip} == ${server_array[0]} ]]; then
    bin/bookkeeper shell metaformat -nonInteractive -force
  fi

  # To format the bookie local filesystem data, execute the following command on
  # each bookie node:
  bin/bookkeeper shell bookieformat -nonInteractive -force
}

if [[ "$target" =~ (all|bk) ]]; then
  install_bookkeeper
fi

###################
# 3. install hedwig
###################
function install_hedwig_server() {
  echo "***** installing hedwig server *****"
  cd ${HW_ROOT}
  if [ ! -d ${HW_SERVER} ]; then
    wget ${MIRROR_ROOT}/bookkeeper/bookkeeper-${BK_VERSION}/${HW_SERVER}-bin.tar.gz
    tar -xzf ${HW_SERVER}-bin.tar.gz
  fi
  cd ${HW_SERVER}

  mv conf/hw_server.conf conf/hw_server.conf.orig
  cp /benchmaster/hw_server.conf conf/hw_server.conf
  set_value zk_host "${zkServers}" conf/hw_server.conf
  set_value region "reg$((${my_region_id} + 1))" conf/hw_server.conf
}

if [[ "$target" =~ (all|hw_server) ]]; then
  install_hedwig_server
fi

# hedwig client
function install_hedwig_client() {
  echo "***** installing hedwig client *****"
  cd ${HW_ROOT}
  #if [ ! -d ${HW_CLIENT} ]; then
    mkdir -p ${HW_CLIENT}/{bin,conf,lib,logs}
    cp /benchmaster/${HW_CLIENT}.jar ${HW_CLIENT}/
    cp /benchmaster/hedwig-client.sh ${HW_CLIENT}/bin/
    cp /benchmaster/hw_client.conf ${HW_CLIENT}/conf/
    cp /benchmaster/hwenv.sh ${HW_CLIENT}/conf/
    cp ${HW_SERVER}/lib/*.jar ${HW_CLIENT}/lib/

    # remove the duplicate jar file
    rm -f ${HW_CLIENT}/lib/${HW_CLIENT}.jar
  #fi
}

if [[ "$target" =~ (all|hw_client) ]]; then
  install_hedwig_client
fi
