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

import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.gc.TestJournaledGarbageCollector;
import edu.stonybrook.kurma.server.BlockExecutor;
import edu.stonybrook.kurma.server.FileHandler;

// Read KvsFacacdeTest.java
public class GarbageBlockJournalTest extends TestBase {
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(JOURNALED_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  @Test
  public void testJournalRedo() throws Exception {
    FileHandler fh = createFileUnderRoot("JournaledBlockTestFileMock");

    final int BLOCKSIZE = 64 * 1024;
    ByteBuffer dataIn = genRandomBuffer(BLOCKSIZE);
    fh.write(0, dataIn);
    fh.flush();

    BlockExecutor blockExecutor = Mockito.spy(new BlockExecutor());
    vh.getGarbageCollector().setBlockExecutor(blockExecutor);
    Mockito.doReturn(false).when(blockExecutor).delete(Mockito.anyCollection(), Mockito.any());

    fh.delete();
    int records_cleaned =
        ((TestJournaledGarbageCollector) vh.getGarbageCollector()).getBlockCollector().setup(vh);
    assertTrue(records_cleaned == 1);
  }

}
