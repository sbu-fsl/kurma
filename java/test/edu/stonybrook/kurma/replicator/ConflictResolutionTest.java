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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;

import org.apache.commons.io.FileUtils;
import org.apache.curator.test.TestingServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.fs.KurmaResult;
import edu.stonybrook.kurma.fs.OpenFlags;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.DirectoryHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.StatusHelper;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.KurmaServiceHandler;
import edu.stonybrook.kurma.server.SessionManager;
import edu.stonybrook.kurma.util.RandomBuffer;

public class ConflictResolutionTest extends GatewayReplicationSetup {

  private GatewayMessageHandler localHandler;
  private GatewayMessageHandler remoteHandler;
  private int HEDWIG_DELAY_MS;
  private int REPLICATION_PROCESS_DELAY_MS = 0;

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
    // Change Hedwig delay for this test cases because of testing delay used
    // in replcation
    HEDWIG_DELAY_MS = 20000;
    REPLICATION_PROCESS_DELAY_MS = 1000;
    localConfig = new GatewayConfig(GatewayConfig.KURMA_LOCAL_CONFIG_FILE);
    remoteConfig = new GatewayConfig(GatewayConfig.KURMA_REMOTE_CONFIG_FILE);

    FileUtils.cleanDirectory(new File(localConfig.getJournalDirectory()));
    FileUtils.cleanDirectory(new File(remoteConfig.getJournalDirectory()));

    localKurmaHandler = buildKurmaHandler(localConfig);
    remoteKurmaHandler = buildKurmaHandler(remoteConfig);

    /** hedwigClient is an inherited non-static variable */
    GatewayMessageFilter localFilter = new GatewayMessageFilter(localConfig.getGatewayId());
    localHandler = new GatewayMessageHandler(localKurmaHandler, REPLICATION_PROCESS_DELAY_MS, 8);
    localReplicator = new KurmaReplicator(localConfig, hedwigClient, localHandler, localFilter);
    localSessionManager = new SessionManager(localKurmaHandler, 1000);
    localService = new KurmaServiceHandler(localKurmaHandler, localSessionManager, localConfig,
        localReplicator);

    GatewayMessageFilter remoteFilter = new GatewayMessageFilter(remoteConfig.getGatewayId());
    remoteHandler = new GatewayMessageHandler(remoteKurmaHandler, REPLICATION_PROCESS_DELAY_MS, 8);
    remoteReplicator = new KurmaReplicator(remoteConfig, hedwigClient, remoteHandler, remoteFilter);
    remoteSessionManager = new SessionManager(remoteKurmaHandler, 1000);
    remoteService = new KurmaServiceHandler(remoteKurmaHandler, remoteSessionManager, remoteConfig,
        remoteReplicator);

    randomBuffer = new RandomBuffer();
    createTestingVolume();
  }

  @Test
  public void testDeleteFileSameName() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // create local file under root
    final String filename = "testDeleteSameFileName";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localFileAttrs = AttributesHelper.newFileAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.create(localSessionId, rootOid, filename, localFileAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + filename);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isFile(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Delete this file locally and from remote as well without waiting for
    // replication
    localKr = localService.unlink(localSessionId, rootOid, filename);
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, filename);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // wait for both messages to get replicated
    Thread.sleep(HEDWIG_DELAY_MS);
    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getSuccessCount(), 2);
  }

  @Test
  public void testDeleteDirectorySameName() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // create local dir under root
    final String dirname = "testDeleteSameDirName";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localDirAttrs = AttributesHelper.newDirAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.mkdir(localSessionId, rootOid, dirname, localDirAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + dirname);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isDirectory(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Delete this dir locally and from remote as well without waiting for
    // replication
    localKr = localService.unlink(localSessionId, rootOid, dirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, dirname);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // wait for both messages to get replicated
    Thread.sleep(HEDWIG_DELAY_MS);
    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getSuccessCount(), 2);
  }

  @Test
  public void testSetAttrOnDeletedFile() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);
    localHandler.setReceivedMsgCount(0);
    remoteHandler.setReceivedMsgCount(0);

    // create local file under root
    final String filename = "testSetAttrOnDeletedFile";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localFileAttrs = AttributesHelper.newFileAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.create(localSessionId, rootOid, filename, localFileAttrs);
    ObjectID fileOid = localKr.getOid();
    assertTrue(ObjectIdHelper.isFile(fileOid));
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + filename);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isFile(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Delete this file locally and from remote as well without waiting for
    // replication
    localKr = localService.unlink(localSessionId, rootOid, filename);
    remoteKr =
        remoteService.setattrs(remoteSessionId, fileOid, AttributesHelper.newFileAttributes());
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // wait for both messages to get replicated
    Thread.sleep(HEDWIG_DELAY_MS);
    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getSuccessCount(), 2);
    assertEquals(localHandler.getReceivedMsgCount(), 1);
    assertEquals(remoteHandler.getReceivedMsgCount(), 2);
  }

  @Test
  public void testSetAttrOnDeletedDirectory() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // create local dir under root
    final String dirname = "testSetAttrOnDeletedDir";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localDirAttrs = AttributesHelper.newDirAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.mkdir(localSessionId, rootOid, dirname, localDirAttrs);
    ObjectID dirOid = localKr.getOid();
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + dirname);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isDirectory(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Delete this file locally and from remote as well without waiting for
    // replication
    localKr = localService.unlink(localSessionId, rootOid, dirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.setattrs(remoteSessionId, dirOid, AttributesHelper.newDirAttributes());
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // wait for both messages to get replicated
    Thread.sleep(HEDWIG_DELAY_MS);
    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getSuccessCount(), 2);
  }

  @Test
  public void testUpdateFileConflict() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // Create a file under root
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    final String filename = "NyFileToUpdate";
    KurmaResult kr = localService.create(localSessionId, rootOid, filename, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();

    // make sure it got replicated
    Thread.sleep(HEDWIG_DELAY_MS);

    // write first block at both the gateway, this set same block size too
    kr = localService.open(localSessionId, fileOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    KurmaResult remoteKr = remoteService.open(remoteSessionId, fileOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    ByteBuffer localDataOld = randomBuffer.genRandomBuffer(BLOCKSIZE);
    localDataOld.mark();
    ByteBuffer remoteDataOld = randomBuffer.genRandomBuffer(BLOCKSIZE);
    remoteDataOld.mark();

    kr = localService.write(localSessionId, fileOid, 0, localDataOld);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    remoteKr = remoteService.write(remoteSessionId, fileOid, 0, remoteDataOld);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    // Wait for first write to propagate.
    Thread.sleep(HEDWIG_DELAY_MS);
    kr = localService.read(localSessionId, fileOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertEquals(ByteBuffer.wrap(remoteKr.getFile_data()), ByteBuffer.wrap(kr.getFile_data()));

    /* exactly same blocks conflicting */
    // Modify same file block on remote first and then on local gateway
    // without waiting for it to replicate
    ByteBuffer localDataNew = randomBuffer.genRandomBuffer(BLOCKSIZE);
    localDataNew.mark();
    ByteBuffer remoteData = randomBuffer.genRandomBuffer(BLOCKSIZE);
    remoteData.mark();
    kr = localService.write(localSessionId, fileOid, 0, localDataNew);
    remoteKr = remoteService.write(remoteSessionId, fileOid, 0, remoteData);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    // Read block after messages are replicated, Both gateway should have
    // same data and same block versions
    Thread.sleep(HEDWIG_DELAY_MS);
    kr = localService.read(localSessionId, fileOid, 0, BLOCKSIZE);
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, BLOCKSIZE);
    assertEquals(ByteBuffer.wrap(kr.getFile_data()), remoteData);
    assertEquals(ByteBuffer.wrap(remoteKr.getFile_data()), remoteData);
    assertEquals(ByteBuffer.wrap(remoteKr.getFile_data()), ByteBuffer.wrap(kr.getFile_data()));

    /* Partial overlapping of blocks in conflict */
    ByteBuffer localDataP = randomBuffer.genRandomBuffer(3 * BLOCKSIZE);
    localDataP.mark();
    ByteBuffer remoteDataP = randomBuffer.genRandomBuffer(3 * BLOCKSIZE);
    remoteDataP.mark();
    kr = localService.write(localSessionId, fileOid, 0, localDataP);
    remoteKr = remoteService.write(remoteSessionId, fileOid, BLOCKSIZE, remoteDataP);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    // Read block after messages are replicated, Both gateway should have
    // same data and same block versions
    Thread.sleep(HEDWIG_DELAY_MS);
    kr = localService.read(localSessionId, fileOid, 0, 4 * BLOCKSIZE);
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, 4 * BLOCKSIZE);
    assertEquals(ByteBuffer.wrap(remoteKr.getFile_data()), ByteBuffer.wrap(kr.getFile_data()));

    /*
     * One gateway modify blocks that covers all blocks modified by other gateway
     */
    ByteBuffer localDataC = randomBuffer.genRandomBuffer(2 * BLOCKSIZE);
    localDataC.mark();
    ByteBuffer remoteDataC = randomBuffer.genRandomBuffer(BLOCKSIZE);
    remoteDataC.mark();
    kr = localService.write(localSessionId, fileOid, 0, localDataC);
    remoteKr = remoteService.write(remoteSessionId, fileOid, 0, remoteDataC);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    // Read block after messages are replicated, Both gateway should have
    // same data and same block versions
    Thread.sleep(HEDWIG_DELAY_MS);
    kr = localService.read(localSessionId, fileOid, 0, BLOCKSIZE);
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, BLOCKSIZE);
    assertEquals(ByteBuffer.wrap(remoteKr.getFile_data()), ByteBuffer.wrap(kr.getFile_data()));

    // Test for non conflicting updates from both gateway
    ByteBuffer localDataNC = randomBuffer.genRandomBuffer(BLOCKSIZE);
    localDataC.mark();
    ByteBuffer remoteDataNC = randomBuffer.genRandomBuffer(3 * BLOCKSIZE);
    remoteDataC.mark();
    kr = localService.write(localSessionId, fileOid, 0, localDataNC);
    remoteKr = remoteService.write(remoteSessionId, fileOid, BLOCKSIZE, remoteDataNC);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    // Read block after messages are replicated, Both gateway should have
    // same data and same block versions
    Thread.sleep(HEDWIG_DELAY_MS);
    kr = localService.read(localSessionId, fileOid, 0, 4 * BLOCKSIZE);
    remoteKr = remoteService.read(remoteSessionId, fileOid, 0, 4 * BLOCKSIZE);
    assertEquals(ByteBuffer.wrap(remoteKr.getFile_data()), ByteBuffer.wrap(kr.getFile_data()));

    assertEquals(localHandler.getSuccessCount(), 5);
    assertEquals(remoteHandler.getSuccessCount(), 6);
  }

  /*
   * Below test correctness of conflict resolution for create file with the same name at two
   * gateway.
   *
   * It creates file and wait for both gateways to resolve conflict. It then operates (i.e. update
   * file) on those resolved file name from the gateway that created it. It also operates on those
   * resolved file name from gateway that didn't create it NOTE: In last scenario mentioned above,
   * we test unlink operation on resolved files It tests whether original name is restored from
   * resolved name or not
   */
  @Test
  public void testCreateFileConflict() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // create same file under same dir on both gateways
    final String fileName = "testCreateSameFileConflict";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localFileAttrs = AttributesHelper.newFileAttributes();
    ObjectAttributes remoteFileAttrs = AttributesHelper.newFileAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.create(localSessionId, rootOid, fileName, localFileAttrs);
    LOGGER.info("{} created locally at Gateway-{}", fileName, localConfig.getGatewayName());
    KurmaResult remoteKr =
        remoteService.create(remoteSessionId, rootOid, fileName, remoteFileAttrs);
    LOGGER.info("{} created remotely at Gateway-{}", fileName, remoteConfig.getGatewayName());
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    ObjectID localOid = localKr.getOid();
    ObjectID remoteOid = remoteKr.getOid();
    assertNotEquals(localOid, remoteOid);

    // Make sure that both gateway resolve conflicts and they have file with
    // remote gwid suffix
    Thread.sleep(HEDWIG_DELAY_MS);

    String localFileNameToOperate =
        DirectoryHelper.getSuffixedName(fileName, remoteConfig.getGatewayId());
    String remoteFileNameToOperate =
        DirectoryHelper.getSuffixedName(fileName, localConfig.getGatewayId());
    try {
    localKr = localService.lookup(localSessionId, rootOid, localFileNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, rootOid, remoteFileNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    } catch (AssertionError e) {
      System.exit(1);
    }

    // perform operation (i.e. write) on those files after conflict
    // resolution
    localKr = localService.open(localSessionId, localOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.open(remoteSessionId, remoteOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    /* Accessing files from its creator's gateway */
    ByteBuffer localData = randomBuffer.genRandomBuffer(BLOCKSIZE);
    localData.mark();
    ByteBuffer remoteData = randomBuffer.genRandomBuffer(BLOCKSIZE);
    remoteData.mark();
    localKr = localService.write(localSessionId, localOid, 0, localData);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.write(remoteSessionId, remoteOid, 0, remoteData);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    localKr = localService.close(localSessionId, localOid);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.close(remoteSessionId, remoteOid);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // Read file after messages are replicated, Both gateway should have
    // each other's files
    Thread.sleep(HEDWIG_DELAY_MS);
    localKr = localService.open(localSessionId, remoteOid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.open(remoteSessionId, localOid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    localKr = localService.read(localSessionId, remoteOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.read(remoteSessionId, localOid, 0, BLOCKSIZE);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertEquals(remoteData, ByteBuffer.wrap(localKr.getFile_data()));
    assertEquals(localData, ByteBuffer.wrap(remoteKr.getFile_data()));

    localKr = localService.close(localSessionId, remoteOid);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.close(remoteSessionId, localOid);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    /* Accessing files from gateway other than the creator */
    localKr = localService.unlink(localSessionId, rootOid, localFileNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, remoteFileNameToOperate);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    localKr = localService.lookup(localSessionId, rootOid, fileName);
    assertFalse(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, rootOid, fileName);
    assertFalse(StatusHelper.isOk(remoteKr.getStatus()));

    assertEquals(localHandler.getSuccessCount(), 3);
    assertEquals(remoteHandler.getSuccessCount(), 3);
  }

  /*
   * Below test correctness of conflict resolution for create directory with the same name at two
   * gateway.
   *
   * It creates directory and wait for both gateway to resolve conflict. It then operates (i.e.
   * create child file) on those resolved directory name from the gateway that created it. It also
   * operates on those resolved directory name from gateway that didn't create it NOTE: In last
   * scenario mentioned above, we test rename operation on resolved directory It tests whether
   * original name is restored from resolved name or not
   */
  @Test
  public void testCreateDirConflict() throws Exception {
    // set success count and conflict count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // create same dir under same root dir on both gateway
    final String dirName = "testCreateSameDirConflict";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localDirAttrs = AttributesHelper.newDirAttributes();
    ObjectAttributes remoteDirAttrs = AttributesHelper.newDirAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.mkdir(localSessionId, rootOid, dirName, localDirAttrs);
    KurmaResult remoteKr = remoteService.mkdir(remoteSessionId, rootOid, dirName, remoteDirAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    ObjectID localDirOid = localKr.getOid();
    ObjectID remoteDirOid = remoteKr.getOid();
    assertNotEquals(localDirOid, remoteDirOid);

    // Make sure that both gateway resolve conflicts and they have dir with
    // remote gwid suffix
    Thread.sleep(HEDWIG_DELAY_MS);
    String localDirNameToOperate =
        DirectoryHelper.getSuffixedName(dirName, remoteConfig.getGatewayId());
    String remoteDirNameToOperate =
        DirectoryHelper.getSuffixedName(dirName, localConfig.getGatewayId());
    localKr = localService.lookup(localSessionId, rootOid, localDirNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, rootOid, remoteDirNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    /*
     * Accessing directories from gateway that created it Perform operation (i.e. create child file)
     * on those directory after conflict is resolved to make sure its correctness
     */
    final String localFilename = "localFileName";
    final String remoteFilename = "remoteFileName";
    ObjectAttributes localFileAttrs = AttributesHelper.newFileAttributes();
    ObjectAttributes remoteFileAttrs = AttributesHelper.newFileAttributes();

    localKr = localService.create(localSessionId, localDirOid, localFilename, localFileAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.create(remoteSessionId, remoteDirOid, remoteFilename, remoteFileAttrs);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // wait for changes to be replicated
    Thread.sleep(HEDWIG_DELAY_MS);

    localKr = localService.lookup(localSessionId, remoteDirOid, remoteFilename);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, localDirOid, localFilename);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    /*
     * Accessing directories from gateway other than gateway that created it (i.e. rename dir)
     */
    String newLocalDirName = "newLocalDirectory";
    String newRemoteDirName = "newRemoteDirectory";
    localKr = localService.rename(localSessionId, rootOid, localDirNameToOperate, rootOid,
        newLocalDirName);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.rename(remoteSessionId, rootOid, remoteDirNameToOperate, rootOid,
        newRemoteDirName);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);

    localKr = localService.lookup(localSessionId, rootOid, newRemoteDirName);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, rootOid, newLocalDirName);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    assertEquals(localHandler.getSuccessCount(), 3);
    assertEquals(remoteHandler.getSuccessCount(), 3);
  }

  /*
   * Below test checks the correctness of restoring resolved name to original name after directory
   * name is resolved using suffix name approach.
   *
   * First it will create directory such that it ends up with directory name conflict and then it
   * will try to remove resolved directories from gateway that DIDN'T create it
   */
  @Test
  public void testRestoreOriginalDirNameAfterResolution() throws Exception {
    // set success count and conflict count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);

    // create directory with same name at both gateway
    final String dirName = "testRestoreDirName";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localDirAttrs = AttributesHelper.newDirAttributes();
    ObjectAttributes remoteDirAttrs = AttributesHelper.newDirAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.mkdir(localSessionId, rootOid, dirName, localDirAttrs);
    KurmaResult remoteKr = remoteService.mkdir(remoteSessionId, rootOid, dirName, remoteDirAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    ObjectID localDirOid = localKr.getOid();
    ObjectID remoteDirOid = remoteKr.getOid();
    assertNotEquals(localDirOid, remoteDirOid);

    // Make sure that both gateway resolve conflicts and they have dir with
    // remote gwid suffix
    Thread.sleep(HEDWIG_DELAY_MS);

    String localDirNameToOperate =
        DirectoryHelper.getSuffixedName(dirName, remoteConfig.getGatewayId());
    String remoteDirNameToOperate =
        DirectoryHelper.getSuffixedName(dirName, localConfig.getGatewayId());
    localKr = localService.lookup(localSessionId, rootOid, localDirNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, rootOid, remoteDirNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Delete directory from gateway that didn't create it
    localKr = localService.unlink(localSessionId, rootOid, localDirNameToOperate);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, remoteDirNameToOperate);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));

    // wait for changes to be replicated
    Thread.sleep(HEDWIG_DELAY_MS);

    // lookup with original name of directory at both gateway, both lookups
    // should fail
    localKr = localService.lookup(localSessionId, rootOid, dirName);
    assertFalse(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.lookup(remoteSessionId, rootOid, dirName);
    assertFalse(StatusHelper.isOk(localKr.getStatus()));

    assertEquals(localHandler.getSuccessCount(), 2);
    assertEquals(remoteHandler.getSuccessCount(), 2);
  }

  @Test
  public void testDeleteRenameNonEmptyDirConflict() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);
    remoteHandler.setReceivedMsgCount(0);

    // create local dir under root
    final String dirname = "testDeleteRenameNonEmptyDir";
    final String renamedDirname = "RenamedDir";
    final String childDir = "childDir";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localDirAttrs = AttributesHelper.newDirAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.mkdir(localSessionId, rootOid, dirname, localDirAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    ObjectID oid = localKr.getOid();

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + dirname);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isDirectory(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Rename this dir locally and delete from remote without waiting for
    // replication
    localKr = localService.rename(localSessionId, rootOid, dirname, rootOid, renamedDirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    localKr =
        localService.mkdir(localSessionId, oid, childDir, AttributesHelper.newDirAttributes());
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, dirname);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    // Non-Empty renamed directory should not be deleted
    localKr = localService.lookup(localSessionId, rootOid, renamedDirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    // Delete renamed dir and its child, it shouldn't be replicated
    localKr = localService.unlink(localSessionId, oid, childDir);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    localKr = localService.unlink(localSessionId, rootOid, renamedDirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // wait for messages to get replicated
    Thread.sleep(HEDWIG_DELAY_MS);
    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getReceivedMsgCount(), 3);
    assertEquals(remoteHandler.getSuccessCount(), 3);
  }

  @Test
  public void testDeleteRenameEmptyDirConflict() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);
    remoteHandler.setReceivedMsgCount(0);

    // create local dir under root
    final String dirname = "testDeleteRenameEmptyDir";
    final String renamedDirname = "RenamedDir";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localDirAttrs = AttributesHelper.newDirAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.mkdir(localSessionId, rootOid, dirname, localDirAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + dirname);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isDirectory(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Rename this dir locally and delete from remote without waiting for
    // replication
    localKr = localService.rename(localSessionId, rootOid, dirname, rootOid, renamedDirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, dirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    // Empty renamed directory shouldn't be deleted
    localKr = localService.lookup(localSessionId, rootOid, renamedDirname);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getSuccessCount(), 2);
    assertEquals(remoteHandler.getReceivedMsgCount(), 2);
  }

  @Test
  public void testDeleteRenameFileConflict() throws Exception {
    // set success count to '0' initially
    localHandler.setSuccessCount(0);
    remoteHandler.setSuccessCount(0);
    remoteHandler.setReceivedMsgCount(0);

    // create local dir under root
    final String filename = "testDeleteRenameFile";
    final String renamedFilename = "renamedFileName";
    ByteBuffer localSessionId = createSession(localService);
    ByteBuffer remoteSessionId = createSession(remoteService);
    ObjectAttributes localFileAttrs = AttributesHelper.newFileAttributes();
    short creatorGwid =
        localKurmaHandler.getVolumeHandler(TEST_VOLUME_NAME).getVolumeInfo().getCreator();
    ObjectID rootOid = ObjectIdHelper.getRootOid(creatorGwid);

    KurmaResult localKr = localService.create(localSessionId, rootOid, filename, localFileAttrs);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    // Make sure that remote receives this change
    Thread.sleep(HEDWIG_DELAY_MS);
    KurmaResult remoteKr = getattrsRemote("/" + filename);
    assertTrue(StatusHelper.isOk(remoteKr.getStatus()));
    assertTrue(ObjectIdHelper.isFile(remoteKr.getOid()));
    assertEquals(localKr.getOid(), remoteKr.getOid());

    // Rename this file locally and delete from remote without waiting for
    // replication
    localKr = localService.rename(localSessionId, rootOid, filename, rootOid, renamedFilename);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));
    remoteKr = remoteService.unlink(remoteSessionId, rootOid, filename);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    Thread.sleep(HEDWIG_DELAY_MS);
    // Renamed file shouldn't be deleted
    localKr = localService.lookup(localSessionId, rootOid, renamedFilename);
    assertTrue(StatusHelper.isOk(localKr.getStatus()));

    assertEquals(localHandler.getSuccessCount(), 1);
    assertEquals(remoteHandler.getSuccessCount(), 2);
    assertEquals(remoteHandler.getReceivedMsgCount(), 2);
  }
}
