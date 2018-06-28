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
package edu.stonybrook.kurma.gc;

import java.util.HashSet;
import java.util.Set;

import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.DirectoryHandler;

public class TestDirCollector extends AbstractWorker<DirectoryHandler> {
  private Set<ObjectID> collectedDirectories = new HashSet<>();

  @Override
  public boolean clean(DirectoryHandler dh) {
    collectedDirectories.add(dh.getOid());
    return true;
  }

  public boolean hasCollected(ObjectID oid) {
    return collectedDirectories.contains(oid);
  }
}
