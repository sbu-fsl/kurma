# Copyright (c) 2013-2018 Ming Chen
# Copyright (c) 2016-2016 Praveen Kumar Morampudi
# Copyright (c) 2016-2016 Harshkumar Patel
# Copyright (c) 2017-2017 Rushabh Shah
# Copyright (c) 2013-2014 Arun Olappamanna Vasudevan
# Copyright (c) 2013-2014 Kelong Wang
# Copyright (c) 2013-2018 Erez Zadok
# Copyright (c) 2013-2018 Stony Brook University
# Copyright (c) 2013-2018 The Research Foundation for SUNY
# This file is released under the GPL.
#!/bin/bash

# Run Prefix: The starting prefix of znodes to be created
# Action: See action table
# Znum: Number of Znodes on which to take action
# Ztype: See type table

# Actions:
# 1. Create
# 2. Read
# 3. Update
# 4. Delete

# Types:
# 1. Ephemeral
# 2. Persistent
# 3. Persistent Sequential

##########
# PARAMS #
##########
RUNPREFIX="/rpzn"
ACTION=1
ZNUM=10000
ZTYPE=2


#################
# DO NOT MODITY #
#################
echo "Zookeeper must be running for this test. Use port 2181 for Zookeeper."
echo "Zookeeper must be running for this test. Use port 2181 for Zookeeper."
echo "Zookeeper must be running for this test. Use port 2181 for Zookeeper."
echo "Zookeeper must be running for this test. Use port 2181 for Zookeeper."
mvn exec:java -Dexec.mainClass="edu.stonybrook.zookeepertest.App" -Dexec.args="$RUNPREFIX $ACTION $ZNUM $ZTYPE"
