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
#ifndef AVFS_ANTIVIRUS_H
#define AVFS_ANTIVIRUS_H

#include <stdint.h>
#include <stdio.h>

typedef enum {
	AV_INIT_FAILED,
	AV_INIT_SUCCESS,
	AV_NO_VIRUS,
	AV_VIRUS,
	AV_ERROR
} av_status_t;

av_status_t av_init();
av_status_t av_scan(const char *tmpfilename, const char **virus);

#endif // AVFS_ANTIVIRUS_H

