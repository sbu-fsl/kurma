#!/bin/bash - 
#=============================================================================
# Build kurma and kurma_fsal
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

SECNFS_DIR="$(dirname $DIR)"
KURMA_ROOT="$(dirname $SECNFS_DIR)"

echo "KURMA_ROOT = $KURMA_ROOT"

updated=0
git pull origin kurma | grep -q -v 'Already up-to-date.' && updated=1

if [[ $updated = 1 ]]; then
  echo "re-compiling kurma"
  mkdir -p $KURMA_ROOT/target

  cd $KURMA_ROOT/src/secnfs/proto
  ./gen.sh

  cd $KURMA_ROOT
  mvn compile
fi

cd $KURMA_ROOT
mkdir -p release
cd release
cmake -DCMAKE_BUILD_TYPE=Release ../src
make && make install
