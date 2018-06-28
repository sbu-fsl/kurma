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

/**
 * Garbage collector for all resources to be reclaimed including metadata in ZooKeeper and data
 * blocks in public clouds.
 *
 * @author mchen
 *
 */
public interface GarbageCollector {

  public void collectFile(FileHandler fh);

  public void collectDirectory(DirectoryHandler dh);

  public void collectZnode(String zpath);

  public void collectBlocks(Collection<FileBlock> fb);

  public void flush();

  public void setBlockExecutor(BlockExecutor blockExecutor);
}
