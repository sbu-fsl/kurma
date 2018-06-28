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
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.helpers.VolumeInfoHelper;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.Int128;
import edu.stonybrook.kurma.meta.VolumeInfo;

public class KurmaHandlerTest extends TestBase {
  private static String volumeId = "TestVolume1";

  @BeforeClass
  public static void setUp() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    closeTestServer();
  }

  public KurmaHandlerTest() throws Exception {
    super();
  }

  @Test
  public void testCreateVolume() throws Exception {
    VolumeHandler vh = kh.createVolume(VolumeInfoHelper.newVolumeInfo(volumeId));
    String volumeZpath = kh.getVolumeZpath(volumeId);
    assertNotNull(client.checkExists().forPath(volumeZpath));

    TWrapper<VolumeInfo> vi = new TWrapper<>(volumeZpath, new VolumeInfo());
    vi.read(vh.getZkClient());
    assertEquals(volumeId, vi.get().id);

    TWrapper<Int128> ids = new TWrapper<>(vh.getIdCursorZpath(), new Int128(), false, true);
    ids.read(vh.getZkClient());
    assertEquals(ids.get().id1, config.getIdAllocationUnit() + 1);

    TWrapper<Directory> rootDh = new TWrapper<>(vh.getRootZpath(), new Directory());
    rootDh.read(vh.getZkClient());
    assertEquals(rootDh.get().name, VolumeHandler.VOLUME_ROOT_NAME);

    assertEquals(kh.getVolumeHandler(volumeId), vh);

    // test loading of volumes
    KurmaHandler kh2 = new KurmaHandler(client, dummyGarbageCollector, config, conflictResolver);
    VolumeHandler vh2 = kh2.getVolumeHandler(volumeId);
    assertNotNull(vh2);
    assertEquals(volumeId, vh2.get().id);
    assertTrue(kh != kh2);
    assertTrue(vh != vh2);
  }
}
