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

import edu.stonybrook.kurma.server.BlockExecutor;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.FileHandler;

public class TestGarbageCollector implements GarbageCollector {

  private TestDirCollector dirCollector;
  private TestFileCollector fileCollector;
  private TestBlockCollector blockCollector;
  private TestZnodeCollector znodeCollector;

  public TestGarbageCollector(TestDirCollector dirCollector, TestFileCollector fileCollector,
      TestBlockCollector blockCollector, TestZnodeCollector znodeCollector) {
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

  public TestBlockCollector getBlockCollector() {
    return blockCollector;
  }

  public TestZnodeCollector getZnodeCollector() {
    return znodeCollector;
  }

  @Override
  public void setBlockExecutor(BlockExecutor blockExecutor) {
    // TODO Auto-generated method stub

  }
}
