#!/bin/bash - 
#=============================================================================
# Deploy config files
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

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

DEST=/etc/ganesha

for file in *.conf; do
  src=$(readlink -f $DIR/$file)
  dst=$(readlink -f $DEST/$file)
  if [[ $src == $dst ]]; then
    echo "$file already deployed"
  else
    rm -f $dst
    ln -s $src $dst
  fi
done
