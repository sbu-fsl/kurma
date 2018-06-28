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
logfile='./logperf'
COUNTER=2000

for i in `seq 1 $COUNTER`
do
    date >> $logfile
    ps -euf | grep 'zookeeper' | cut -c1-100 | grep 'Dzoo' >> $logfile
    #tail -n 1 /var/log/kurma.log >> $logfile
    sleep 1
done
