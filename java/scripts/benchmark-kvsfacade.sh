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

export LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/local/lib"

# write, no duplicate
#mvn exec:java -Dexec.mainClass="edu.stonybrook.kurma.bench.KvsFacadeBenchmark" \
    #-Dexec.args="-w -k test -i 10 -c azure0,google0,amazon0,rackspace0"

# read
mvn exec:java -Dexec.mainClass="edu.stonybrook.kurma.bench.KvsFacadeBenchmark" \
   -Dexec.args="-k test -i 10 -c azure0,google0,amazon0,rackspace0"
