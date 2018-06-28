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
package edu.stonybrook.kurma.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.util.AuthenticatedEncryption;

public class BlockExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(BlockExecutor.class);

  public static final int THREAD_POOL_SIZE = 16;
  public static final int TIME_OUT_SECONDS = 300;

  private ExecutorService executor;

  public enum ActionType {
    READ, WRITE, DELETE
  };

  public BlockExecutor() {
    executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
  }

  private Callable<Entry<Boolean, FileBlock>> createAction(FileBlock block,
      AuthenticatedEncryption ae, KvsFacade kvs, ActionType at) {
    Callable<Entry<Boolean, FileBlock>> ac = null;
    switch (at) {
      case READ:
        ac = new BlockReader(block, ae, kvs);
        break;
      case WRITE:
        ac = new BlockWriter(block, ae, kvs);
        break;
      case DELETE:
        ac = new BlockDeleter(block, kvs);
        break;
    }
    return ac;
  }

  public boolean execute(List<FileBlock> blocks, AuthenticatedEncryption ae, KvsFacade kvs,
      ActionType at, int timeout) {
    boolean isSuccess = true;
    List<Future<Entry<Boolean, FileBlock>>> results =
        new ArrayList<Future<Entry<Boolean, FileBlock>>>(blocks.size());
    for (FileBlock block : blocks) {
      results.add(executor.submit(createAction(block, ae, kvs, at)));
    }

    try {
      for (int i = 0; i < results.size(); ++i) {
        Entry<Boolean, FileBlock> res = results.get(i).get(timeout, TimeUnit.SECONDS);
        if (res == null) {
          LOGGER.error("timed out");
          return false;
        }
        /*
         * Mark operation succeeded for block so that it can be garbage collected in case of partial
         * success
         */
        if (res.getKey()) {
          res.getValue().setOperationResult(true);
        } else {
          LOGGER.info("failed to read file block {}", res.getValue());
          isSuccess = false;
        }
      }
    } catch (Exception e) {
      LOGGER.error("BlockExecutor failed", e);
      e.printStackTrace();
      return false;
    } finally {
      for (Future<Entry<Boolean, FileBlock>> future : results) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
      results.clear();
    }

    return isSuccess;
  }

  public boolean read(List<FileBlock> blocks, AuthenticatedEncryption ae, KvsFacade kvs) {
    return execute(blocks, ae, kvs, ActionType.READ, TIME_OUT_SECONDS);
  }

  public boolean write(List<FileBlock> blocks, AuthenticatedEncryption ae, KvsFacade kvs) {
    return execute(blocks, ae, kvs, ActionType.WRITE, TIME_OUT_SECONDS);
  }

  public boolean delete(Collection<FileBlock> blocks, KvsFacade kvs) {
    int retryCount = 3;
    boolean res = false;
    List<FileBlock> blocksToBeDeleted = new ArrayList<FileBlock>(blocks);
    Iterator<FileBlock> iterator = blocksToBeDeleted.iterator();
    while (retryCount > 0) {
      res = execute(blocksToBeDeleted, null, kvs, ActionType.DELETE, TIME_OUT_SECONDS);
      if (res) {
        break;
      } else {
        for (; iterator.hasNext();) {
          FileBlock block = iterator.next();
          if (block.getOperationResult())
            iterator.remove();
        }
      }
      retryCount--;
    }
    return res;
  }
}
