/**
 * Copyright (C) Stony Brook University 2016-2017
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

#ifndef __PERF_STATS_H__
#define __PERF_STATS_H__

#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * A counter of function calling statistics.
 */
struct perf_func_counter {
	const char *name;
	uint32_t calls;      /* # of calls (or RPCs) */
	uint32_t failures;   /* # of failures of the calls */
	uint64_t bytes;	     /* size of operations (RPC bytes) */
	uint64_t time_ns;    /* the total time in calling these functions */
	struct perf_func_counter *next;
	bool registered;
};

void perf_register_counter(struct perf_func_counter *tfc);

void perf_iterate_counters(bool (*tfc_reader)(struct perf_func_counter *tfc,
					      void *arg),
			   void *arg);

void init_perf_counters();

#define PERF_ENABLED 1

#define PERF_COUNTER_OUTPUT_INTERVAL 5

#if PERF_ENABLED

#define PERF_DECLARE_COUNTER(nm)                                               \
	struct timespec nm##_start_tm;                                         \
	struct timespec nm##_stop_tm;                                          \
	static struct perf_func_counter nm##_perf_counter = {.name = #nm,      \
							     .calls = 0,       \
							     .bytes = 0,       \
							     .time_ns = 0,     \
							     .next = NULL,     \
							     .registered =     \
								 false };      \
	perf_register_counter(&nm##_perf_counter)

#define PERF_START_COUNTER(nm)                                                 \
	now(&nm##_start_tm);                                                   \
	__sync_fetch_and_add(&nm##_perf_counter.calls, 1)

#define PERF_STOP_COUNTER(nm, b, succeed)                                      \
	do {                                                                   \
		now(&nm##_stop_tm);                                            \
		if (succeed) {                                                 \
			__sync_fetch_and_add(&nm##_perf_counter.bytes,         \
					    (uint64_t)b);                      \
			__sync_fetch_and_add(                                  \
			    &nm##_perf_counter.time_ns,                        \
			    timespec_diff(&nm##_start_tm, &nm##_stop_tm));     \
		} else {                                                       \
			__sync_fetch_and_add(&nm##_perf_counter.failures, 1);  \
		}                                                              \
	} while (false)

#else

#define PERF_DECLARE_COUNTER(nm)
#define PERF_START_COUNTER(nm)
#define PERF_STOP_COUNTER(nm, bytes, succeed)

#endif

#ifdef __cplusplus
}
#endif

#endif // __PERF_STATS_H__
