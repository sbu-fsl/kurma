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
#include "antivirus.h"
#include "log.h"
#include <clamav.h>

static struct cl_engine *engine = NULL;
const char *dbdir;

av_status_t av_init() {

	int ret;
	int sigs = 0;

	if(engine != NULL) {
		LogDebug(COMPONENT_FSAL,
			 "Antivirus engine already running");
		return AV_INIT_SUCCESS;
	}

	if((ret = cl_init(0)) != CL_SUCCESS) {
		LogDebug(COMPONENT_FSAL,
			 "Failed to initialize ClamAV, ret %d", ret);
		return AV_INIT_FAILED;
	}

	if(!(engine = cl_engine_new())) {
		// TODO: uninitialize clamav?
		LogDebug(COMPONENT_FSAL,
			 "Failed to create new ClamAV engine");
		return AV_INIT_FAILED;
	}

    if((ret = cl_initialize_crypto()) != 0) {
        LogDebug(COMPONENT_FSAL,
                "Failed to initialize ClamAV crypto, ret %d", ret);
        return AV_INIT_FAILED;
    }

	dbdir = cl_retdbdir();
	if((ret = cl_load(dbdir, engine, &sigs, CL_DB_STDOPT)) != CL_SUCCESS) {
		// TODO: uninitialize clamav?
		LogDebug(COMPONENT_FSAL,
			"Failed to load ClamAV database %s, ret %d, sigs %d",
			dbdir, ret, sigs);
		return AV_INIT_FAILED;
	}

	if((ret = cl_engine_compile(engine)) != CL_SUCCESS) {
		// TODO: uninitialize clamav?
		LogDebug(COMPONENT_FSAL,
			"Failed to compile ClamAV, ret %d", ret);
		return AV_INIT_FAILED;
	}

	LogDebug(COMPONENT_FSAL,
		"ClamAV initialized successfully");
	return AV_INIT_SUCCESS;
}

av_status_t av_scan(const char *tmpfilename, const char **virus) {

	int ret = cl_scanfile(tmpfilename, virus, NULL, engine, CL_SCAN_STDOPT);

	if(ret == CL_CLEAN) {
		return AV_NO_VIRUS;
	}

	if(ret == CL_VIRUS) {
		return AV_VIRUS;
	}

	LogCrit(COMPONENT_FSAL,
		"ClamAV internal error %d", ret);
	return AV_ERROR;
}

