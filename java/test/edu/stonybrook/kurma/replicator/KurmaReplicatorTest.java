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
package edu.stonybrook.kurma.replicator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.fs.KurmaError;
import edu.stonybrook.kurma.fs.KurmaResult;
import edu.stonybrook.kurma.fs.OpenFlags;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.StatusHelper;
import edu.stonybrook.kurma.helpers.VolumeInfoHelper;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.KurmaServiceHandler;
import edu.stonybrook.kurma.server.SessionManager;
import edu.stonybrook.kurma.util.RandomBuffer;

public final class KurmaReplicatorTest extends GatewayReplicationSetup {

  protected static final int REPLICATION_THREADS = 8;

  @BeforeClass
  public static void setUpClass() throws Exception {
    zkServer = new TestingServer();
    zkServer.start();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    zkServer.close();
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    localConfig = new GatewayConfig(GatewayConfig.KURMA_LOCAL_CONFIG_FILE);
    remoteConfig = new GatewayConfig(GatewayConfig.KURMA_REMOTE_CONFIG_FILE);

    localKurmaHandler = buildKurmaHandler(localConfig);
    remoteKurmaHandler = buildKurmaHandler(remoteConfig);

    /** hedwigClient is an inherited non-static variable */
    GatewayMessageFilter localFilter = new GatewayMessageFilter(localConfig.getGatewayId());
    GatewayMessageHandler localHandler =
        new GatewayMessageHandler(localKurmaHandler, REPLICATION_THREADS, 8);
    localReplicator = new KurmaReplicator(localConfig, hedwigClient, localHandler, localFilter);
    localSessionManager = new SessionManager(localKurmaHandler, 60000);
    localService = new KurmaServiceHandler(localKurmaHandler, localSessionManager, localConfig,
        localReplicator);

    GatewayMessageFilter remoteFilter = new GatewayMessageFilter(remoteConfig.getGatewayId());
    GatewayMessageHandler remoteHandler =
        new GatewayMessageHandler(remoteKurmaHandler, REPLICATION_THREADS, 8);
    remoteReplicator = new KurmaReplicator(remoteConfig, hedwigClient, remoteHandler, remoteFilter);
    remoteSessionManager = new SessionManager(remoteKurmaHandler, 60000);
    remoteService = new KurmaServiceHandler(remoteKurmaHandler, remoteSessionManager, remoteConfig,
        remoteReplicator);

    randomBuffer = new RandomBuffer();
    createTestingVolume();
  }

  @Override
  @After
  public void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * Test that messages are successfully replicated between local and remote gateways.
   *
   * @throws Exception
   */
  @Test
  public void testMessageReplication() throws Exception {
    // Publish from local gateway to remote gateway.
    GatewayMessageBuilder localBuilder = new GatewayMessageBuilder(localConfig);
    localReplicator
        .broadcast(localBuilder.buildVolumeCreation(VolumeInfoHelper.newVolumeInfo("volume1")));
    localReplicator
        .broadcast(localBuilder.buildVolumeCreation(VolumeInfoHelper.newVolumeInfo("volume2")));

    // Publish from remote gateway to local gateway.
    GatewayMessageBuilder remoteBuilder = new GatewayMessageBuilder(remoteConfig);
    remoteReplicator
        .broadcast(remoteBuilder.buildVolumeCreation(VolumeInfoHelper.newVolumeInfo("volume-a")));

    Thread.sleep(HEDWIG_DELAY_MS);
    assertEquals(localReplicator.getSuccessCount(), 3);
    assertEquals(remoteReplicator.getSuccessCount(), 1);

    // Check the replicator has indeed created the two volumes upon
    // receiving the messages.
    List<String> remoteVolumes = Collections.list(remoteKurmaHandler.getVolumes());
    Collections.sort(remoteVolumes);
    assertEquals(remoteVolumes.size(), 3);
    assertEquals(remoteVolumes.get(0), TEST_VOLUME_NAME);
    assertEquals(remoteVolumes.get(1), "volume1");
    assertEquals(remoteVolumes.get(2), "volume2");

    Enumeration<String> localVolume = localKurmaHandler.getVolumes();
    assertTrue(localVolume.hasMoreElements());
    assertEquals(localVolume.nextElement(), "volume-a");

    // Check FS meta-data in ZooKeeper.
    assertNotNull(curator.usingNamespace(null).checkExists().forPath(getRemoteZpath("volume1")));
    assertNotNull(curator.usingNamespace(null).checkExists().forPath(getRemoteZpath("volume2")));
    assertNotNull(curator.usingNamespace(null).checkExists().forPath(getLocalZpath("volume-a")));
  }

  /**
   * Test that a directory created locally is also created remotely.
   *
   * @throws Exception
   */
  @Test
  public void testCreatingDirectories() throws Exception {
    randomBuffer.setRandomSeed(1019);
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    KurmaResult kr = localService.mkdir(sessionId, rootOid, "NyDir", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/NyDir");
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isDirectory(remoteKr.getOid()));
    assertEquals(kr.getOid(), remoteKr.getOid());
  }

  private boolean searchForName(List<DirEntry> entries, String name) {
    boolean found = false;
    for (DirEntry de : entries) {
      LOGGER.info("item {}: {}", de.getName(), de.getOid());
      if (name.equals(de.getName())) {
        found = true;
        break;
      }
    }
    return found;
  }

  @Test
  public void testCreatingFile() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    KurmaResult kr = localService.create(sessionId, rootOid, "NyFile", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    long localCtime = kr.getNew_attrs().getChange_time();

    Thread.sleep(HEDWIG_DELAY_MS);

    KurmaResult remoteKr = getattrsRemote("/NyFile");
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isFile(remoteKr.getOid()));
    assertEquals(kr.getOid(), remoteKr.getOid());
    assertEquals(localCtime, remoteKr.getNew_attrs().getChange_time());

    remoteKr = listRootDirRemote();
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(searchForName(remoteKr.getDir_data(), "NyFile"));
  }

  /**
   * Test that a directory created locally is also created remotely.
   *
   * @throws Exception
   */
  @Test
  public void testNewFileInNewDirectory() throws Exception {
    randomBuffer.setRandomSeed(1019);
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    KurmaResult kr = localService.mkdir(sessionId, rootOid, "nfsdata", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fsOid = kr.getOid();

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/nfsdata");
    long time1 = remoteKr.getNew_attrs().getChange_time();
    int nlinks = remoteKr.getNew_attrs().getNlinks();

    ObjectAttributes fileAttrs = AttributesHelper.newFileAttributes();
    kr = localService.create(sessionId, fsOid, "hello.txt", fileAttrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    // long changeTime = kr.getNew_attrs().getChange_time();

    Thread.sleep(HEDWIG_DELAY_MS);
    remoteKr = listdirRemote(fsOid);
    assertTrue(searchForName(remoteKr.getDir_data(), "hello.txt"));
    remoteKr = getattrsRemote("/nfsdata");
    long time2 = remoteKr.getNew_attrs().getChange_time();
    assertTrue(time2 > time1);
    assertEquals(nlinks + 1, remoteKr.getNew_attrs().getNlinks());
    // assertEquals(changeTime, remoteKr.getNew_attrs().getChange_time());
  }

  /**
   * Test that a file created remotely will not be blocked by negative cache.
   *
   * @throws Exception
   */
  @Test
  public void testNegativeCache() throws Exception {
    randomBuffer.setRandomSeed(1019);
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    KurmaResult kr = localService.mkdir(sessionId, rootOid, "testNegativeCache", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/testNegativeCache");
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    ObjectID dirOid = remoteKr.getOid();
    // negative cache
    remoteKr = getattrs(remoteService, dirOid, "fileToCreate");
    assertEquals(KurmaError.OBJECT_NOT_FOUND.getValue(), remoteKr.getStatus().getErrcode());

    ObjectAttributes fileAttrs = AttributesHelper.newFileAttributes();
    kr = localService.create(sessionId, dirOid, "fileToCreate", fileAttrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    remoteKr = getattrs(remoteService, dirOid, "fileToCreate");
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
  }

  @Test
  public void testChmod() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    KurmaResult kr = localService.create(sessionId, rootOid, "NyFileChmod", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();

    // change mode
    int newMode = 0b0111111111;
    attrs.setMode(newMode);
    kr = localService.setattrs(sessionId, fileOid, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/NyFileChmod");
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertEquals(fileOid, remoteKr.getOid());
    assertEquals(newMode, remoteKr.getNew_attrs().getMode());
  }

  @Test
  public void testRename() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    final String oldName = "NyRenameFileOld";
    final String newName = "NyRenameFileNew";
    KurmaResult kr = localService.create(sessionId, rootOid, oldName, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();

    kr = localService.rename(sessionId, rootOid, oldName, rootOid, newName);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote(oldName);
    assertFalse(StatusHelper.isOk(remoteKr.getStatus()));
    remoteKr = getattrsRemote(newName);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertEquals(fileOid, remoteKr.getOid());
  }

  @Test
  public void testDeleteFile() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    final String filename = "NyFileToDelete";
    KurmaResult kr = localService.create(sessionId, rootOid, filename, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = localService.unlink(sessionId, rootOid, filename);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote(filename);
    assertFalse(StatusHelper.isOk(remoteKr.getStatus()));
  }

  @Test
  public void testDeleteDirectory() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    final String dirname = "NyDirToDelete";
    KurmaResult kr = localService.mkdir(sessionId, rootOid, dirname, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = localService.unlink(sessionId, rootOid, dirname);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote(dirname);
    assertFalse(StatusHelper.isOk(remoteKr.getStatus()));
  }

  @Test
  public void testWriteFile() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    final String filename = "NyFileToWrite";
    KurmaResult kr = localService.create(sessionId, rootOid, filename, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();

    ByteBuffer oldData = randomBuffer.genRandomBuffer(4096);
    kr = localService.open(sessionId, fileOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    kr = localService.write(sessionId, fileOid, 0, oldData);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote(filename);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertEquals(remoteKr.getNew_attrs().getFilesize(), 4096);
  }

  @Test
  public void testUpdateFile() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    ByteBuffer sessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    final String filename = "FileToUpdate";
    KurmaResult kr = localService.create(sessionId, rootOid, filename, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();

    ByteBuffer oldData = randomBuffer.genRandomBuffer(BLOCKSIZE);
    oldData.mark();
    kr = localService.open(sessionId, fileOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    kr = localService.write(sessionId, fileOid, 0, oldData);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    long localBlockShift = kr.getNew_attrs().getBlock_shift();
    long mtime1 = localService.getattrs(sessionId, fileOid).getNew_attrs().getModify_time();

    // Wait for the initial file creation and first write to propagate.
    Thread.sleep(HEDWIG_DELAY_MS);

    KurmaResult remoteKr = getattrsRemote(filename);
    assertEquals(localBlockShift, remoteKr.getNew_attrs().getBlock_shift());
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    LOGGER.debug("local time {}; remote time {}", mtime1, remoteKr.getNew_attrs().getModify_time());
    assertEquals(remoteKr.getNew_attrs().getModify_time(), mtime1);

    remoteKr = remoteService.open(remoteSessionId, fileOid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    ByteBuffer fileData = ByteBuffer.wrap(remoteKr.getFile_data());
    assertEquals(fileData.remaining(), BLOCKSIZE);
    assertEquals(fileData.capacity(), BLOCKSIZE);
    assertTrue(fileData.equals(oldData));
    long remoteChangeTime1 = remoteKr.getNew_attrs().getRemote_change_time();

    ByteBuffer newData = randomBuffer.genRandomBuffer(BLOCKSIZE);
    newData.mark();
    kr = localService.write(sessionId, fileOid, 0, newData);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    long mtime2 = localService.getattrs(sessionId, fileOid).getNew_attrs().getModify_time();
    assertTrue(mtime1 <= mtime2);

    // Wait for the second write to propagate.
    Thread.sleep(HEDWIG_DELAY_MS);

    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    fileData = ByteBuffer.wrap(remoteKr.getFile_data());
    assertEquals(fileData.remaining(), BLOCKSIZE);
    assertEquals(fileData.capacity(), BLOCKSIZE);
    assertTrue(!fileData.equals(oldData));
    ByteBuffer expected = ByteBuffer.wrap(newData.array(), 0, BLOCKSIZE);
    System.out.printf("read: %x; expected: %x\n", fileData.hashCode(), expected.hashCode());
    assertEquals(fileData, ByteBuffer.wrap(newData.array(), 0, BLOCKSIZE));
    assertEquals(remoteKr.getNew_attrs().getModify_time(), mtime2);
    assertTrue(remoteChangeTime1 < remoteKr.getNew_attrs().getRemote_change_time());

    // TODO: check remoteKr.getBlock_versions()
  }

  @Test
  public void testCreateDirAndFile() throws Exception {
    ByteBuffer sessionId = createSession(localService);
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    ObjectID rootOid = ObjectIdHelper.getRootOid(localConfig.getGatewayId());
    KurmaResult kr = localService.mkdir(sessionId, rootOid, "NyCreateDirAndFile_Dir", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID dirOid = kr.getOid();
    assertTrue(ObjectIdHelper.isDirectory(dirOid));

    attrs = AttributesHelper.newFileAttributes();
    kr = localService.create(sessionId, dirOid, "NyCreateDirAndFile_File", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();
    kr = localService.open(sessionId, fileOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ByteBuffer writeData = randomBuffer.genRandomBuffer(BLOCKSIZE);
    kr = localService.write(sessionId, fileOid, 0, writeData);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    kr = localService.close(sessionId, fileOid);

    Thread.sleep(HEDWIG_DELAY_MS);

    ByteBuffer remoteSessionId = createSession(remoteService);
    KurmaResult remoteKr = remoteService.open(remoteSessionId, fileOid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    writeData.rewind();
    assertEquals(writeData, ByteBuffer.wrap(remoteKr.getFile_data()));
  }
}
