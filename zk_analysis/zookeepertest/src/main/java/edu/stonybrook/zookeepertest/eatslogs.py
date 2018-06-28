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
#!/bin/python

import sys

# GLOBAL VARS
#FILNAME = 'log_creator_1489807227364'
#FILNAME = 'log_reader_1489807483245'
#FILNAME = 'log_modifier_1489807491162'
FILNAME = 'log_deleter_1489807735417'
znums = []
times = []
pmems = []
vmems = []


def main(args):
    global FILNAME
    global znums
    global times
    global pmems
    global vmems
    of = open(FILNAME, 'r')

    # Read the file
    for line in of:
        words = []
        if ('record' in line):
            words = (line.strip()).split(' ')
            znums.append(int(words[-1]))
        elif ('java' in line):
            words = (line.strip()).split(' ')
            pmems.append(int(words[11]) / 1000)
            vmems.append(int(words[12]) / 1000)
        else:
            times.append(int((line.strip())))
    of.close()

    # Calculate second difference
    i = 0
    val = times[0]
    for k in times:
        times[i] = (k - val)/1000
        i += 1

    # Print znums
    print("\n===== ZNUMS =====")
    for k in znums:
        print(k)

    # Print pmems
    print("\n===== PMEMS =====")
    for k in pmems:
        print(k)

    # Print vmems
    print("\n===== VMEMS =====")
    for k in vmems:
        print(k)

    # Print times
    print("\n===== TIMES =====")
    for k in times:
        print(k)

if __name__ == "__main__":
    main(sys.argv[1:])
