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

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.data.Stat;

import edu.stonybrook.kurma.records.ZKOperationType;
import edu.stonybrook.kurma.util.LRUCache;
import edu.stonybrook.kurma.util.ZkUtils;

public class ZkClient {
  private CuratorFramework client;
  private InTransactionZnodes cache;
  private KurmaTransactionManager manager;
  private LRUCache<String, Boolean> existingZpaths = new LRUCache<>(16);

  public ZkClient(CuratorFramework client, KurmaTransactionManager manager,
      InTransactionZnodes cache) {
    this.client = client;
    this.manager = manager;
    this.cache = cache;
  }

  public byte[] read(String zpath, Stat zkStat) throws Exception {
    byte[] data = cache.get(zpath);
    if (data == null) {
      if (zkStat == null) {
        data = client.getData().forPath(zpath);
      } else {
        data = client.getData().storingStatIn(zkStat).forPath(zpath);
      }
    }
    return data;
  }

  public void createRecursive(String zpath, byte[] data) throws Exception {
    client.create().creatingParentsIfNeeded().forPath(zpath, data);
  }

  public void deleteRecursive(String zpath) throws Exception {
    client.delete().deletingChildrenIfNeeded().forPath(zpath);
  }

  public boolean checkExists(String zpath) throws Exception {
    if (cache.get(zpath) != null) {
      return true;
    }
    return client.checkExists().forPath(zpath) != null;
  }

  public void ensurePath(String zpath, boolean excludeLast) throws Exception {
    if (excludeLast) {
      zpath = ZKPaths.getPathAndNode(zpath).getPath();
    }

    synchronized(existingZpaths) {
      if (existingZpaths.containsKey(zpath)) {
        return;
      }
    }

    if (cache.get(zpath) != null) {
      return;
    }

    ZkUtils.ensurePath(client, zpath, false);

    synchronized(existingZpaths) {
      if (!existingZpaths.containsKey(zpath)) {
        existingZpaths.put(zpath, true);
      }
    }
  }

  public CuratorFramework getCuratorClient() {
    return client;
  }

  public InTransactionZnodes getCache() {
    return cache;
  }

  public KurmaTransaction newTransaction() {
    return manager.getNewTransaction();
  }

  public boolean submitTransaction(KurmaTransaction txn) {
    boolean res = manager.submit(txn);
    if (res) {
      for (OperationInfo op : txn.getOperations()) {
        if (ZKOperationType.CREATE.equals(op.getType())) {
          cache.put(op.getPath(), op.getData());
        } else if (ZKOperationType.UPDATE.equals(op.getType())) {
          cache.put(op.getPath(), op.getData());
        } else if (ZKOperationType.REMOVE.equals(op.getType())) {
          cache.delete(op.getPath());
        }
      }
    }
    return res;
  }

  public boolean flush() {
    manager.flush();
    cache.clear();
    return true;
  }
}
