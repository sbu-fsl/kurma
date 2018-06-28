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
