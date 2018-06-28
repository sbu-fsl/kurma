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

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import edu.stonybrook.kurma.server.FileBlock;

public class TestBlockCollector extends AbstractWorker<Collection<FileBlock>> {

  private Set<FileBlock> collectedBlocks = new HashSet<>();

  @Override
  public boolean clean(Collection<FileBlock> t) {
    for (FileBlock fb : t) {
      collectedBlocks.add(fb);
    }
    return true;
  }

  public boolean hasCollected(FileBlock fb) {
    return collectedBlocks.contains(fb);
  }

  public Set<FileBlock> getCollectedBlocks() {
    return collectedBlocks;
  }

  public void reset() {
    collectedBlocks.clear();
  }
}
