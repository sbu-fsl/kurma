# Copyright (C) 2013-2018 Ming Chen
# Copyright (C) 2016-2016 Praveen Kumar Morampudi
# Copyright (C) 2016-2016 Harshkumar Patel
# Copyright (C) 2017-2017 Rushabh Shah
# Copyright (C) 2013-2018 Erez Zadok
# Copyright (c) 2013-2018 Stony Brook University
# Copyright (c) 2013-2018 The Research Foundation for SUNY
# This file is released under the GPL.
#!/bin/bash -
#
# Perform latency test of zookeeper using "zk-smoketest" at
# https://github.com/phunt/zk-smoketest

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
ZK_HOME=/root/hedwig/zookeeper-3.4.6

function install() {
  git clone https://github.com/phunt/zk-smoketest.git
  sudo yum -y install python-devel
  cd $ZK_HOME/src/contrib/zkpython/
  ant compile

  cd $ZK_HOME/src/c
  ./configure && make && sudo make install
}

cd $DIR/zk-smoketest

export PYTHONPATH=$ZK_HOME/build/contrib/zkpython/lib.linux-x86_64-2.7/

export LD_LIBRARY_PATH=$ZK_HOME/build/contrib/zkpython/lib.linux-x86_64-2.7/:/usr/local/lib

./zk-latencies.py \
  --servers="130.245.177.111:2181,130.245.177.112:2181,130.245.177.113:2181" \
  --znode_count=100 \
  --znode_size=100 \
  --synchronous
