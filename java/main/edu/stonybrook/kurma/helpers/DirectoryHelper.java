/*
 * Copyright (C) 2013-2018 Ming Chen
 * Copyright (C) 2016-2016 Praveen Kumar Morampudi
 * Copyright (C) 2016-2016 Harshkumar Patel
 * Copyright (C) 2017-2017 Rushabh Shah
 * Copyright (C) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
package edu.stonybrook.kurma.helpers;

import java.util.ArrayList;
import java.util.Optional;

import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.ObjectID;

public class DirectoryHelper {
  // Separate file/dir name from original name and its creator gwid
  public static final String SUFFIX_STRING_SEPARATOR = "___Kurma___";

  public static Directory newDirectory(ObjectID oid, Optional<ObjectID> parent_oid) {
    Directory dir = new Directory();
    dir.setOid(oid);
    if (parent_oid.isPresent()) {
      dir.setParent_oid(parent_oid.get());
    } else {
      dir.unsetParent_oid();
    }
    dir.setEntries(new ArrayList<DirEntry>());
    return dir;
  }

  public static DirEntry newDirEntry(ObjectID oid, String name) {
    DirEntry entry = new DirEntry();
    entry.name = name;
    entry.oid = oid;
    entry.timestamp = System.currentTimeMillis();
    return entry;
  }

  public static String getSuffixedName(String name, short gwid) {
    return name + SUFFIX_STRING_SEPARATOR + GatewayHelper.nameOf(gwid);
  }

  public static String getNameFromSuffixedName(String suffixName) {
    return suffixName.split(SUFFIX_STRING_SEPARATOR)[0];
  }

  public static boolean isSuffixedName(String name) {
    String str[] = name.split(SUFFIX_STRING_SEPARATOR);
    if (str.length == 2) {
      return true;
    }
    return false;
  }
}
