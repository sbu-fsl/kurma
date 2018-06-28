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
package edu.stonybrook.kurma.transaction;

import java.util.HashMap;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.util.LoggingUtils;

/**
 * A Cache of all changes to znodes that have made but not written back to
 * ZooKeeper.  It is thread-safe.
 *
 *
 * @author mchen
 *
 */
public class InTransactionZnodes {
  private static Logger LOGGER = LoggerFactory.getLogger(InTransactionZnodes.class);
  private HashMap<String, byte[]> znodes = new HashMap<>();
  private HashSet<String> deleted = new HashSet<>();

  public synchronized byte[] put(String zpath, byte[] data) {
    LOGGER.trace("put {}: {}", zpath, LoggingUtils.hash(data));
    deleted.remove(zpath);
    return znodes.put(zpath, data);
  }

  public synchronized byte[] get(String zpath) {
    byte[] data = null;
    if (!deleted.contains(zpath)) {
      data = znodes.getOrDefault(zpath, null);
    }
    LOGGER.trace("get {}: {}", zpath, LoggingUtils.hash(data));
    return data;
  }

  public synchronized byte[] delete(String zpath) {
    byte[] oldData = znodes.remove(zpath);
    deleted.add(zpath);
    LOGGER.trace("deleted {}", zpath);
    return oldData;
  }

  public synchronized void clear() {
    znodes.clear();
    deleted.clear();
  }
}
