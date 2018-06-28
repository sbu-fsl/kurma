#!/bin/bash - 
#=============================================================================
# 
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

# create the stress file if not exist
#./dixio_stress -n 100m -c 1 -i 256 /vfs-ganesha/stress_file

#[[ -f /vfs-ganesha/stress_file ]] && echo "file created"

for i in $(seq 1 10); do
  ./dixio_stress -i 2 -c 10000 -s $i /vfs-ganesha/stress_file &
done
