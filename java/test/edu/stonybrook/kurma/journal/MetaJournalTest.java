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
package edu.stonybrook.kurma.journal;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import journal.io.api.Location;

import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.records.MetaUpdateJournalRecord;
import edu.stonybrook.kurma.records.ZKOperationType;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.KurmaTransactionManager;
@FixMethodOrder()
public class MetaJournalTest extends TestBase {

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  private void redoJournalRecords() {
    MetaJournal journal = vh.getMetaJournal();
    KurmaTransactionManager manager = new KurmaTransactionManager(vh.getZkClient().getCuratorClient(),
                                journal, 1, 20);
    vh.setTransactionManager(manager);
    journal.redoOldRecords(vh);
    manager.flush();
  }

  @Test
  public void testRedoCreate() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();
    for (int i=0; i<10; i++) {
      txn.create("/testRedoCreate"+i, data);
    }
    vh.getMetaJournal().record(txn.getTransactionID(), ZKOperationType.COMMIT, null, null);
    manager.flush();
    redoJournalRecords();
    for (int i=0; i<10; i++) {
      byte readData[] = client.getData().forPath("/testRedoCreate"+i);
      assert(Arrays.equals(readData, data));
    }
  }

  @Test
  public void testRedoDelete() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();
    for (int i=0; i<10; i++) {
      txn.create("/testRedoDelete"+i, data);
    }
    vh.getZkClient().submitTransaction(txn);
    manager.flush();
    txn = manager.getNewTransaction();
    for (int i=0; i<10; i++) {
      txn.delete("/testRedoDelete"+i);
    }
    vh.getMetaJournal().record(txn.getTransactionID(), ZKOperationType.COMMIT, null, null);
    manager.flush();
    redoJournalRecords();
    for (int i=0; i<10; i++) {
      Stat st = client.checkExists().forPath("/testRedoDelete"+i);
      assert(st == null);
    }
  }

  @Test
  public void testRedoUpdate() throws Exception {
    KurmaTransactionManager manager = vh.getTransactionManager();
    KurmaTransaction txn = manager.getNewTransaction();
    byte data[] = "test".getBytes();
    for (int i=0; i<10; i++) {
      txn.create("/testRedoUpdate"+i, data);
    }
    vh.getZkClient().submitTransaction(txn);
    manager.flush();
    txn = manager.getNewTransaction();
    data = "update".getBytes();
    for (int i=0; i<10; i++) {
      txn.update("/testRedoUpdate"+i, data);
    }
    vh.getMetaJournal().record(txn.getTransactionID(), ZKOperationType.COMMIT, null, null);
    manager.flush();
    redoJournalRecords();
    for (int i=0; i<10; i++) {
      byte readData[] = client.getData().forPath("/testRedoUpdate"+i);
      assert(Arrays.equals(readData, data));
    }
  }

  @Test
  public void testRecordWrittenCorrectly() throws Exception {
    File journal_dir = new File(vh.getConfig().getJournalDirectory()+"/testRecordWritten");
    if (!journal_dir.exists()) {
      journal_dir.mkdirs();
    }
    MetaJournal journal = new MetaJournal(journal_dir, 100);

    int trID = 0;
    byte data[] = "test".getBytes();
    String zpath = "/testRecordWrittenCorrectly";
    MetaUpdateJournalRecord record = new MetaUpdateJournalRecord(trID, ZKOperationType.CREATE);
    record.setData(data);
    record.setZpath(zpath);
    Location location = journal.record(trID, ZKOperationType.CREATE, zpath, data);

    MetaUpdateJournalRecord readRecord = journal.read(location);
    assert(readRecord.equals(record));
    journal.cleanJournal();
    journal.close();
  }

  @Test
  public void testReadAfterDelete() throws Exception {
    File journal_dir = new File(vh.getConfig().getJournalDirectory()+"/testRecordWritten");
    boolean failure = false;
    if (!journal_dir.exists()) {
      journal_dir.mkdirs();
    }
    MetaJournal journal = new MetaJournal(journal_dir, 100);
    int trID = 0;
    byte data[] = "test".getBytes();
    String zpath = "/testReadAfterDelete";
    MetaUpdateJournalRecord record = new MetaUpdateJournalRecord(trID, ZKOperationType.CREATE);
    record.setData(data);
    record.setZpath(zpath);
    Location location = journal.record(trID, ZKOperationType.CREATE, zpath, data);
    journal.delete(location);
    try {
      journal.read(location);
    }
    catch (IOException e) {
      failure = true;
    }
    assert(failure);
    journal.cleanJournal();
    journal.close();
  }
}
