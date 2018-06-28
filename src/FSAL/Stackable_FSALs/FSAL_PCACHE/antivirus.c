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
#include <clamav.h>
#include "antivirus.h"
#include "log.h"

static struct cl_engine *engine = NULL;

int av_init()
{
	int ret;
	int sigs = 0;
	const char *dbdir;

	if (engine != NULL) {
		LogDebug(COMPONENT_FSAL, "antivirus engine already running");
		return 0;
	}
	if ((ret = cl_init(0)) != CL_SUCCESS) {
		LogDebug(COMPONENT_FSAL, "failed to initialize clamav, ret %d", ret);
		return -1;
	}
	if (!(engine = cl_engine_new())) {
		// TODO: uninitialize clamav?
		LogDebug(COMPONENT_FSAL, "failed to create new clamav engine");
		return -1;
	}
	if ((ret = cl_initialize_crypto()) != 0) {
		LogDebug(COMPONENT_FSAL, "failed to initialize clamav crypto, ret %d", ret);
		return -1;
	}
	dbdir = cl_retdbdir();
	if ((ret = cl_load(dbdir, engine, &sigs, CL_DB_STDOPT)) != CL_SUCCESS) {
		// TODO: uninitialize clamav?
		LogDebug(COMPONENT_FSAL, "failed to load clamav database %s, ret %d, sigs %d",
			 dbdir, ret, sigs);
		return -1;
	}
	if ((ret = cl_engine_compile(engine)) != CL_SUCCESS) {
		// TODO: uninitialize clamav?
		LogDebug(COMPONENT_FSAL, "failed to compile clamav, ret %d", ret);
		return -1;
	}

	LogDebug(COMPONENT_FSAL, "clamav initialized successfully");
	return 0;
}

int av_scan(const void *buff, size_t len)
{
	int cl_ret;
	const char *virus;

	if (!engine) {
		LogWarn(COMPONENT_FSAL,
			"not scanning: clamav not initialized!");
		return 0;
	}

	cl_ret = cl_scanbuff(buff, len, &virus, NULL, engine, CL_SCAN_STDOPT);
	if (cl_ret == CL_CLEAN) {
		LogDebug(COMPONENT_FSAL, "clean");
		return 0;
	}

	if (cl_ret == CL_VIRUS) {
		LogCrit(COMPONENT_FSAL, "infected by virus %s", virus);
		return -1;
	}

	LogWarn(COMPONENT_FSAL, "clamav internal error %d", cl_ret);
	return 0;
}
