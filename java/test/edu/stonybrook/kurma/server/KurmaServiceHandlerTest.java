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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.hedwig.client.HedwigClient;
import org.apache.thrift.TException;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.fs.DynamicInfo;
import edu.stonybrook.kurma.fs.KurmaError;
import edu.stonybrook.kurma.fs.KurmaResult;
import edu.stonybrook.kurma.fs.OpenFlags;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.StatusHelper;
import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.message.OperationType;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.replicator.IReplicator;
import edu.stonybrook.kurma.replicator.KurmaReplicator;
import edu.stonybrook.kurma.replicator.MessageRecorder;

// @FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class KurmaServiceHandlerTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaServiceHandlerTest.class);
  private static String volumeId = "TestVolume1";
  private static SessionManager sm;
  private static HedwigTestServer hedwigServer;
  private static MessageRecorder recorder;
  private static KurmaServiceHandler service;
  private static IReplicator replicator;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
    sm = new SessionManager(kh, 1000);

    hedwigServer = new HedwigTestServer();
    hedwigServer.start();

    HedwigClient client = hedwigServer.newHedwigClient();
    recorder = new MessageRecorder();
    replicator = new KurmaReplicator(config, client, recorder, null);

    service = new KurmaServiceHandler(kh, sm, config, replicator);

    KurmaResult kr = service.format_volume(volumeId, 0);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    GatewayMessage gm = recorder.popMessage();
    assertEquals(OperationType.CREATE_VOLUME, gm.getOp_type());
    assertEquals(volumeId, gm.getOp().getCreate_volume().getVolume().getId());
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    hedwigServer.close();
    closeTestServer();
  }

  /**
   * Clear up messages before each test.
   *
   * @throws Exception
   */
  @Before
  public void setUp() throws Exception {
    Thread.sleep(100);
    while (recorder.getMessageCount() > 0) {
      recorder.popMessage();
      Thread.sleep(100);
    }
  }

  @Test
  public void testCreateAndRenewSession() throws Exception {
    setRandomSeed(1009);
    ByteBuffer clientId = genRandomBuffer(32);
    KurmaResult kr = service.create_session(clientId, volumeId);
    assertNotNull(kr);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertTrue(kr.isSetTimeout_sec());

    ByteBuffer sessionId = ByteBuffer.wrap(kr.getSessionid());
    kr = service.renew_session(sessionId);
    assertNotNull(kr);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
  }

  private ByteBuffer createSession() throws Exception {
    ByteBuffer clientId = genRandomBuffer(32);
    KurmaResult kr = service.create_session(clientId, volumeId);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    return ByteBuffer.wrap(kr.getSessionid());
  }

  private ObjectID getImplicitRootOid() {
    return ObjectIdHelper.newDirectoryOid(Int128Helper.newId(0, 0), (short) 0);
  }

  @Test
  public void testReclaimSessions() throws Exception {
    setRandomSeed(1013);
    ByteBuffer sessionId1 = createSession();
    ByteBuffer sessionId2 = createSession();

    Thread.sleep(990);
    // Renew session 1 after 900ms, i.e., before it times out (1000ms).
    KurmaResult kr1 = service.renew_session(sessionId1);
    assertTrue(StatusHelper.isOk(kr1.getStatus()));

    // Wait for slightly less than 60s, now, session-2 should have timed
    // out.
    Thread.sleep(config.getSessionTimeout() * 1000 + 1000);
    KurmaResult kr2 = service.renew_session(sessionId2);
    assertEquals(KurmaError.SESSION_NOT_EXIST.getValue(), kr2.getStatus().errcode);
  }

  @Test
  public void testMkdir() throws Exception {
    setRandomSeed(1019);
    ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "testMkdir1", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertTrue(kr.isSetNew_attrs());

    kr = service.lookup(sessionId, getImplicitRootOid(), "testMkdir1");
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertTrue(ObjectIdHelper.isDirectory(kr.getOid()));

    GatewayMessage gm = recorder.popMessage();
    assertEquals(OperationType.CREATE_DIR, gm.op_type);
    assertEquals(config.getLocalGateway().getId(), gm.getGwid());
    assertEquals("testMkdir1", gm.getOp().getCreate_dir().getDir().getName());
  }

  @Test
  public void testCreateFile() throws Exception {
    setRandomSeed(1021);
    ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    KurmaResult kr = service.create(sessionId, getImplicitRootOid(), "testCreateFile1", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertTrue(kr.isSetNew_attrs());
    assertTrue(ObjectIdHelper.isFile(kr.getOid()));
    assertEquals(kr.getNew_attrs().getNlinks(), 1);

    kr = service.lookup(sessionId, getImplicitRootOid(), "testCreateFile1");
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertTrue(ObjectIdHelper.isFile(kr.getOid()));

    assertEquals(OperationType.CREATE_FILE, recorder.popMessage().op_type);
  }

  @Test
  public void benchOpenAndCloseFile() throws Exception {
    final int N = 100;
    ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ObjectID[] fileIds = new ObjectID[N];
    for (int n = 0; n < N; ++n) {
      KurmaResult kr =
          service.create(sessionId, getImplicitRootOid(), "benchOpenAndCloseFile" + n, attrs);
      fileIds[n] = kr.getOid();
      assertTrue(StatusHelper.isOk(kr.getStatus()));
    }
    Random rand = new Random(8887);
    long start = System.nanoTime();
    for (int i = 0; i < 1000; ++i) {
      ObjectID oid = fileIds[rand.nextInt(N)];
      service.open(sessionId, oid, OpenFlags.WRITE);
    }
    rand = new Random(8887);
    for (int i = 0; i < 1000; ++i) {
      ObjectID oid = fileIds[rand.nextInt(N)];
      service.close(sessionId, oid);
    }
    long end = System.nanoTime();
    System.out.printf("average time: %.2fms\n", (end - start) / 1000.0 / 1000000);
  }

  @Test
  public void testParallelFileCreation() throws Exception {
    final ByteBuffer sessionId = createSession();
    // final int nFiles = 2 * config.getIdAllocationUnit();
    final int nFiles = 10;
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    Consumer<Integer> worker = new Consumer<Integer>() {
      private ConcurrentSkipListSet<BigInteger> oids_ = new ConcurrentSkipListSet<BigInteger>();

      @Override
      public void accept(Integer t) {
        int b = t.intValue() * 1000000;
        for (int i = 0; i < nFiles; ++i) {
          String filename = "parallelFileCreation" + (b + i);
          try {
            KurmaResult kr = service.create(sessionId, getImplicitRootOid(), filename, attrs);
            assertTrue(StatusHelper.isOk(kr.getStatus()));
            assertTrue(oids_.add(Int128Helper.toBigInteger(kr.getOid().getId())));
          } catch (TException e) {
            e.printStackTrace();
            assertTrue(false);
          }
        }
      }
    };
    final int nthreads = 16;
    doParallel(nthreads, worker);
    for (int i = nthreads * nFiles; i > 0; --i) {
      assertEquals(OperationType.CREATE_FILE, recorder.popMessage().op_type);
    }
  }

  private ObjectID createFile(ByteBuffer sessionId, String name, ByteBuffer data) throws Exception {
    return createFile(sessionId, getImplicitRootOid(), name, data);
  }

  private ObjectID createFile(ByteBuffer sessionId, ObjectID parentOid, String name,
      ByteBuffer data) throws Exception {
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    assertEquals(0, recorder.getMessageCount());
    KurmaResult kr = service.create(sessionId, parentOid, name, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID oid = kr.getOid();

    int size = data.remaining();
    attrs = new ObjectAttributes();
    attrs.setFilesize(size);
    kr = service.setattrs(sessionId, oid, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.open(sessionId, oid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.write(sessionId, oid, 0, data);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.close(sessionId, oid);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertEquals(size, kr.getNew_attrs().getFilesize());

    assertEquals(OperationType.CREATE_FILE, recorder.popMessage().op_type);
    assertEquals(OperationType.SET_ATTRIBUTES, recorder.popMessage().op_type);
    assertEquals(OperationType.UPDATE_FILE, recorder.popMessage().op_type);

    return oid;
  }

  @Test
  public void testWriteFile() throws Exception {
    setRandomSeed(1031);
    ByteBuffer sessionId = createSession();
    final int FILESIZE = 8192;
    ObjectID oid = createFile(sessionId, "testWriteFile1", genRandomBuffer(FILESIZE));
    assertNotNull(oid);
  }

  @Test
  public void testWriteCanExtendFileSize() throws Exception {
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    ByteBuffer sessionId = createSession();
    KurmaResult kr =
        service.create(sessionId, getImplicitRootOid(), "testWriteCanExtentFileSize", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID fileOid = kr.getOid();

    service.open(sessionId, fileOid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    service.write(sessionId, fileOid, 0, genRandomBuffer(4096));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    service.close(sessionId, fileOid);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    assertEquals(OperationType.CREATE_FILE, recorder.popMessage().op_type);
    assertEquals(OperationType.UPDATE_FILE, recorder.popMessage().op_type);
  }

  @Test
  public void testReadFile() throws Exception {
    setRandomSeed(1031);
    ByteBuffer sessionId = createSession();
    final int FILESIZE = 64 * 1024;
    ByteBuffer data = genRandomBuffer(FILESIZE);
    data.mark();
    ObjectID oid = createFile(sessionId, "testReadFile1", data);

    KurmaResult kr = service.open(sessionId, oid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.read(sessionId, oid, 0, FILESIZE);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    data.reset();
    assertTrue(Arrays.equals(data.array(), kr.getFile_data()));

    kr = service.close(sessionId, oid);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
  }

  @Test
  public void testChmod() throws Exception {
    setRandomSeed(1033);
    ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    int mode1 = 0b0110100000;
    attrs.setMode(mode1);
    KurmaResult kr1 = service.create(sessionId, getImplicitRootOid(), "testChmod1", attrs);
    assertTrue(StatusHelper.isOk(kr1.getStatus()));
    assertEquals(mode1, kr1.getNew_attrs().getMode());

    ObjectID oid = kr1.getOid();
    // change of attributes should not affect the attrs of the created file
    attrs.setMode(0);
    kr1 = service.getattrs(sessionId, oid);
    assertEquals(mode1, kr1.getNew_attrs().getMode());

    int mode2 = 0b0111101000;
    assertNotEquals(mode1, mode2);
    attrs = new ObjectAttributes();
    attrs.setMode(mode2); // chmod +x
    KurmaResult kr2 = service.setattrs(sessionId, oid, attrs);
    assertTrue(StatusHelper.isOk(kr2.getStatus()));
    assertEquals(mode2, kr2.getNew_attrs().getMode());

    // The two attributes should be different.
    // The change of attributes should not affect results of previous
    // requests.
    assertEquals(mode1, kr1.getNew_attrs().getMode());
    assertNotEquals(kr1.getNew_attrs().getMode(), kr2.getNew_attrs().getMode());

    assertEquals(OperationType.CREATE_FILE, recorder.popMessage().op_type);
    assertEquals(OperationType.SET_ATTRIBUTES, recorder.popMessage().op_type);
  }

  @Test
  public void testRenameInTheSameDirectory() throws Exception {
    setRandomSeed(1039);
    ByteBuffer sessionId = createSession();
    ByteBuffer data = genRandomBuffer(4096);
    createFile(sessionId, "name1", data);

    KurmaResult kr = service.lookup(sessionId, getImplicitRootOid(), "name1");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.rename(sessionId, getImplicitRootOid(), "name1", getImplicitRootOid(), "name2");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.lookup(sessionId, getImplicitRootOid(), "name1");
    assertEquals(KurmaError.OBJECT_NOT_FOUND.getValue(), kr.getStatus().getErrcode());

    kr = service.lookup(sessionId, getImplicitRootOid(), "name2");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    assertEquals(OperationType.RENAME, recorder.popMessage().op_type);
  }

  @Test
  public void testRenameToDifferentDirectory() throws Exception {
    setRandomSeed(1049);
    ByteBuffer sessionId = createSession();

    // create two directories: srcDir, and dstDir
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "srcDir", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID srcDirOid = kr.getOid();
    kr = service.mkdir(sessionId, getImplicitRootOid(), "dstDir", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID dstDirOid = kr.getOid();

    assertEquals(OperationType.CREATE_DIR, recorder.popMessage().op_type);
    assertEquals(OperationType.CREATE_DIR, recorder.popMessage().op_type);

    // create file
    ObjectID oid = createFile(sessionId, srcDirOid, "srcName", genRandomBuffer(4096));
    assertNotNull(oid);

    // rename file
    kr = service.rename(sessionId, srcDirOid, "srcName", dstDirOid, "dstName");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.lookup(sessionId, srcDirOid, "srcName");
    assertEquals(KurmaError.OBJECT_NOT_FOUND.getValue(), kr.getStatus().getErrcode());

    kr = service.lookup(sessionId, dstDirOid, "dstName");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    assertEquals(OperationType.RENAME, recorder.popMessage().op_type);
  }

  @Test
  public void testUnlinkFile() throws Exception {
    setRandomSeed(1051);
    ByteBuffer sessionId = createSession();
    ObjectID oid = createFile(sessionId, "unlinkTestFile", genRandomBuffer(4096));
    assertNotNull(oid);

    KurmaResult kr = service.lookup(sessionId, getImplicitRootOid(), "unlinkTestFile");
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertEquals(4096, kr.getNew_attrs().getFilesize());

    kr = service.unlink(sessionId, getImplicitRootOid(), "unlinkTestFile");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.lookup(sessionId, getImplicitRootOid(), "unlinkTestFile");
    assertEquals(KurmaError.OBJECT_NOT_FOUND.getValue(), kr.getStatus().getErrcode());

    assertEquals(OperationType.UNLINK_FILE, recorder.popMessage().op_type);
  }

  @Test
  public void testGetattrAfterUnlink() throws Exception {
    setRandomSeed(1051);
    ByteBuffer sessionId = createSession();
    ObjectID oid = createFile(sessionId, "getattrAfterUnlink", genRandomBuffer(4096));

    KurmaResult kr = service.lookup(sessionId, getImplicitRootOid(), "getattrAfterUnlink");
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertEquals(4096, kr.getNew_attrs().getFilesize());

    kr = service.unlink(sessionId, getImplicitRootOid(), "getattrAfterUnlink");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.getattrs(sessionId, oid);
    assertEquals(KurmaError.OBJECT_NOT_FOUND.getValue(), kr.getStatus().getErrcode());

    assertEquals(OperationType.UNLINK_FILE, recorder.popMessage().op_type);
  }

  @Test
  public void testShrinkFile() throws Exception {
    setRandomSeed(1061);
    ByteBuffer sessionId = createSession();
    ObjectID oid = createFile(sessionId, "shrinkTestFile", genRandomBuffer(4096));
    assertNotNull(oid);

    ObjectAttributes attrs = new ObjectAttributes();
    attrs.setFilesize(0);
    KurmaResult kr = service.setattrs(sessionId, oid, attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertEquals(0, kr.getNew_attrs().getFilesize());

    assertEquals(OperationType.SET_ATTRIBUTES, recorder.popMessage().op_type);
  }

  @Test
  public void testListDir() throws Exception {
    setRandomSeed(1063);
    ByteBuffer sessionId = createSession();
    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "listTestDir",
        AttributesHelper.newDirAttributes());
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID dirOid = kr.getOid();

    assertEquals(OperationType.CREATE_DIR, recorder.popMessage().op_type);

    ByteBuffer data = genRandomBuffer(4096);
    createFile(sessionId, dirOid, "file1", data);
    createFile(sessionId, dirOid, "file2", data);
    createFile(sessionId, dirOid, "file3", data);
    service.mkdir(sessionId, dirOid, "dir1", AttributesHelper.newDirAttributes());
    service.mkdir(sessionId, dirOid, "dir2", AttributesHelper.newDirAttributes());

    kr = service.listdir(sessionId, dirOid);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertTrue(kr.isSetDir_data());
    assertEquals(5, kr.dir_data.size());
    List<String> names = kr.dir_data.stream().map(de -> de.getName()).collect(Collectors.toList());
    Collections.sort(names);
    assertEquals(names, Lists.newArrayList("dir1", "dir2", "file1", "file2", "file3"));

    assertEquals(OperationType.CREATE_DIR, recorder.popMessage().op_type);
    assertEquals(OperationType.CREATE_DIR, recorder.popMessage().op_type);
  }

  @Test
  public void testUnlinkDirectory() throws Exception {
    setRandomSeed(1069);
    ByteBuffer sessionId = createSession();
    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "unlinkTestDir",
        AttributesHelper.newDirAttributes());
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.unlink(sessionId, getImplicitRootOid(), "unlinkTestDir");
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.lookup(sessionId, getImplicitRootOid(), "unlinkTestDir");
    assertEquals(KurmaError.OBJECT_NOT_FOUND.getValue(), kr.getStatus().getErrcode());

    assertEquals(OperationType.CREATE_DIR, recorder.popMessage().op_type);
    assertEquals(OperationType.REMOVE_DIR, recorder.popMessage().op_type);
  }

  @Test
  public void testReadWriteFileHoles() throws Exception {
    final ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    int mode1 = 0b0110100000;
    attrs.setMode(mode1);
    KurmaResult kr1 = service.create(sessionId, getImplicitRootOid(), "testReadHole", attrs);
    assertTrue(StatusHelper.isOk(kr1.getStatus()));
    assertEquals(mode1, kr1.getNew_attrs().getMode());

    final ObjectID oid = kr1.getOid();
    // change of attributes should not affect the attrs of the created file
    attrs = new ObjectAttributes();
    attrs.setFilesize(65536);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(65536L, kr1.getNew_attrs().getFilesize());

    attrs.setFilesize(0);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(0, kr1.getNew_attrs().getFilesize());

    attrs.setFilesize(65536);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(65536L, kr1.getNew_attrs().getFilesize());

    kr1 = service.open(sessionId, oid, OpenFlags.WRITE);
    assertTrue(StatusHelper.isOk(kr1.getStatus()));

    KurmaResult kr = service.read(sessionId, oid, 0, 65536);
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.write(sessionId, oid, 65536, genRandomBuffer(65536));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.read(sessionId, oid, 0, 65536); // read hole
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    // Write hole and there should be not garbage collection.
    kr = service.write(sessionId, oid, 0, genRandomBuffer(65536));
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    kr = service.close(sessionId, oid);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
  }

  @Test
  public void testXFSTest075() throws Exception {
    final ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    int mode1 = 0b0110100000;
    attrs.setMode(mode1);
    KurmaResult kr1 = service.create(sessionId, getImplicitRootOid(), "testXFSTest075", attrs);
    assertTrue(StatusHelper.isOk(kr1.getStatus()));
    assertEquals(mode1, kr1.getNew_attrs().getMode());

    final ObjectID oid = kr1.getOid();
    // change of attributes should not affect the attrs of the created file
    attrs = new ObjectAttributes();
    attrs.setFilesize(0);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(0L, kr1.getNew_attrs().getFilesize());

    kr1 = service.open(sessionId, oid, OpenFlags.READ);
    assertTrue(StatusHelper.isOk(kr1.getStatus()));

    attrs.setFilesize(100000);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(100000L, kr1.getNew_attrs().getFilesize());

    attrs.setFilesize(0);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(0L, kr1.getNew_attrs().getFilesize());

    attrs.setFilesize(248738);
    kr1 = service.setattrs(sessionId, oid, attrs);
    assertEquals(248738L, kr1.getNew_attrs().getFilesize());
    LOGGER.info("file block size: {}", kr1.getNew_attrs().getBlock_shift());

    assertParallel(3, i -> {
      try {
        KurmaResult kr = null;
        if (i == 0) {
          kr = service.read(sessionId, oid, 0, 262144);
          if (!StatusHelper.isOk(kr.getStatus())) {
            LOGGER.error("Thread-1 failed: {}", kr.getStatus().errmsg);
            return false;
          }
        } else if (i == 1) {
          /*
           * kr = service.close(sessionId, oid); if (!StatusHelper.isOk(kr.getStatus())) {
           * LOGGER.error("Thread-2 failed: {}", kr.getStatus().errmsg); return false; }
           *
           * kr = service.open(sessionId, oid, OpenFlags.WRITE); if
           * (!StatusHelper.isOk(kr.getStatus())) { LOGGER.error("Thread-2 failed: {}",
           * kr.getStatus().errmsg); return false; }
           */

          kr = service.read(sessionId, oid, 131072, 65536);
          if (!StatusHelper.isOk(kr.getStatus())) {
            LOGGER.error("Thread-2 failed: {}", kr.getStatus().errmsg);
            return false;
          }

          kr = service.read(sessionId, oid, 196608, 65536);
          if (!StatusHelper.isOk(kr.getStatus())) {
            LOGGER.error("Thread-2 failed: {}", kr.getStatus().errmsg);
            return false;
          }

          kr = service.write(sessionId, oid, 131072, genRandomBuffer(117666));
          if (!StatusHelper.isOk(kr.getStatus())) {
            LOGGER.error("Thread-2 failed: {}", kr.getStatus().errmsg);
            return false;
          }
        } else if (i == 2) {
          kr = service.read(sessionId, oid, 0, 65536);
          if (!StatusHelper.isOk(kr.getStatus())) {
            LOGGER.error("Thread-3 failed: {}", kr.getStatus().errmsg);
            return false;
          }
        }
      } catch (Exception e) {
        LOGGER.error("XFS Test 075 Failed", e);
        return false;
      }
      return true;
    });
  }

  @Test
  public void testTakeAndListSnapshots() throws Exception {
    ByteBuffer sessionId = createSession();
    ObjectID oid = createFile(sessionId, getImplicitRootOid(), "srcName", genRandomBuffer(4096));
    assertNotNull(oid);

    KurmaResult kr = service.take_snapshot(sessionId, oid, "snapshot-1", null);
    assertTrue(kr.isSetSnapshot_id());
    int snapshotId = kr.getSnapshot_id();
    assertTrue(snapshotId != 0);

    kr = service.list_snapshots(sessionId, oid);
    assertEquals(1, kr.getDir_data().size());
    assertEquals("snapshot-1", kr.getDir_data().get(0).name);

    kr = service.lookup_snapshot(sessionId, oid, "snapshot-1", 0);
    assertEquals(snapshotId, kr.getSnapshot_id());
    assertNotNull(kr.getNew_attrs());
    assertEquals(4096, kr.getNew_attrs().getFilesize());
  }

  @Test
  public void testRestoreSnapshot() throws Exception {
    ByteBuffer sessionId = createSession();
    ObjectID oid =
        createFile(sessionId, getImplicitRootOid(), "restoreTestFile", genRandomBuffer(4096));
    assertNotNull(oid);

    KurmaResult kr = service.take_snapshot(sessionId, oid, "snapshot-1", null);
    assertTrue(kr.isSetSnapshot_id());
    int snapshotId = kr.getSnapshot_id();
    assertTrue(snapshotId != 0);

    kr = service.open(sessionId, oid, OpenFlags.WRITE);
    kr = service.write(sessionId, oid, 0, genRandomBuffer(8192));
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertEquals(8192, kr.getNew_attrs().getFilesize());
    kr = service.close(sessionId, oid);

    kr = service.restore_snapshot(sessionId, oid, "snapshot-1", 0);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    assertEquals(4096, kr.getNew_attrs().getFilesize());
  }

  @Test
  public void testConcurrentMkdirOperation() throws Exception {
    final int N = 5;
    final CountDownLatch startSignal = new CountDownLatch(1);
    for (int i = 0; i < N; ++i) {
      final int n = i;
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            startSignal.await();
            ByteBuffer sessionId = createSession();
            KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "ROOT_" + n,
                AttributesHelper.newDirAttributes());
            assertTrue(StatusHelper.isOk(kr.getStatus()));
          } catch (Exception e) {
            e.printStackTrace();
          }
        };
      };
      t.start();
    }
    startSignal.countDown();
  }

  @Test
  public void testConcurrentRenameOperation() throws Exception {
    final int N = 5;
    final CountDownLatch startSignal = new CountDownLatch(1);
    ByteBuffer sessionId = createSession();
    for (int i = 0; i < N; i++) {
      KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "ROOT_" + i,
          AttributesHelper.newDirAttributes());
      assertTrue(StatusHelper.isOk(kr.getStatus()));
    }
    AtomicBoolean failed = new AtomicBoolean();
    List<Thread> threads = new ArrayList<>(N);
    for (int i = 0; i < N; ++i) {
      final int n = i;
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            startSignal.await();
            KurmaResult kr = service.rename(sessionId, getImplicitRootOid(), "ROOT_" + n,
                getImplicitRootOid(), n + "_ROOT");
            if (!StatusHelper.isOk(kr.getStatus())) {
              failed.set(true);
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        };
      };
      t.start();
      threads.add(t);
    }
    startSignal.countDown();
    for (int i = 0; i < N; ++i) {
      threads.get(i).join();
    }
    assertFalse(failed.get());
  }

  @Test
  public void testConcurrentAddChildFileOperation() throws Exception {
    final int N = 40;
    final CountDownLatch startSignal = new CountDownLatch(1);
    ByteBuffer sessionId = createSession();
    List<Thread> threads = new ArrayList<>(N);

    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "ROOT_DIR",
        AttributesHelper.newDirAttributes());
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID rootOid = kr.getOid();
    AtomicInteger count = new AtomicInteger();
    for (int i = 0; i < N; ++i) {
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            startSignal.await();
            KurmaResult kr =
                service.create(sessionId, rootOid, "ROOT_", AttributesHelper.newFileAttributes());
            if (StatusHelper.isOk(kr.getStatus())) {
              count.incrementAndGet();
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        };
      };
      t.start();
      threads.add(t);
    }
    startSignal.countDown();
    for (int i = 0; i < N; ++i) {
      threads.get(i).join();
    }
    assertEquals(count.get(), 1);
  }

  @Test
  public void testConcurrentRemoveChildOperation() throws Exception {
    final int N = 40;
    final CountDownLatch startSignal = new CountDownLatch(1);
    ByteBuffer sessionId = createSession();
    List<Thread> threads = new ArrayList<>(N);

    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(), "ROOT_DIR_TO_REMOVE",
        AttributesHelper.newDirAttributes());
    assertTrue(StatusHelper.isOk(kr.getStatus()));

    AtomicInteger count = new AtomicInteger();
    for (int i = 0; i < N; ++i) {
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            startSignal.await();
            KurmaResult kr = service.unlink(sessionId, getImplicitRootOid(), "ROOT_DIR_TO_REMOVE");
            if (StatusHelper.isOk(kr.getStatus())) {
              count.incrementAndGet();
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        };
      };
      t.start();
      threads.add(t);
    }
    startSignal.countDown();
    for (int i = 0; i < N; ++i) {
      threads.get(i).join();
    }
    assertEquals(count.get(), 1);
  }

  @Test
  public void testGetDynamicInfo() throws Exception {
    ByteBuffer sessionId = createSession();
    final int filesize = (1 << 20);
    DynamicInfo oldInfo = service.get_dynamic_info(sessionId).getDynamic_info();
    createFile(sessionId, "testGetVolumeInfo", ByteBuffer.allocate(filesize));
    DynamicInfo dinfo = service.get_dynamic_info(sessionId).getDynamic_info();
    assertTrue(dinfo.getBytes() >= filesize);
    assertTrue(dinfo.getFiles() == (1 + oldInfo.getFiles()));
  }

  @Test
  public void testRenameToAnNonEmptyDirectory() throws Exception {
    ByteBuffer sessionId = createSession();
    KurmaResult kr = service.mkdir(sessionId, getImplicitRootOid(),
        "testRenameToAnNonEmptyDirectory", AttributesHelper.newDirAttributes());
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID dirOid = kr.getOid();

    kr = service.mkdir(sessionId, dirOid, "dir1", AttributesHelper.newDirAttributes());
    kr = service.mkdir(sessionId, dirOid, "dir2", AttributesHelper.newDirAttributes());
    kr = service.create(sessionId, kr.getOid(), "file", AttributesHelper.newFileAttributes());

    kr = service.rename(sessionId, dirOid, "dir1", dirOid, "dir2");
    assertEquals(kr.getStatus().errcode, KurmaError.DIRECTORY_NOT_EMPTY.getValue());
  }

  @Test
  public void testChangeTimeIsUpdatedWhenSettingAttributes() throws Exception {
    ByteBuffer sessionId = createSession();
    ObjectAttributes attrs = AttributesHelper.newFileAttributes();
    KurmaResult kr = service.create(sessionId, getImplicitRootOid(),
        "testUpdateTimestampIsCorrectlyMaintained", attrs);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    ObjectID oid = kr.getOid();
    long changeTime1 = kr.getNew_attrs().getChange_time();

    Thread.sleep(10);
    attrs.setModify_time(System.currentTimeMillis());
    kr = service.setattrs(sessionId, oid, attrs);
    long changeTime2 = kr.getNew_attrs().getChange_time();
    assertNotEquals(changeTime1, changeTime2);

    attrs = new ObjectAttributes();
    attrs.setAccess_time(System.currentTimeMillis());
    kr = service.setattrs(sessionId, oid, attrs);
    long changeTime3 = kr.getNew_attrs().getChange_time();
    assertNotEquals(changeTime2, changeTime3);
  }

  // ADD TEST CASE FOR RENAME AND DELETE CONCURRENT OP
}
