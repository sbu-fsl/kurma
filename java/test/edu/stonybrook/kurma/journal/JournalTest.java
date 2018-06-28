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

import journal.io.api.Journal;
import journal.io.api.Journal.ReadType;
import journal.io.api.Journal.WriteType;
import journal.io.api.JournalBuilder;
import journal.io.api.Location;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.meta.TestJournalObj;
import edu.stonybrook.kurma.util.ThriftUtils;

public class JournalTest extends TestBase {
  static Journal journal;
  int iterations = 10;
  int deletions = 5;
  public static java.io.File journal_directory;
  private static final Logger LOGGER = LoggerFactory.getLogger(JournalTest.class);
  @Before
  public void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
    journal_directory =
        new java.io.File(vh.getConfig().getJournalDirectory() + "/FileStoreTestJournal/");
    if (!journal_directory.exists()) {
      journal_directory.mkdirs();
    }
    FileUtils.cleanDirectory(journal_directory);
    journal = JournalBuilder.of(journal_directory).open();
    journal.setMaxFileLength(8192);
  }

  @After
  public void tearDownAfterClass() throws Exception {
    journal.close();
    closeTestServer();
  }

  @Test
  public void testDeletedRecordIsNotReplayed() throws Exception {
    List<Location> locations = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      TestJournalObj tbj = new TestJournalObj(i);
      Location location = journal.write(ThriftUtils.encodeBinary(tbj), WriteType.ASYNC);
      locations.add(location);
    }
    int index = 6;
    journal.delete(locations.get(index));
    for (Location location : journal.redo()) {
      if (location.isDeletedRecord())
        continue;
      TestJournalObj tbj = new TestJournalObj();
      ThriftUtils.decodeBinary(journal.read(location, ReadType.ASYNC), tbj);
      assert (tbj.getNumber() != index);
    }
  }

  @Test
  public void testAsyncWriteOrdering() throws Exception {
    Object obj = new Object();
    Thread t1 = new Thread(new PrintNumbers(obj, journal));
    Thread t2 = new Thread(new PrintNumbers(obj, journal));
    t1.setName("Even");
    t2.setName("Odd");
    t1.start();
    t2.start();
    t1.join();
    t2.join();
    journal.sync();
    int count = 1;
    for (Location location : journal.redo()) {
      if (location.isDeletedRecord())
        continue;
      TestJournalObj tbj = new TestJournalObj();
      ThriftUtils.decodeBinary(journal.read(location, ReadType.ASYNC), tbj);
      LOGGER.info("{}", tbj.getNumber());
      assert (tbj.getNumber() == count);
      count++;
      journal.delete(location);
    }
    journal.sync();
  }

  @Test
  public void testCompaction() throws Exception {
    JournalCleaner jc = new JournalCleaner(journal, 10);
    jc.startCleaner();
    for (int i = 0; i < 20; i++) {
      PrintNumbers.i = 1;
      testAsyncWriteOrdering();
      Thread.sleep(1000);
    }
    jc.stopCleaner();
  }
}


class PrintNumbers implements Runnable {
  volatile static int i = 1;
  Object lock;
  Journal journal;
  private static final Logger LOGGER = LoggerFactory.getLogger(PrintNumbers.class);
  PrintNumbers(Object lock, Journal journal) {
    this.lock = lock;
    this.journal = journal;
  }

  @Override
  public void run() {
    while (i <= 10) {
      if (i % 2 == 0 && Thread.currentThread().getName().equals("Even")) {
        synchronized (lock) {
          TestJournalObj tobj = new TestJournalObj(i);
          try {
            journal.write(ThriftUtils.encodeBinary(tobj), WriteType.ASYNC);
          } catch (Exception e) {
            e.printStackTrace();
          }
          LOGGER.info("{}", Thread.currentThread().getName() + " - " + i);
          i++;
          try {
            if (i < 10)
              lock.wait();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }
      if ((i & 1) == 1 && Thread.currentThread().getName().equals("Odd")) {
        synchronized (lock) {
          TestJournalObj tobj = new TestJournalObj(i);
          try {
            journal.write(ThriftUtils.encodeBinary(tobj), WriteType.ASYNC);
          } catch (Exception e) {
            e.printStackTrace();
          }
          LOGGER.info("{}", Thread.currentThread().getName() + " - " + i);
          i++;
          lock.notify();
        }
      }

    }
  }
}
