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
#include "base/PathMapping.h"

#include <glog/logging.h>
#include <set>
#include <string>

using std::string;
using secnfs::util::Slice;

namespace secnfs {
namespace base {

string PathMapping::PathToHandle(Slice path) const {
  string res;
  auto it = path2fh_.find(path.rtrim('/').ToString());
  if (it != path2fh_.end()) {
    res = it->second;
  }
  return res;
}

string PathMapping::PathToHandle(Slice dir_fh, Slice name) const {
  string res;
  string dir = HandleToPath(dir_fh);
  if (!dir.empty()) {
    res = PathToHandle(dir + '/' + name.ToString());
  }
  return res;
}

string PathMapping::HandleToPath(Slice fh) const {
  string res;
  auto it = fh2path_.find(fh.ToString());
  if (it != fh2path_.end() && !it->second.empty()) {
    CHECK_EQ(1, it->second.size()) << "more than one path found";
    res = *(it->second.begin());
  }
  return res;
}

std::set<string> PathMapping::HandleToPaths(Slice fh) const {
  std::set<string> paths;
  auto it = fh2path_.find(fh.ToString());
  if (it != fh2path_.end() && !it->second.empty()) {
    paths = it->second;
  }
  return paths;
}

void PathMapping::Insert(Slice fh, Slice path) {
  // Directories are kept without any trailing '/'.
  path.rtrim('/');
  CHECK(!path.empty()) << "invalid path: " << path;
  string sp = path.ToString();
  fh2path_[fh.ToString()].insert(sp);
  path2fh_[sp] = fh.ToString();
}

string PathMapping::InsertAt(Slice dir_fh, Slice fh, Slice name) {
  string dir = HandleToPath(dir_fh);
  CHECK(!dir.empty()) << "Insert file into non-existing directory.";
  string path = dir + '/' + name.rtrim('/').ToString();
  Insert(fh, path);
  return path;
}

string PathMapping::Delete(Slice dir_fh, Slice name) {
  string dir = HandleToPath(dir_fh);
  CHECK(!dir.empty()) << "Delete file from non-existing directory.";
  string path = dir + '/' + name.rtrim('/').ToString();
  string fh = PathToHandle(path);
  if (!fh.empty()) {
    auto it = fh2path_.find(fh);
    it->second.erase(path);
    if (it->second.empty()) {
      fh2path_.erase(it);
    }
    path2fh_.erase(path);
  } else {
    LOG(INFO) << "File " << name << " does not exist in " << path;
  }
  return path;
}

}  // namespace base
}  // namespace secnfs

// vim:sw=2:ts=2:tw=80:expandtab:cinoptions=>2,(0\:0:
