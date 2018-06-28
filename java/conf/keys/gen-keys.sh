# Copyright (C) 2013-2018 Ming Chen
# Copyright (C) 2016-2016 Praveen Kumar Morampudi
# Copyright (C) 2016-2016 Harshkumar Patel
# Copyright (C) 2017-2017 Rushabh Shah
# Copyright (C) 2013-2018 Erez Zadok
# Copyright (c) 2013-2018 Stony Brook University
# Copyright (c) 2013-2018 The Research Foundation for SUNY
# This file is released under the GPL.
#!/bin/bash - 
#=============================================================================
# Generate gateway master key for testing
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

for gw in ny ca ct nv; do
  openssl genrsa -out ${gw}.pem 1024
  openssl pkcs8 -topk8 -inform PEM -outform DER -in ${gw}.pem -out ${gw}-pri.der -nocrypt
  openssl rsa -in ${gw}.pem -pubout -outform DER -out ${gw}-pub.der
done

mkdir -p pub/
mv *-pub.der pub/

mkdir -p deleted/
mv ct-pri.der deleted/
mv nv-pri.der deleted/
