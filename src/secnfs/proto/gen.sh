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
# Generate Java code from the Thrift files.
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

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
TARGET_DIR=${DIR}/../../../target
GEN_JAVA_DIR="${TARGET_DIR}/gen-java/edu/stonybrook/kurma"
KURMA_DIR=${DIR}/../../../java/main/edu/stonybrook/kurma

mkdir -p $TARGET_DIR

function gen_java() {
  local thrift_file="$1"
  local namespace="$2"

  mkdir -p ${KURMA_DIR}/${namespace}
  rm -f ${KURMA_DIR}/${namespace}/*.java
  rm -f ${GEN_JAVA_DIR}/${namespace}/*.java
  thrift --gen java -o ${TARGET_DIR} ${thrift_file}

  cp ${GEN_JAVA_DIR}/${namespace}/*.java ${KURMA_DIR}/${namespace}/
}

cd $DIR

gen_java Namespace.thrift meta
gen_java Kurma.thrift fs
gen_java GatewayMessage.thrift message
gen_java JournalRecords.thrift records

PYTHON_DIR=${DIR}/../../../python/prototest

function gen_python() {
  local name="$1"
  local outdir=${PYTHON_DIR}/${name}

  rm -rf ${outdir}
  mkdir -p ${outdir}

  thrift --gen py -out ${PYTHON_DIR} ${name}.thrift
}

gen_python Namespace
gen_python Kurma
gen_python GatewayMessage
gen_python JournalRecords

cd $OLDPWD
