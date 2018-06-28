# Setup and install nfs-ganesha as server on CentOS7
#
# by Garima Gehlot, garima.gehlot@stonybrook.edu
#=============================================================================

set -o nounset                          # treat unset variables as an error
set -o errexit                          # stop script if command fail
export PATH="/bin:/usr/bin:/sbin:/usr/local/bin"
IFS=$' \t\n'                            # reset IFS
unset -f unalias                        # make sure unalias is not a function
\unalias -a                             # unset all aliases
ulimit -H -c 0 --                       # disable core dump
hash -r                                 # clear the command path hash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
SECNFS_HOME=$(dirname $DIR)
GANESHA_HOME=$(dirname $SECNFS_HOME)

HOME_DIR=`pwd`;
USR="";

# got to the root of this git repo
cd $DIR/../../

# NFS-ganesha specific
sudo git submodule update --init --recursive

# clean up the yum cache directory to get rid of
# obsolete headers, if any
#sudo yum clean all

sudo yum install -y cmake
sudo yum install -y glog-devel gflags-devel # libgssglue-devel
sudo yum install -y openssl-devel
sudo yum install -y libnfsidmap-devel
sudo yum install -y doxygen
sudo yum install -y gperftools-libs
sudo yum install -y protobuf-devel leveldb-devel snappy-devel opencv-devel boost-devel hdf5-devel
sudo yum install -y lmdb-devel jemalloc-devel tbb-devel libaio-devel cryptopp-devel
sudo yum -y groupinstall "Development Tools"
sudo yum install -y glibc-headers
sudo yum install -y gcc-c++
sudo yum install -y bison flex
sudo yum install -y libcurl-devel boost-system boost-filesystem boost-regex
sudo yum install -y boost-static
sudo yum install -y glib2-devel glib-devel
sudo yum install -y automake autoconf libtool
sudo yum install -y cryptopp-devel
sudo yum install -y maven
sudo yum install -y java-1.8.0-openjdk-devel
sudo yum install -y python-pip
sudo yum install -y python-gflags
pip install google-apputils

cd /opt

# set the environment variables required for configuration
# and add it to the .bashrc file as well

# setup gmock and gtest
if [ ! -d gmock-1.7.0 ]; then
  wget https://github.com/google/googlemock/archive/release-1.7.0.zip
  unzip release-1.7.0.zip
  mv googlemock-release-1.7.0 gmock-1.7.0

  cd gmock-1.7.0
  wget https://github.com/google/googletest/archive/release-1.7.0.zip
  unzip release-1.7.0.zip
  mv googletest-release-1.7.0 gtest

  autoreconf -fvi
  ./configure
  make
fi

echo "export GOOGLE_MOCK=/opt/gmock-1.7.0" >> ~/.bashrc
echo "export GOOGLE_TEST=/opt/gmock-1.7.0/gtest" >> ~/.bashrc

# install thrift
cd /opt
THRIFT_VERSION=0.9.3
wget http://apache.cs.utah.edu/thrift/${THRIFT_VERSION}/thrift-${THRIFT_VERSION}.tar.gz
tar xzf thrift-${THRIFT_VERSION}.tar.gz
cd thrift-${THRIFT_VERSION}
./configure --without-csharp \
  --without-lua \
  --without-nodejs \
  --without-haskell \
  --without-d \
  --without-perl \
  --with-c_glib
make
make install

# install clamav
cd $SECNFS_HOME/clamav
./install_clamav.sh 0001-libclamav-added-api-for-scanning-memory-containing-e.patch

# create build directory
cd $GANESHA_HOME
sudo mkdir -p release

# configure and install NFS ganesha
cd release && sudo -E cmake -DCMAKE_BUILD_TYPE=Release ../src/
sudo make && sudo make install

cd /opt
git clone https://github.com/pviotti/hybris.git
cd hybris
cd jerasure
make
cp ../lib/libJerasure* /lib64
cp ../lib/libJerasure* /usr/local/lib

cd /opt
git clone https://github.com/chintran27/CDStore.git
cd CDStore/trunk/src/client/lib/gf_complete
./configure && make
sudo make install

cd ${SECNFS_HOME}/../java
./install-jerasure-jar.sh
cd secret-sharing
make && make install

echo "export GOOGLE_TEST=/opt/gmock-1.7.0/gtest" >> ~/.bashrc
echo "export GOOGLE_MOCK=/opt/gmock-1.7.0" >> ~/.bashrc
echo "export THRIFT=/usr/local" >> ~/.bashrc
