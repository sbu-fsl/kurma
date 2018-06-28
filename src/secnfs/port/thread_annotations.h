// Copyright (c) 2012 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

#ifndef STORAGE_LEVELDB_PORT_THREAD_ANNOTATIONS_H_
#define STORAGE_LEVELDB_PORT_THREAD_ANNOTATIONS_H_

// Some environments provide custom macros to aid in static thread-safety
// analysis.  Provide empty definitions of such macros unless they are already
// defined.

#ifndef GUARDED_BY
#define GUARDED_BY(x)
#endif

#ifndef PT_GUARDED_BY
#define PT_GUARDED_BY(x)
#endif

#ifndef REQUIRES
#define REQUIRES(...)
#endif

#ifndef REQUIRES_SHARED
#define REQUIRES_SHARED(...)
#endif

#ifndef ACQUIRE
#define ACQUIRE(...)
#endif

#ifndef ACQUIRE_SHARED
#define ACQUIRE_SHARED(...)
#endif

#ifndef RELEASE
#define RELEASE(...)
#endif

#ifndef RELEASE_SHARED
#define RELEASE_SHARED(...)
#endif

#ifndef EXCLUDES
#define EXCLUDES(...)
#endif

#ifndef NO_THREAD_SAFETY_ANALYSIS
#define NO_THREAD_SAFETY_ANALYSIS
#endif

#ifndef RETURN_CAPABILITY
#define RETURN_CAPABILITY(x)
#endif

#ifndef ACQUIRED_BEFORE
#define ACQUIRED_BEFORE(...)
#endif

#ifndef ACQUIRED_AFTER
#define ACQUIRED_AFTER(...)
#endif

#ifndef CAPABILITY
#define CAPABILITY(x)
#endif

#ifndef SCOPED_CAPABILITY
#define SCOPED_CAPABILITY
#endif

#ifndef TRY_ACQUIRE
#define TRY_ACQUIRE(...)
#endif

#ifndef TRY_ACQUIRE_SHARED
#define TRY_ACQUIRE_SHARED(...)
#endif

#ifndef ASSERT_CAPABILITY
#define ASSERT_CAPABILITY(x)
#endif

#ifndef ASSERT_SHARED_CAPABILITY
#define ASSERT_SHARED_CAPABILITY(x)
#endif

// below are deprecated macros
// http://clang.llvm.org/docs/ThreadSafetyAnalysis.html
#ifndef EXCLUSIVE_LOCKS_REQUIRED
#define EXCLUSIVE_LOCKS_REQUIRED(...)
#endif

#ifndef SHARED_LOCKS_REQUIRED
#define SHARED_LOCKS_REQUIRED(...)
#endif

#ifndef LOCKS_EXCLUDED
#define LOCKS_EXCLUDED(...)
#endif

#ifndef LOCK_RETURNED
#define LOCK_RETURNED(x)
#endif

#ifndef LOCKABLE
#define LOCKABLE
#endif

#ifndef SCOPED_LOCKABLE
#define SCOPED_LOCKABLE
#endif

#ifndef EXCLUSIVE_LOCK_FUNCTION
#define EXCLUSIVE_LOCK_FUNCTION(...)
#endif

#ifndef SHARED_LOCK_FUNCTION
#define SHARED_LOCK_FUNCTION(...)
#endif

#ifndef EXCLUSIVE_TRYLOCK_FUNCTION
#define EXCLUSIVE_TRYLOCK_FUNCTION(...)
#endif

#ifndef SHARED_TRYLOCK_FUNCTION
#define SHARED_TRYLOCK_FUNCTION(...)
#endif

#ifndef UNLOCK_FUNCTION
#define UNLOCK_FUNCTION(...)
#endif

#ifndef GUARDED_VAR
#define GUARDED_VAR
#endif

#ifndef PT_GUARDED_VAR
#define PT_GUARDED_VAR
#endif

#endif  // STORAGE_LEVELDB_PORT_THREAD_ANNOTATIONS_H_
