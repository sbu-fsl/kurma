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
#pragma once

#include <set>
#include <string>
#include <unordered_map>

#include "util/slice.h"

namespace secnfs {
namespace base {

// A PathMapping is a database that maitains the mapping information between
// file pathes and NFS file handles.
// TODO(mchen): make PathMapping persistent
class PathMapping {
 public:
  // return file handle of the given path
  std::string PathToHandle(secnfs::util::Slice path) const;
  std::string PathToHandle(secnfs::util::Slice dir_fh,
                           secnfs::util::Slice name) const;

  // return file paths of the given file handle
  std::set<std::string> HandleToPaths(secnfs::util::Slice fh) const;
  // return the first path of the given file handle
  std::string HandleToPath(secnfs::util::Slice fh) const;

  // insert the (fh, path) pair into this mapping database
  void Insert(secnfs::util::Slice fh, secnfs::util::Slice path);
  std::string InsertAt(secnfs::util::Slice dir_fh, secnfs::util::Slice fh,
                       secnfs::util::Slice name);

  // return the full path of the file if exists, or empty if the given file
  // does not exist
  std::string Delete(secnfs::util::Slice dir_fh, secnfs::util::Slice name);

 private:
  std::unordered_map<std::string, std::set<std::string>> fh2path_;
  std::unordered_map<std::string, std::string> path2fh_;
};

}  // namespace base
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
