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

import edu.stonybrook.kurma.gc.GarbageCollector;
import edu.stonybrook.kurma.gc.JournaledBlockCollector;
import edu.stonybrook.kurma.server.BlockExecutor;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.FileHandler;

public class TestJournaledGarbageCollector implements GarbageCollector {

  private TestDirCollector dirCollector;
  private TestFileCollector fileCollector;
  private JournaledBlockCollector blockCollector;
  private TestZnodeCollector znodeCollector;

  public TestJournaledGarbageCollector(TestDirCollector dirCollector,
      TestFileCollector fileCollector, JournaledBlockCollector blockCollector,
      TestZnodeCollector znodeCollector) {
    this.dirCollector = dirCollector;
    this.fileCollector = fileCollector;
    this.blockCollector = blockCollector;
    this.znodeCollector = znodeCollector;
  }

  @Override
  public void collectFile(FileHandler fh) {
    fileCollector.clean(fh);
  }

  @Override
  public void collectDirectory(DirectoryHandler dh) {
    dirCollector.clean(dh);
  }

  @Override
  public void collectZnode(String zpath) {
    znodeCollector.clean(zpath);
  }

  @Override
  public void collectBlocks(Collection<FileBlock> blocks) {
    blockCollector.clean(blocks);
  }

  @Override
  public void flush() {
    // do nothing
  }

  public TestDirCollector getDirCollector() {
    return dirCollector;
  }

  public TestFileCollector getFileCollector() {
    return fileCollector;
  }

  public JournaledBlockCollector getBlockCollector() {
    return blockCollector;
  }

  public TestZnodeCollector getZnodeCollector() {
    return znodeCollector;
  }

  @Override
  public void setBlockExecutor(BlockExecutor blockExecutor) {
    blockCollector.setBlockExecutor(blockExecutor);

  }

}
