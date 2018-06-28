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

import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.meta.TestJournalObj;

public class TWrapperTransactionTest extends TestBase {

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
  public void testReadWrite() throws Exception {
    KurmaTransaction txn = zkClient.newTransaction();

    TestJournalObj dummyObject = new TestJournalObj(1);
    TWrapper<TestJournalObj> wrapper = new TWrapper<TestJournalObj>("/zkClient", dummyObject);

    wrapper.create(txn);
    Stat zkStat = new Stat();
    dummyObject.setNumber(2);
    wrapper.write(txn);
    zkClient.submitTransaction(txn);
    byte newData[] = zkClient.read(wrapper.getZpath(), zkStat);
    assert(Arrays.equals(newData, wrapper.getData()));
    zkClient.flush();

    byte readData[] = client.getData().forPath(wrapper.getZpath());
    assert(Arrays.equals(newData, readData));
  }

  @Test
  public void testReadAfterDelete() throws Exception {
    KurmaTransaction txn = zkClient.newTransaction();

    TestJournalObj dummyObject = new TestJournalObj(1);
    TWrapper<TestJournalObj> wrapper = new TWrapper<TestJournalObj>("/test2", dummyObject);

    wrapper.create(txn);
    zkClient.submitTransaction(txn);

    txn = zkClient.newTransaction();
    wrapper.delete(txn);
    zkClient.submitTransaction(txn);
    zkClient.flush();

    Stat st = client.checkExists().forPath("/test2");
    assert(st == null);
  }

  @Test
  public void testCreateAndUpdateAfterDelete() throws Exception {
    TestJournalObj dummyObject = new TestJournalObj(1);
    TWrapper<TestJournalObj> wrapper = new TWrapper<TestJournalObj>("/test2", dummyObject);
    client.create().forPath("/test2", wrapper.getData());

    KurmaTransaction txn = zkClient.newTransaction();
    wrapper.delete(txn);
    zkClient.submitTransaction(txn);
    zkClient.flush();

    dummyObject.setNumber(5);
    wrapper = new TWrapper<TestJournalObj>("/test2", dummyObject);
    txn = zkClient.newTransaction();

    wrapper.create(txn);
    dummyObject.setNumber(8);
    wrapper.write(txn);
    zkClient.submitTransaction(txn);
    zkClient.flush();

    byte readData[] = client.getData().forPath(wrapper.getZpath());
    assert(Arrays.equals(wrapper.getData(), readData));

  }
}
