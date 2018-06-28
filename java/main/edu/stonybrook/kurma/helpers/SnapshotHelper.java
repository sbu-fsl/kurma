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
package edu.stonybrook.kurma.helpers;

import java.util.List;

import org.apache.zookeeper.data.Stat;

import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.meta.Snapshot;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.ZkClient;
import edu.stonybrook.kurma.util.ThriftUtils;

public class SnapshotHelper {
  public static List<String> listSnapshots(ZkClient client, String snapshotZdir)
      throws CuratorException {
    List<String> snapshotNames = null;
    try {
      client.flush();
      Stat zkstat = client.getCuratorClient().checkExists().forPath(snapshotZdir);
      if (zkstat == null) {
        return null; // No snapshots
      }
      snapshotNames = client.getCuratorClient().getChildren().forPath(snapshotZdir);
    } catch (Exception e) {
      throw new CuratorException(String.format("cannot list snapshots in %s", snapshotZdir), e);
    }
    return snapshotNames;
  }

  public static Snapshot readSnapshot(ZkClient client, String zpath)
      throws CuratorException {
    Snapshot snapshot = new Snapshot();
    byte[] data;
    try {
      data = client.read(zpath, null);
      ThriftUtils.decode(data, snapshot, true);
    } catch (Exception e) {
      throw new CuratorException(String.format("cannot read snapshot %s", zpath), e);
    }
    return snapshot;
  }

  public static void writeSnapshot(ZkClient client, String zpath, Snapshot snapshot)
      throws CuratorException {
    byte[] data = ThriftUtils.encode(snapshot, true);
    try {
      KurmaTransaction txn = client.newTransaction();
      txn.create(zpath, data);
      client.submitTransaction(txn);
    } catch (Exception e) {
      throw new CuratorException(String.format("cannot write snapshot %s", zpath), e);
    }
  }
}
