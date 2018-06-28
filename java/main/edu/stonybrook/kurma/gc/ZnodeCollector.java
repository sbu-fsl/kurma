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

import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZnodeCollector extends AbstractWorker<String> {
  private static final Logger LOGGER = LoggerFactory.getLogger(BlockCollector.class);
  private CuratorFramework client;

  public ZnodeCollector(CuratorFramework client) {
    this.client = client;
  }

  @Override
  public boolean clean(String zpath) {
    try {
      client.delete().deletingChildrenIfNeeded().forPath(zpath);
    } catch (Exception e) {
      LOGGER.error(String.format("could not delete %s", zpath), e);
      e.printStackTrace();
      return false;
    }
    return true;
  }
}
