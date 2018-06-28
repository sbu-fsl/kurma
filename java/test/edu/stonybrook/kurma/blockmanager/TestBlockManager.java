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
package edu.stonybrook.kurma.blockmanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.io.BaseEncoding;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.FileHandler;


// TODO: add TestCases once BlockManager code is done
// add corner TestCases
public class TestBlockManager extends TestBase {
  static BlockManager bm;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  @After
  public void cleanupBlockManager() throws Exception {
    if (!bm.getJournal().getDirectory().exists()) {
      return;
    }
    bm.getJournal().close();
    bm.getJournal().getDirectory().delete();
    System.out.println("Cleanup " + bm.getJournal());
    // verify that hash map doesn't contain any key. everything is processed
    assertTrue(bm.getGatewayMap().isEmpty());
    assertTrue(bm.getDeletedBlockKeys().isEmpty());
  }

  @Test
  public void testBlockManagerDeleteFb() throws Exception {
    /* Use Configuration gatewayIDs for real scenario */
    List<Short> rmtGwids = new ArrayList<>();
    rmtGwids.add((short) 1);
    rmtGwids.add((short) 2);
    final int size = 64 * 1024;
    File jDir = new File(vh.getConfig().getJournalDirectory() + "/TestBlockManagerBasic/");
    if (jDir.exists()) {
      jDir.delete();
    }

    FileHandler fh = createFileUnderRoot("testBlockManager");
    FileBlock fb = new FileBlock(fh, 0, size, 1, config.getGatewayId());

    bm = new BlockManager(rmtGwids, vh, jDir);

    assertTrue(bm.notifyDeleteLocalGateway(fb));
    assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(0)));
    Thread.sleep(1500);
    assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));
    // TODO : verification with new GC api
  }

  @Test
  public void testReplayBlockManagerNfiles() throws Exception {
    /* Use Configuration gatewayIDs for real scenario */
    List<Short> rmtGwids = new ArrayList<>();
    File jDir = new File(vh.getConfig().getJournalDirectory() + "/TestReplayNfiles/");
    if (jDir.exists()) {
      jDir.delete();
    }

    final int size = 64 * 1024;

    rmtGwids.add((short) 1);
    rmtGwids.add((short) 2);
    System.out.println("BFOREBLOCKMNGR: " + rmtGwids);

    bm = new BlockManager(rmtGwids, vh, jDir);

    for (int i = 0; i < 100; i++) {
      FileHandler fh = createFileUnderRoot("testReplayBlockManager_" + i);
      FileBlock fb = new FileBlock(fh, 0, size, 1, config.getGatewayId());
      assertTrue(bm.notifyDeleteLocalGateway(fb));
      assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(0)));
      assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));
    }

    bm.getJournal().sync();

    bm = null;

    rmtGwids = new ArrayList<>();
    rmtGwids.add((short) 1);
    rmtGwids.add((short) 2);
    bm = new BlockManager(rmtGwids, vh, jDir);

    FileHandler fh = createFileUnderRoot("testReplayBlockManager_last");
    FileBlock fb = new FileBlock(fh, 0, size, 1, config.getGatewayId());

    assertTrue(bm.notifyDeleteLocalGateway(fb));
    // this will trigger sendblocks to GC
    assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(0)));
    Thread.sleep(1500);
    assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));

    // TODO : verification of real deletion with new GC api
  }

  @Test
  public void testReplayBlockManagerPartial() throws Exception {
    /* Use Configuration gatewayIDs for real scenario */
    List<Short> rmtGwids = new ArrayList<>();
    // add dummy remote gwids
    rmtGwids.add((short) 123);
    rmtGwids.add((short) 456);
    final int size = 64 * 1024;

    File jDir = new File(vh.getConfig().getJournalDirectory() + "/TestReplayPartial/");

    if (jDir.exists()) {
      jDir.delete();
    }

    bm = new BlockManager(rmtGwids, vh, jDir);
    FileHandler fh = createFileUnderRoot("testReplayBlockManagerPartial");
    FileBlock fb = new FileBlock(fh, 0, size, 1, config.getGatewayId());
    assertTrue(bm.notifyDeleteLocalGateway(fb));
    assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(0)));
    bm.getJournal().sync();

    bm = null;
    /*
     * mimic reboot as above before the last gateway sends the response
     * assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));
     */
    rmtGwids = new ArrayList<>();
    rmtGwids.add((short) 123);
    rmtGwids.add((short) 456);
    bm = new BlockManager(rmtGwids, vh, jDir);


    String key = new String(BaseEncoding.base64Url().encode(fb.getKey()));

    // HashMap must contain the key
    assertTrue(bm.getGatewayMap().keySet().size() == 1);
    assertTrue(bm.getGatewayMap().keySet().contains(key));
    // deletedBlockKeys must not contain the key at this time/
    assertFalse(bm.getDeletedBlockKeys().contains(key));
    // now send remaining gateways response
    // next update after delay will trigger send block to GC
    Thread.sleep(1200);
    assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));
    // TODO : verification of real deletion with new GC api
  }

  class notifyRunnable implements Runnable {
    BlockManager bm;
    short remoteGwid;
    FileBlock fb;

    public notifyRunnable(BlockManager bm, short gwid, FileBlock fb) {
      this.bm = bm;
      this.remoteGwid = gwid;
      this.fb = fb;
    }

    @Override
    public void run() {
      try {
        assertTrue(bm.notifyDeleteRemoteGateway(fb, remoteGwid));
      } catch (Exception e) {
        e.printStackTrace();
        return;
      }
    }
  }

  @Test
  public void testSynchronizedBlockManager() throws Exception {
    /* Use Configuration gatewayIDs for real scenario */
    List<Short> rmtGwids = new ArrayList<>();
    Thread[] t = new Thread[10];
    // add dummy remote gwids

    for (int i = 0; i < t.length; i++) {
      rmtGwids.add((short) (123 + i));
    }

    final int size = 64 * 1024;

    File jDir = new File(vh.getConfig().getJournalDirectory() + "/TestSynchronous/");

    if (jDir.exists()) {
      jDir.delete();
    }

    bm = new BlockManager(rmtGwids, vh, jDir);
    FileHandler fh = createFileUnderRoot("testSyncBlockManager");
    FileBlock fb = new FileBlock(fh, 0, size, 1, config.getGatewayId());

    assertTrue(bm.notifyDeleteLocalGateway(fb));

    for (int i = 0; i < t.length - 1; i++) {
      t[i] = new Thread(new notifyRunnable(bm, rmtGwids.get(i), fb));
      t[i].start();
    }

    for (int i = 0; i < t.length - 1; i++) {
      t[i].join();
    }

    bm.getJournal().sync();

    bm = null;
    /*
     * mimic reboot as above before the last gateway sends the response
     * assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));
     */
    rmtGwids = new ArrayList<>();
    for (int i = 0; i < t.length; i++) {
      rmtGwids.add((short) (123 + i));
    }

    bm = new BlockManager(rmtGwids, vh, jDir);
    t[t.length - 1] = new Thread(new notifyRunnable(bm, rmtGwids.get(t.length - 1), fb));

    String key = new String(BaseEncoding.base64Url().encode(fb.getKey()));

    // HashMap must contain the key
    assertTrue(bm.getGatewayMap().keySet().size() == 1);
    assertTrue(bm.getGatewayMap().keySet().contains(key));
    // deletedBlockKeys must not contain the key at this time/
    assertFalse(bm.getDeletedBlockKeys().contains(key));
    // now send remaining gateways response
    // next update after delay will trigger send block to GC
    Thread.sleep(1200);
    t[t.length - 1].start();
    t[t.length - 1].join();
    // TODO : verification of real deletion with new GC api
  }

  @Test
  public void testSynchronizedBlockManagerMultiFiles() throws Exception {
    /* Use Configuration gatewayIDs for real scenario */
    List<Short> rmtGwids = new ArrayList<>();
    Thread[] t = new Thread[100];
    FileBlock[] fb = new FileBlock[10];
    int nRemoteGateways = 10;

    for (int i = 0; i < nRemoteGateways; i++) {
      rmtGwids.add((short) (123 + i));
    }

    final int size = 64 * 1024;

    File jDir = new File(vh.getConfig().getJournalDirectory() + "/TestSyncNFiles/");

    if (jDir.exists()) {
      jDir.delete();
    }

    bm = new BlockManager(rmtGwids, vh, jDir);

    for (int i = 0; i < fb.length; i++) {
      FileHandler fh = createFileUnderRoot("testSyncBlockManager" + i);
      fb[i] = new FileBlock(fh, 0, size, 1, config.getGatewayId());
      assertTrue(bm.notifyDeleteLocalGateway(fb[i]));
    }

    // Initialize N-1 threads
    for (int i = 0; i < fb.length; i++) {
      // keep last update for after replay case
      for (int j = 0; j < nRemoteGateways - 1; j++) {
        t[fb.length * i + j] = new Thread(new notifyRunnable(bm, rmtGwids.get(j), fb[i]));
        t[fb.length * i + j].start();
      }
    }

    for (int i = 0; i < fb.length; i++) {
      for (int j = 0; j < nRemoteGateways - 1; j++) {
        t[fb.length * i + j].join();
      }
    }
    bm.getJournal().sync();

    bm = null;
    /*
     * mimic reboot as above before the last gateway sends the response
     * assertTrue(bm.notifyDeleteRemoteGateway(fb, rmtGwids.get(1)));
     */
    rmtGwids = new ArrayList<>();
    for (int i = 0; i < nRemoteGateways; i++) {
      rmtGwids.add((short) (123 + i));
    }

    bm = new BlockManager(rmtGwids, vh, jDir);

    for (int i = 0; i < fb.length; i++) {
      t[fb.length * i + (nRemoteGateways - 1)] =
          new Thread(new notifyRunnable(bm, rmtGwids.get(nRemoteGateways - 1), fb[i]));
    }


    // HashMap must contain the key
    System.out.println(bm.getGatewayMap().keySet().size());
    assertTrue(bm.getGatewayMap().keySet().size() == fb.length);
    for (int i = 0; i < fb.length; i++) {
      String key = new String(BaseEncoding.base64Url().encode(fb[i].getKey()));
      assertTrue(bm.getGatewayMap().keySet().contains(key));
      // deletedBlockKeys must not contain the key at this time/
      assertFalse(bm.getDeletedBlockKeys().contains(key));
    }


    for (int i = 0; i < fb.length - 1; i++) {
      t[fb.length * i + (nRemoteGateways - 1)].start();
    }

    for (int i = 0; i < fb.length - 1; i++) {
      t[fb.length * i + (nRemoteGateways - 1)].join();
    }

    // now send final gateways response after threshold time
    // next update after delay will trigger send block to GC
    // and cleanup remaining entries.
    Thread.sleep(1200);
    t[fb.length * (fb.length - 1) + (nRemoteGateways - 1)].start();
    t[fb.length * (fb.length - 1) + (nRemoteGateways - 1)].join();
    // TODO: verification of real deletion with new GC api
  }

}
