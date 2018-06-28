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

import java.util.Arrays;

import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.records.ZKOperationType;

/**
 * Tests involving single KurmaTransaction and
 * their operations
 *
 * @author mchen
 *
 */
public class KurmaTransactionTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaTransactionTest.class);

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  @Test
  public void testAtomicity() {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();

    txn.addOperation(ZKOperationType.CREATE, "/a", data, null, null);
    txn.addOperation(ZKOperationType.CREATE, "/b", data, null, null);
    try {
      // update should fail on submission
      txn.addOperation(ZKOperationType.UPDATE, "/c",data, null, null);
      txn.addOperation(ZKOperationType.COMMIT, null, null, null, null);
      manager.addTransaction(txn);
      manager.flush();
    } catch (Exception e) {
      LOGGER.info("Operation failed");
    }
    try {
      // should throw a no node exception
      data = client.getData().forPath("/a");
      // should not reach this point
      assert(false);
    } catch(Exception e) {
      LOGGER.warn("No node excpetion");
      assert(e instanceof NoNodeException);
    }
  }

  @Test
  public void testSingleUpdate() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();
    final String path = "/testSingleUpdate";
    txn.create(path, data);
    txn.update(path, "data".getBytes());
    manager.submit(txn);
    manager.flush();
    byte readData[] = client.getData().forPath(path);
    assert(Arrays.equals(readData, "data".getBytes()));
  }

  @Test
  public void testConsecutiveUpdates() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();
    final String zpath = "/testConsecutiveUpdates";
    txn.create(zpath, data);
    for (int i=0; i<10; i++) {
      data = ("test"+i).getBytes();
      txn.update(zpath, data);
    }
    manager.submit(txn);
    manager.flush();
    byte readData[] = client.getData().forPath(zpath);
    assert(Arrays.equals(data, readData));
  }

  @Test
  public void testDeletion() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();
    String path = "/deletion";
    txn.create(path, data);
    manager.submit(txn);
    txn = manager.getNewTransaction();
    txn.delete(path);
    manager.submit(txn);
    manager.flush();
    Stat st = client.checkExists().forPath(path);
    assert(st == null);
  }
}
