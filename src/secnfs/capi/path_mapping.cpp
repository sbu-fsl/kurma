// Copyright (c) 2013-2018 Ming Chen
// Copyright (c) 2016-2016 Praveen Kumar Morampudi
// Copyright (c) 2016-2016 Harshkumar Patel
// Copyright (c) 2017-2017 Rushabh Shah
// Copyright (c) 2013-2014 Arun Olappamanna Vasudevan 
// Copyright (c) 2013-2014 Kelong Wang
// Copyright (c) 2013-2018 Erez Zadok
// Copyright (c) 2013-2018 Stony Brook University
// Copyright (c) 2013-2018 The Research Foundation for SUNY
// This file is released under the GPL.
// PathMapping's C interface to NFS-Ganesha.

#include <set>
#include <string>

#include "capi/path_mapping.h"
#include "capi/cpputil.h"
#include "base/PathMapping.h"

using std::string;
using secnfs::base::PathMapping;
using secnfs::capi::FillString;
using secnfs::capi::ToSlice;

static PathMapping* pm;

int pm_init() {
  pm = new PathMapping();
  return pm == NULL ? -1 : 0;
}

int pm_insert(const char* path, const_buffer_t fh) {
  pm->Insert(ToSlice(fh), path);
  return 0;
}

int pm_insert_at(const_buffer_t dir_fh, const char* name, const_buffer_t fh,
                 buffer_t* path) {
  string p = pm->InsertAt(ToSlice(dir_fh), ToSlice(fh), name);
  return p.empty() ? -EINVAL : FillString(p, path);
}

int pm_delete(const_buffer_t dir_fh, const char* name, buffer_t* path) {
  string p = pm->Delete(ToSlice(dir_fh), name);
  return p.empty() ? -EINVAL : FillString(p, path);
}

int pm_lookup_handle(const_buffer_t dir_fh, const char* name,
                     buffer_t* result_fh) {
  string fh = pm->PathToHandle(ToSlice(dir_fh), name);
  return fh.empty() ? -EINVAL : FillString(fh, result_fh);
}

int pm_path_to_handle(const char* path, buffer_t* result_fh) {
  string fh = pm->PathToHandle(path);
  return fh.empty() ? -EINVAL : FillString(fh, result_fh);
}

int pm_handle_to_paths(const_buffer_t fh, size_t npath, buffer_t* paths) {
  int ret = 0;
  std::set<std::string> ps = pm->HandleToPaths(ToSlice(fh));
  auto it = ps.begin();
  for (size_t i = 0; i < npath && i < ps.size(); ++i, ++it) {
    if ((ret = FillString(*it, paths + i)) < 0)
      return ret;
  }
  return npath < ps.size() ? npath : ps.size();
}

void pm_destroy() {
  delete pm;
}

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
