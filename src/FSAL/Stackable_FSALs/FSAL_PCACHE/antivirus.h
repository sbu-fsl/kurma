/*
 * Copyright (c) 2013-2018 Ming Chen
 * Copyright (c) 2016-2016 Praveen Kumar Morampudi
 * Copyright (c) 2016-2016 Harshkumar Patel
 * Copyright (c) 2017-2017 Rushabh Shah
 * Copyright (c) 2013-2014 Arun Olappamanna Vasudevan
 * Copyright (c) 2013-2014 Kelong Wang
 * Copyright (c) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
/* Anti virus utilities
 */
#ifndef PCACHEFS_ANTIVIRUS_H
#define PCACHEFS_ANTIVIRUS_H

/*
 * We define our own error code from 20000, which is larger than all error code
 * predefined in the protocol. See page 340 of RFC5661.
 */
#define NFS4ERR_PRIVATE 20000
#define NFS4ERR_FILE_INFECTED 20001

#define IS_INFECTED(st) \
        (((st).major == ERR_FSAL_SEC) && (st).minor == NFS4ERR_FILE_INFECTED)

int av_init();
int av_scan(const void *buff, size_t len);

#endif
