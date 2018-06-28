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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;

/**
 * These tests verify the working of merge 
 * update operations within a TransactionCommter 
 * in various scenarios
 * 
 * Note: An update on a non existing node will 
 * not be reported, if followed by a successful 
 * update on the same znode
 * 
 * @author rushabh
 *
 */
public class TransactionCommitterTest extends TestBase {

  @BeforeClass
  public static void setUp() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    closeTestServer();
  }

  @Test
  public void testCommitATransactionWithoutDependancy() throws Exception {
    KurmaTransactionManager manager = new KurmaTransactionManager(vh.getZkClient().getCuratorClient(),
        vh.getMetaJournal(), 1, 1);
    KurmaTransaction txn = manager.getNewTransaction();
    txn.create("/testCommitATransactionWithoutDependancy", "test".getBytes());
    manager.submit(txn);
    manager.flush();
  }

  @Test
  public void testUpdatesAccrossTransactions() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    final String zpath = "/testUpdateAcrossTransactions";
    txn.create(zpath, null);
    manager.submit(txn);
    manager.flush();
    byte data[] = null;
    for (int i=0; i<500; i++) {
      data = ("testUpdate-"+i).getBytes();
      txn = manager.getNewTransaction();
      txn.update(zpath, data);
      manager.submit(txn);
    }
    manager.flush();
    byte readData[] = client.getData().forPath(zpath);
    assert(Arrays.equals(data, readData));
  }

  @Test
  public void testUpdateWithOtherOperations() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    final String zpath = "/testUpdateWithOtherOperations";
    txn.create(zpath+"0", null);
    int n = 10;
    manager.submit(txn);
    byte data[] = "data".getBytes();
    for (int i=1; i<10; i++) {
      txn = manager.getNewTransaction();
      txn.create(zpath+i, null);
      txn.update(zpath+(i-1), data);
      if (i>=2) {
        txn.delete(zpath+(i-2));
      }
      manager.submit(txn);
    }
    manager.flush();
    for (int i=0; i<n; i++) {
      if (i<=n-3)
         assert(client.checkExists().forPath(zpath+i) == null);
      else if (i==n-2){
        byte readData[] = client.getData().forPath(zpath+i);
        assert(Arrays.equals(readData, data));
      }
      else {
        assert(client.checkExists().forPath(zpath+i) != null);
      }
    }
  }

  @Test
  public void testFailedUpdateFollowedBySuccessfull() throws Exception {
    KurmaTransactionManager manager = new KurmaTransactionManager(client, vh.getMetaJournal(), 10000, 10000);
    KurmaTransaction txn = manager.getNewTransaction();
    final String zpath = "/testFailedUpdateFollowedBySuccessfull";
    byte data[] = "test".getBytes();
    txn.update(zpath, null);
    manager.submit(txn);
    txn = manager.getNewTransaction();
    txn.create(zpath, null);
    data = "data".getBytes();
    txn.update(zpath, data);
    manager.submit(txn);
    manager.flush();

    byte readData[] = client.getData().forPath(zpath);
    assert(Arrays.equals(readData, data));
  }
  
  @Test
  public void testUpdateWithDependentOperations() throws Exception {
    KurmaTransactionManager manager = new KurmaTransactionManager(client, vh.getMetaJournal(), 10000, 10000);
    KurmaTransaction txn = manager.getNewTransaction();
    final String zpath = "/testUpdateOperationWithDependancies";
    txn.create(zpath, null);
    txn.update(zpath, "data".getBytes());
    manager.submit(txn);
    
    txn = manager.getNewTransaction();
    txn.create(zpath+"/child", null);
    manager.submit(txn);
    
    txn = manager.getNewTransaction();
    txn.update(zpath+"/child", "data".getBytes());
    manager.submit(txn);    
    manager.flush();
    
    byte readData[] = client.getData().forPath(zpath);
    assert(Arrays.equals(readData, "data".getBytes()));
    
    readData = client.getData().forPath(zpath+"/child");
    assert(Arrays.equals(readData, "data".getBytes()));    
  }
  
  @Test
  public void testUpdateAfterSecondCreation() throws Exception {
    KurmaTransactionManager manager = new KurmaTransactionManager(client, vh.getMetaJournal(), 10000, 10000);
    final String zpath = "/testUpdateAfterSecondCreation";
    
    KurmaTransaction txn = manager.getNewTransaction();
    txn.create(zpath, "abc".getBytes());
    manager.submit(txn);
    
    txn = manager.getNewTransaction();
    txn.update(zpath, "data".getBytes());
    manager.submit(txn);
    
    txn = manager.getNewTransaction();
    txn.delete(zpath);
    manager.submit(txn);
    
    txn = manager.getNewTransaction();
    txn.create(zpath, "newabc.txt".getBytes());
    manager.submit(txn);
    
    txn = manager.getNewTransaction();
    txn.update(zpath, "newdata".getBytes());
    manager.submit(txn);
    
    manager.flush();
    byte readData[] = client.getData().forPath(zpath);
    assert(Arrays.equals(readData, "newdata".getBytes()));
    
    
  }
}
