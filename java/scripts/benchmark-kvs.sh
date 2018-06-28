# Copyright (C) 2013-2018 Ming Chen
# Copyright (C) 2016-2016 Praveen Kumar Morampudi
# Copyright (C) 2016-2016 Harshkumar Patel
# Copyright (C) 2017-2017 Rushabh Shah
# Copyright (C) 2013-2018 Erez Zadok
# Copyright (c) 2013-2018 Stony Brook University
# Copyright (c) 2013-2018 The Research Foundation for SUNY
# This file is released under the GPL.
#!/bin/bash -

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
KURMA_ROOT=${DIR}/../../

cd $KURMA_ROOT
# write, no duplicate
mvn exec:java -Dexec.mainClass="edu.stonybrook.kurma.bench.CloudStatistisBench" \
   -Dexec.args="-w -k bench -i 100 -s 16,64,256,1024,4096 -c rackspace0"
   #-Dexec.args="-w -k bench -i 100 -s 16,64,256,1024,4096 -c google0,amazon0,rackspace0"

## write, duplicate
#mvn exec:java -Dexec.mainClass="edu.stonybrook.kurma.bench.CloudStatistisBench" \
   #-Dexec.args="-w -d -k bench -i 100 -s 16,64,256,1024,4096 -c google0,amazon0,rackspace0"
   ##-Dexec.args="-w -d -k bench -i 100 -s 16,64,256,1024,4096 -c azure0"

# read
mvn exec:java -Dexec.mainClass="edu.stonybrook.kurma.bench.CloudStatistisBench" \
   -Dexec.args="-k bench -i 100 -s 16,64,256,1024,4096 -c rackspace0"
   #-Dexec.args="-k bench -i 100 -s 16,64,256,1024,4096 -c google0,amazon0,rackspace0"
   #-Dexec.args="-k bench -i 100 -s 16,64,256,1024,4096 -c azure0"
