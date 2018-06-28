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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.server.BlockExecutor;
import edu.stonybrook.kurma.server.FileBlock;

public class BlockCollector extends AbstractWorker<Collection<FileBlock>> {
  private static final Logger LOGGER = LoggerFactory.getLogger(BlockCollector.class);
  private KvsFacade kvs;
  private short gwId;
  private BlockExecutor blockExecutor;

  public void setBlockExecutor(BlockExecutor blockExecutor) {
    this.blockExecutor = blockExecutor;
  }

  // wait before collecting block, makes sure conflict is resolved first
  private static final int waitTime = 15000;

  public BlockCollector(KvsFacade kvs, short gwId) {
    this.kvs = kvs;
    this.gwId = gwId;
  }

  @Override
  public boolean clean(Collection<FileBlock> blocks) {
    /*
     * Wait before deletion as we want remote gateway to resolve conflict which uses these blocks to
     * be collected
     */
    try {
      Thread.sleep(waitTime);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    List<FileBlock> blocksToBeCollected = new ArrayList<FileBlock>();
    /* Collect only those blocks who were created by this gateway */
    for (FileBlock block : blocks) {
      if (block.getGateway() == gwId) {
        blocksToBeCollected.add(block);
      }
    }
    if (!blockExecutor.delete(blocksToBeCollected, kvs)) {
      LOGGER.error("GC of blocks faile");
    }
    return true;
  }

}
