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

#include "perf_stats.h"
#include "common_types.h"

#include <pthread.h>

static pthread_t perf_counter_thread;
static const char* perf_counter_path = "/tmp/kurma-stats.txt";
static int perf_counter_running = 0;

static struct perf_func_counter *perf_all_counters = NULL;
static pthread_mutex_t perf_counter_lock = PTHREAD_MUTEX_INITIALIZER;

void perf_register_counter(struct perf_func_counter *tfc)
{
        if (!tfc->registered) {
                pthread_mutex_lock(&perf_counter_lock);
		tfc->registered = true;
                tfc->next = perf_all_counters;
                perf_all_counters = tfc;
                pthread_mutex_unlock(&perf_counter_lock);
        }
}

void perf_iterate_counters(bool (*tfc_reader)(struct perf_func_counter *tfc,
					      void *arg),
			   void *arg)
{
        struct perf_func_counter *tfc;

        pthread_mutex_lock(&perf_counter_lock);
        for (tfc = perf_all_counters; tfc; tfc = tfc->next) {
                if (!tfc_reader(tfc, arg)) {
                        break;
                }
        }
        pthread_mutex_unlock(&perf_counter_lock);
}

bool perf_counter_printer(struct perf_func_counter *tfc, void *arg)
{
	buf_t *pbuf = (buf_t *)arg;
	buf_appendf(pbuf, "%s %u %u %llu %llu ",
		    tfc->name,
		    __sync_fetch_and_or(&tfc->calls, 0),
		    __sync_fetch_and_or(&tfc->failures, 0),
		    __sync_fetch_and_or(&tfc->bytes, 0),
		    __sync_fetch_and_or(&tfc->time_ns, 0));
	return true;
}

static void *output_perf_counters(void *arg)
{
	char buf[4096];
	buf_t bf = BUF_INITIALIZER(buf, 4096);

	FILE *pfile = fopen(perf_counter_path, "w");
	while (__sync_fetch_and_or(&perf_counter_running, 0)) {
		buf_reset(&bf);
		perf_iterate_counters(perf_counter_printer, &bf);
		buf_append_char(&bf, '\n');
		fwrite(bf.data, 1, bf.size, pfile);
		fflush(pfile);
		sleep(PERF_COUNTER_OUTPUT_INTERVAL);
	}
	fclose(pfile);
	return NULL;
}

void init_perf_counters()
{
	int retval;

	if (!__sync_fetch_and_or(&perf_counter_running, 0)) {
		__sync_fetch_and_or(&perf_counter_running, 1);
		retval = pthread_create(&perf_counter_thread, NULL,
					&output_perf_counters, NULL);
		if (retval != 0) {
			fprintf(stderr,
				"failed to create perf_counter thread: %s\n",
				strerror(retval));
		}
	}
}
