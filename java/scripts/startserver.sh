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

ulimit -n 102400

export LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/local/lib"

rm -rf /tmp/Journals/*
rm -rf /var/log/kurma.*

cd $KURMA_ROOT
mvn exec:java -Dexec.mainClass="edu.stonybrook.kurma.server.KurmaServer" \
   -Dlog4j.configuration="log4j.release.properties" \
   -Dexec.args="localhost:2181"
