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
#include "writeback_manager.h"
#include "cache_handle.h"
#include "pcachefs_methods.h"

struct obj_for_wb_t {
	struct avltree_node avl_node;
	struct glist_head list_node;
	struct fsal_obj_handle *obj_hdl;
	time_t last_wb;
	unsigned int refcnt;
};

static struct avltree tree_head;
static struct glist_head list_head;

static writebackfunc do_writeback;
static pthread_t timer_tid;
static pthread_mutex_t big_lock;

// Number of manager threads.  Each writeback one file.  Again, each manager
// thread may schedule multiple workers to writeback the blocks of the file.
#define MAX_WB_THREADS 128
#define WB_CHECK_PERIOD_MS 100
#define JOBS_PER_THREAD 1
static struct glist_head wb_thread_list_head;

struct wb_thread_t {
	struct glist_head list_node;
	struct glist_head obj_list;
	pthread_t tid;
} wb_thread_obj[MAX_WB_THREADS];

static inline struct obj_for_wb_t *wb_avltree_insert(struct obj_for_wb_t *obj)
{
	struct avltree_node *node;
	node = avltree_insert(&obj->avl_node, &tree_head);
	if (node)
		return avltree_container_of(node, struct obj_for_wb_t,
					    avl_node);
	return NULL;
}

static inline void wb_avltree_remove(struct obj_for_wb_t *obj)
{
	avltree_remove(&obj->avl_node, &tree_head);
}

static inline void wb_glist_add_tail(struct obj_for_wb_t *obj,
				     struct glist_head *list_hd)
{
	glist_add_tail(list_hd, &obj->list_node);
}

static inline void wb_glist_del(struct obj_for_wb_t *obj)
{
	glist_del(&obj->list_node);
}

static inline void up_ref(struct obj_for_wb_t *obj) { ++obj->refcnt; }

static inline void down_ref(struct obj_for_wb_t *obj)
{
	if (--obj->refcnt) {
		return;
	}
	PC_FULL("Deleting %p", obj->obj_hdl);
	wb_avltree_remove(obj);
	wb_glist_del(obj);
	free(obj);
}

static int compare_wb_file_node(const struct avltree_node *avl_node1,
				const struct avltree_node *avl_node2)
{
	const struct obj_for_wb_t *obj1 =
	    avltree_container_of(avl_node1, struct obj_for_wb_t, avl_node);
	const struct obj_for_wb_t *obj2 =
	    avltree_container_of(avl_node2, struct obj_for_wb_t, avl_node);
	return (int)((unsigned long)obj1->obj_hdl -
		     (unsigned long)obj2->obj_hdl);
}

static inline struct wb_thread_t *get_wb_thread()
{
	struct wb_thread_t *thread_obj = glist_first_entry(
	    &wb_thread_list_head, struct wb_thread_t, list_node);
	if (thread_obj->tid != 0) {
		return NULL;
	}
	glist_del(&thread_obj->list_node);
	glist_add_tail(&wb_thread_list_head, &thread_obj->list_node);
	glist_init(&thread_obj->obj_list);
	return thread_obj;
}

static inline void put_wb_thread(struct wb_thread_t *thread_obj)
{
	thread_obj->tid = 0;
	glist_del(&thread_obj->list_node);
	glist_add(&wb_thread_list_head, &thread_obj->list_node);
}

static void *wb_thread(void *);
static inline int start_wb_thread(struct wb_thread_t *thread_obj)
{
	return pthread_create(&thread_obj->tid, NULL, &wb_thread, thread_obj);
}

static inline void wb_thread_join_all()
{
	struct wb_thread_t *thread_obj;
	struct glist_head *node;
	glist_for_each(node, &wb_thread_list_head)
	{
		thread_obj = glist_entry(node, struct wb_thread_t, list_node);
		if (thread_obj->tid) {
			pthread_join(thread_obj->tid, NULL);
			thread_obj->tid = 0;
		}
	}
}

static void *wb_thread(void *arg)
{
	struct wb_thread_t *thread_obj = (struct wb_thread_t *)arg;
	struct glist_head *node;
	struct glist_head *noden;
	struct obj_for_wb_t *obj;
	time_t cur;
	glist_for_each(node, &thread_obj->obj_list)
	{
		obj = glist_entry(node, struct obj_for_wb_t, list_node);
		do_writeback(obj->obj_hdl);
	}
	pthread_mutex_lock(&big_lock);
	time(&cur);
	glist_for_each_safe(node, noden, &thread_obj->obj_list)
	{
		obj = glist_entry(node, struct obj_for_wb_t, list_node);
		obj->last_wb = cur;
		down_ref(obj);
	}
	glist_splice_tail(&list_head, &thread_obj->obj_list);
	put_wb_thread(thread_obj);
	pthread_mutex_unlock(&big_lock);
	pthread_exit(0);
}

static void *timer_thread(void *arg)
{
	time_t cur;
	struct glist_head *node;
	struct glist_head *noden;
	struct obj_for_wb_t *obj;
	struct wb_thread_t *thread_obj;
	int jobs;

	while (true) {
		usleep(WB_CHECK_PERIOD_MS * 1000);
		pthread_mutex_lock(&big_lock);
		thread_obj = get_wb_thread();
		if (thread_obj == NULL) {
			pthread_mutex_unlock(&big_lock);
			continue;
		}
		time(&cur);
		jobs = 0;
		glist_for_each_safe(node, noden, &list_head)
		{
			obj = glist_entry(node, struct obj_for_wb_t, list_node);
			if (difftime(cur, obj->last_wb) > writeback_seconds) {
				wb_glist_del(obj);
				wb_glist_add_tail(obj, &thread_obj->obj_list);
				up_ref(obj);
				obj->last_wb = cur;
				PC_FULL("time %s: going to writeback %p",
					ctime(&cur), obj->obj_hdl);
				if (++jobs >= JOBS_PER_THREAD) {
					break;
				}
			} else {
				break;
			}
		}
		if (!glist_empty(&thread_obj->obj_list)) {
			int ret;
			if ((ret = start_wb_thread(thread_obj)) != 0) {
				PC_CRT("start thread failed %d", ret);
				glist_for_each_safe(node, noden,
						    &thread_obj->obj_list)
				{
					obj = glist_entry(node,
							  struct obj_for_wb_t,
							  list_node);
					down_ref(obj);
				}
				glist_splice_tail(&list_head,
						  &thread_obj->obj_list);
				put_wb_thread(thread_obj);
			}
		} else {
			put_wb_thread(thread_obj);
		}
		pthread_mutex_unlock(&big_lock);
	};
}

fsal_status_t wb_mgr_init(writebackfunc wb)
{
	int ret, i;
	do_writeback = wb;
	if ((ret = pthread_mutex_init(&big_lock, NULL)) != 0) {
		PC_CRT("pthread_mutex_init failed with %d", ret);
		return fsalstat(ERR_FSAL_FAULT, 0);
	}
	glist_init(&list_head);
	if ((ret = avltree_init(&tree_head, compare_wb_file_node, 0)) != 0) {
		PC_CRT("avltree_init returned %d", ret);
		return fsalstat(ERR_FSAL_FAULT, 0);
	}
	glist_init(&wb_thread_list_head);
	for (i = 0; i < MAX_WB_THREADS; i++) {
		glist_add_tail(&wb_thread_list_head,
			       &wb_thread_obj[i].list_node);
	}
	// start thread with default attributes
	ret = pthread_create(&timer_tid, NULL, &timer_thread, NULL);
	if (ret != 0) {
		PC_CRT("pthread_create failed: ret %d", ret);
		return fsalstat(ERR_FSAL_FAULT, 0);
	}
	LogDebug(COMPONENT_FSAL, "timer thread started");
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

fsal_status_t wb_mgr_destroy()
{
	// TODO(arun):
	// wait for all threads
	// free resources
}

fsal_status_t register_wb_file(struct fsal_obj_handle *obj_hdl)
{
	struct obj_for_wb_t *obj =
	    (struct obj_for_wb_t *)malloc(sizeof(struct obj_for_wb_t));
	if (!obj) {
		return fsalstat(ERR_FSAL_NOMEM, 0);
	}
	memset(obj, 0, sizeof(struct obj_for_wb_t));
	struct obj_for_wb_t *ret;
	// insert in avl tree
	obj->obj_hdl = obj_hdl;
	pthread_mutex_lock(&big_lock);
	time(&obj->last_wb);
	if (ret = wb_avltree_insert(obj)) {
		PC_FULL("obj already exists");
		free(obj);
		obj = ret;
	} else {
		wb_glist_add_tail(obj, &list_head);
	}
	up_ref(obj);
	PC_FULL("file obj: %p, refcnt: %u, time: %s", obj->obj_hdl, obj->refcnt,
		ctime(&obj->last_wb));
	pthread_mutex_unlock(&big_lock);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

fsal_status_t deregister_wb_file(struct fsal_obj_handle *obj_hdl)
{
	struct obj_for_wb_t item, *obj;
	struct avltree_node *node;
	item.obj_hdl = obj_hdl;
	pthread_mutex_lock(&big_lock);
	node = avltree_lookup(&item.avl_node, &tree_head);
	if (!node) {
		pthread_mutex_unlock(&big_lock);
		PC_CRT("Unable to find object handle %p", obj_hdl);
		return fsalstat(ERR_FSAL_FAULT, 0);
	}
	obj = avltree_container_of(node, struct obj_for_wb_t, avl_node);
	down_ref(obj);
	PC_FULL("%p is unregistered", obj_hdl);
	pthread_mutex_unlock(&big_lock);
	return fsalstat(ERR_FSAL_NO_ERROR, 0);
}

/**
 * vim:noexpandtab:shiftwidth=8:tabstop=8:
 */
