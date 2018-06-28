#!/bin/bash - 
#=============================================================================
# Stop Kurma server processes.
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

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
WD=$(pwd)
cd $DIR

set -x

killdaemon ganesha
killdaemon "run-kurma-fsal.sh"

killdaemon KurmaServer
killdaemon startserver.sh

cd $WD
