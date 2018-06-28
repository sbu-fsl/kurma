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
import os

comstr = "/root/zookeeper-3.4.9/bin/zkCli.sh delete /somezn"
for i in range(1, 1000):
#    actstr = comstr + str(i) + " abcde"
    actstr = comstr + str(i)
    os.system(actstr)
