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
package edu.stonybrook.kurma.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.helpers.VolumeInfoHelper;
import edu.stonybrook.kurma.meta.Int128;
import edu.stonybrook.kurma.meta.ObjectID;

public class VolumeHandlerTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(VolumeHandler.class);

  @BeforeClass
  public static void setUp() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    closeTestServer();
  }

  @Test
  public void testGetId() throws Exception {
    int nAlloc = config.getIdAllocationUnit();
    Int128 lastId = null;
    for (int i = 0; i < 5 * nAlloc; ++i) {
      Int128 id = vh.getNextId();
      assertNotNull(id);
      if (lastId != null) {
        assertTrue(Int128Helper.compare(id, lastId) > 0);
      }
      lastId = id;
    }

    TWrapper<Int128> ids = new TWrapper<>(vh.getIdCursorZpath(), new Int128(), false, true);
    ids.read(vh.getZkClient());
    assertTrue(Int128Helper.compare(ids.get(), lastId) >= 0);
  }

  class IdConsumer implements java.util.function.Consumer<Integer> {
    public IdConsumer(VolumeHandler vh, int n) {
      this.vh_ = vh;
      this.n_ = n;
    }

    @Override
    public void accept(Integer t) {
      for (int i = 0; valid() && i < n_; ++i) {
        Int128 id = vh_.getNextId();
        if (id == null || !ids_.add(Int128Helper.toBigInteger(id))) {
          LOGGER.error("invalid id: {}", id);
          valid_.set(false);
        }
      }
    }

    public boolean valid() {
      return valid_.get();
    }

    private VolumeHandler vh_;
    private final int n_;
    // We need to use BigInteger instead of Int128 because Int128's hash()
    // is broken.
    private ConcurrentSkipListSet<BigInteger> ids_ = new ConcurrentSkipListSet<BigInteger>();
    private AtomicBoolean valid_ = new AtomicBoolean(true);
  }

  @Test
  public void testConcurrentGetId() throws Exception {
    Int128 initId = vh.getNextId();
    final int nthreads = 16;
    final int nalloc = config.getIdAllocationUnit() * 5;
    IdConsumer worker = new IdConsumer(vh, nalloc);
    doParallel(nthreads, worker);
    assertTrue(worker.valid());
    Int128 lastId = vh.getNextId();
    LOGGER.debug("initId: {}; lastId: {}", initId, lastId);
    assertEquals(lastId, Int128Helper.add(initId, nthreads * nalloc + 1));
  }

  @Test
  public void testRootDirectory() throws Exception {
    DirectoryHandler root = vh.getRootDirectory();
    assertTrue(root == vh.getDirectoryHandler(root.get().oid));
  }

  @Test
  public void testGetSubpaths() throws Exception {
    List<String> subpaths = vh.getSubpaths();
    List<String> zpaths = client.getChildren().forPath(vh.getZpath());
    zpaths =
        zpaths.stream().map(p -> ZKPaths.makePath(vh.getZpath(), p)).collect(Collectors.toList());
    Collections.sort(subpaths);
    Collections.sort(zpaths);
    assertTrue(subpaths.equals(zpaths));
  }

  @Test
  public void testLoad() throws Exception {
    vh.load();
    VolumeHandler vh2 = new VolumeHandler(VolumeInfoHelper.newVolumeInfo(volumeId),
        kh.getVolumeZpath(volumeId), client, vh.getGarbageCollector(), config, new BlockExecutor());
    LOGGER.info("volume zpath {}", kh.getVolumeZpath(volumeId));
    vh2.load();
    Stat stat1 = vh.getWrapper().getZkStat();
    Stat stat2 = vh2.getWrapper().getZkStat();
    assertEquals(stat1.getVersion(), stat2.getVersion());
    assertEquals(stat1.getAversion(), stat2.getAversion());
    assertEquals(stat1.getCzxid(), stat2.getCzxid());
    assertEquals(stat1.getMzxid(), stat2.getMzxid());
  }

  @Test
  public void testGetObjectZpath() throws Exception {
    ObjectID oid =
        ObjectIdHelper.newFileOid(Int128Helper.newId(0x123456, 0x789), config.getGatewayId());
    System.out.println(vh.getObjectZpath(oid));
    String expectedZpath = String.format("/%s/ny/0000000000000789/000000000012/3456",
        TestBase.volumeId);
    assertEquals(expectedZpath, vh.getObjectZpath(oid));
  }

  @Test
  public void testGetDirectoryHandler() throws Exception {
    DirectoryHandler rootDh = vh.getRootDirectory();
    List<ObjectID> newOids = new ArrayList<ObjectID>();
    for (int i = 0; i < config.getDirectoryCacheSize(); ++i) {
      ObjectID oid = vh.newDirectoryOid();
      DirectoryHandler dh = new DirectoryHandler(oid, vh, true);
      dh.update().setOid(oid);
      dh.update().setParent_oid(rootDh.getOid());
      dh.update().setAttrs(AttributesHelper.newDirAttributes());
      rootDh.addChild(String.format("%08d", i), oid);
      vh.addDirectoryHandler(dh);
      newOids.add(oid);
    }
    DirectoryHandler dh1 = vh.getDirectoryHandler(newOids.get(0));
    assertNotNull(dh1);
    assertNotNull(dh1.getWrapper().getZkStat());
    DirectoryHandler dh2 = vh.getDirectoryHandler(newOids.get(99));
    assertNotNull(dh2.getWrapper().getZkStat());
    assertNull(vh.getDirectoryHandler(vh.newDirectoryOid()));
  }
}
