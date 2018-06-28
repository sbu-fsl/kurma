#!/bin/bash - 
#=============================================================================
# Setup proxy-cache dependencies
# 
# by Ming Chen, v.mingchen@gmail.com
#=============================================================================

set -o nounset                          # treat unset variables as an error
set -o errexit                          # stop script if command fail
export PATH="/bin:/usr/bin:/sbin"             
IFS=$' \t\n'                            # reset IFS
unset -f unalias                        # make sure unalias is not a function
\unalias -a                             # unset all aliases
ulimit -H -c 0 --                       # disable core dump
hash -r                                 # clear the command path hash

#sudo yum install epel-release

yum install cryptopp-devel

cd /opt

#GTEST='gtest-1.7.0'
#wget http://googletest.googlecode.com/files/${GTEST}.zip
#unzip ${GTEST}.zip
#cd ${GTEST}
#./configure && make

#GMOCK='gmock-1.7.0'
#wget http://googlemock.googlecode.com/files/${GMOCK}.zip
#unzip ${GMOCK}.zip
#cd ${GMOCK}
#./configure && make

#wget http://downloads.sourceforge.net/project/boost/boost/1.58.0/boost_1_58_0.zip
#unzip boost_1_58_0.zip
#cd boost_1_58_0
#./bootstrap.sh
#./b2

#wget https://github.com/google/glog/archive/v0.3.4.zip
#unzip v0.3.4.zip
#cd glog-0.3.4
#./configure && make && make install

#ZK='zookeeper-3.4.6'
#wget http://apache.cs.utah.edu/zookeeper/${ZK}/${ZK}.tar.gz
#tar xzf ${ZK}.tar.gz
#cd ${ZK}
#cd src/c
#./configure && make && make install

wget https://github.com/gflags/gflags/archive/v2.1.2.zip
unzip v2.1.2.zip
cd gflags-2.1.2
mkdir build && cd build
cmake ..
make && make install
