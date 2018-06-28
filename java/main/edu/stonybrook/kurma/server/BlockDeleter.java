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

import java.util.AbstractMap;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

import edu.stonybrook.kurma.cloud.KvsFacade;

public class BlockDeleter implements Callable<Entry<Boolean, FileBlock>> {
  private static Logger LOGGER = LoggerFactory.getLogger(BlockDeleter.class);

  private FileBlock block;
  private KvsFacade kvs;

  public BlockDeleter(FileBlock block, KvsFacade kvs) {
    this.block = block;
    this.kvs = kvs;
  }

  @Override
  public Entry<Boolean, FileBlock> call() throws Exception {
    LOGGER.debug("deleteing block {} of file {}", block.getOffset(), block.getFile().getName());
    if (block.getVersion() != 0) { // ignore holes
      String key = new String(BaseEncoding.base64Url().encode(block.getKey()));
      kvs.delete(key);
    }
    return new AbstractMap.SimpleEntry<Boolean, FileBlock>(true, block);
  }

}
