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
import java.util.concurrent.CountDownLatch;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.records.ZKOperationType;
import edu.stonybrook.kurma.server.FileHandler;

/**
 * Tests involving multiple transactions and how they
 * interact with each other
 *
 * @author rushabh
 *
 */

public class KurmaTransactionManagerTest extends TestBase{

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
  public void testWriteOrderingFromMultipleTransactions() throws Exception {
    final String zpath = "/testWriteOrderingFromMultipleTransactions";
    byte data[] = "1".getBytes();
    KurmaTransactionManager manager = vh.getTransactionManager();

    KurmaTransaction txn = manager.getNewTransaction();
    txn.addOperation(ZKOperationType.CREATE, zpath, data, null, null);
    txn.addOperation(ZKOperationType.COMMIT, null, null, null, null);
    manager.addTransaction(txn);

    for (int i = 0; i < 100; i++) {
      txn = manager.getNewTransaction();
      data = ("" + i).getBytes();
      txn.addOperation(ZKOperationType.UPDATE, zpath, data, null, null);
      txn.addOperation(ZKOperationType.COMMIT, null, null, null, null);
      manager.addTransaction(txn);
    }
    manager.flush();
    byte readData[] = client.getData().forPath(zpath);
    assert (Arrays.equals(data, readData));
  }

  @Test
  public void testDependentCreations() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "data".getBytes();
    String path = "";

    for (int i = 0; i < 100; i++) {
      path += ("/" + i);
      txn = manager.getNewTransaction();
      data = (path).getBytes();
      txn.addOperation(ZKOperationType.CREATE, path, data, null, null);
      txn.addOperation(ZKOperationType.COMMIT, null, null, null, null);
      manager.addTransaction(txn);
    }
    manager.flush();
    path = "";
    for (int i = 0; i < 100; i++) {
      path += ("/" + i);
      byte readData[] = client.getData().forPath(path);
      assert (Arrays.equals(path.getBytes(), readData));
    }
  }

  @Test
  public void testReadAfterUpdateWithDependency() throws Exception {
    KurmaTransactionManager manager = new KurmaTransactionManager(vh.getZkClient().getCuratorClient(),
        vh.getMetaJournal(), 1000000, 200);
    KurmaTransaction txn = manager.getNewTransaction();
    int i=-1;
    for (i=0; i<200; i++) {
      txn.create("/testReadAfterUpdateWithDependency-"+i, null);
    }
    manager.submit(txn);
    txn = manager.getNewTransaction();
    txn.update("/testReadAfterUpdateWithDependency-"+(i-1), "data".getBytes());
    manager.submit(txn);
    manager.flush();
    byte readData[] = client.getData().forPath("/testReadAfterUpdateWithDependency-"+(i-1));
    assert(Arrays.equals("data".getBytes(), readData));
  }

  @Test
  public void testLargeCommitThenSmall() throws Exception {
    FileHandler fh = null;
    for (int i = 0; i < 200; ++i) {
      fh = createFileUnderRoot("testLargeCommitThenSmall-" + i);
    }
    vh.getTransactionManager().flush();
    fh.delete();
    Thread.sleep(20000);
    vh.getTransactionManager().flush();
  }


  @Test
  public void testParallelTransactionWithDependancy() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    String zpath = "/testParallelTransactionWithDependancy";
    CountDownLatch counter = new CountDownLatch(1);
    Thread thread1 = new Thread(new Runnable() {
      @Override
      public void run() {
       KurmaTransaction txn = manager.getNewTransaction();
       txn.create(zpath, null);
       manager.submit(txn);
       counter.countDown();
      }
    });
    Thread thread2 = new Thread(new Runnable() {
      @Override
      public void run() {
        KurmaTransaction txn = manager.getNewTransaction();
        txn.update(zpath, "test".getBytes());
        try {
          counter.await();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        manager.submit(txn);
      }
    });
    thread1.start();
    thread2.start();
    thread1.join();
    thread2.join();
    manager.flush();
    byte readData[] = client.getData().forPath(zpath);
    assert(Arrays.equals("test".getBytes(), readData));
  }

}
