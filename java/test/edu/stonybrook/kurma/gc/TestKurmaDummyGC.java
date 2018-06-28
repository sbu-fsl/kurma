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
package edu.stonybrook.kurma.gc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.cloud.drivers.FaultyKvs;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.server.FileHandler;
import edu.stonybrook.kurma.server.VolumeHandler;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.util.AuthenticatedEncryption;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;

/*
 * This test class 'TestKurmaDummyGC' just makes sure that GC code correctly selects blocks, files,
 * directory and znodes for garbage collection. It doesn't check whether these things are actually
 * collected or not.
 */

class TestFileHandler extends FileHandler {
  public TestFileHandler(ObjectID oi, VolumeHandler vh) {
    super(oi, vh);
  }

  public TWrapper<File> getWrapperForTest() {
    return wrapper;
  }

  public void setWrapperForTest(TWrapper<File> newwrapper) {
    wrapper = newwrapper;
  }
}


@RunWith(MockitoJUnitRunner.class)
public class TestKurmaDummyGC extends TestBase {

  KeyGenerator keyGenerator;
  SecretKey fileKey;
  KeyMap keymap;
  AuthenticatedEncryption aeCipher;

  @Before
  public void setUp() {
    try {
      startTestServer(DUMMY_GARBAGE_COLLECTOR);
      keyGenerator = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
      keyGenerator.init(128);
      fileKey = keyGenerator.generateKey();
      keymap = KeyMapHelper.newKeyMap(fileKey, config);
      aeCipher = new EncryptThenAuthenticate(fileKey);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @After
  public void finish() throws IOException {
    closeTestServer();
  }

  @Test
  public void testBlockCollector() throws NoSuchAlgorithmException, NoSuchPaddingException,
      IllegalAccessException, IllegalArgumentException, InvocationTargetException,
      NoSuchMethodException, SecurityException {
    // Don't perform test if test is not for BlockGC
    KvsFacade kvsFacade = config.getDefaultKvsFacade();
    if (!((kvsFacade.getKvsList().get(0)) instanceof FaultyKvs))
      return;

    // Create setup for this test case
    /* Change value in local config in kvs provider if you change this */
    int bufferSize = 64;
    int blockShift = 2;
    int failBlockCount = bufferSize / (blockShift * 4); // fail kvs after
                                                        // exactly half
                                                        // write
    String testFileName = "testCollectBlocks";
    ObjectID oid = vh.newFileOid();
    byte[] data = genRandomBytes(bufferSize);
    ByteBuffer buffer = ByteBuffer.wrap(data);

    // Use file class reflection to change visibility of private method
    FileHandler fh = new FileHandler(oid, vh);
    Class<?> fhClass = fh.getClass();
    Method m = fhClass.getMethod("setBlockShift", new Class<?>[0]);
    m.setAccessible(true);
    m.invoke(blockShift);

    createFileUnderRoot(testFileName);
    // TODO: flush transaction?

    /*
     * test partial write, KVS will fail after half of the blocks are written
     *
     * Operation may succeed or may fail, in both cases we may need to garbage collect blocks It is
     * possible that some block write was failed but that process itself succeeded As we know that
     * it will fail in this case hence following assertion
     */
    assertFalse(fh.write(0, buffer));

    // Making sure that all partial write is reverted
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(),
        failBlockCount);
  }

  @Test
  public void testOverwiteBlocksWithoutSnapshots() throws Exception {
    FileHandler fh = createFileUnderRoot("testOverwiteBlocksWithoutSnapshots");
    final int L = (1 << 20);
    byte[] data1 = genRandomBytes(2 * L);
    byte[] data2 = genRandomBytes(2 * L);

    fh.write(0, ByteBuffer.wrap(data1));
    fh.write(0, ByteBuffer.wrap(data2));
    final int nblocks = 2 * L / fh.getBlockSize();

    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), nblocks);
  }

  @Test
  public void testOverwiteBlocksWithSnapshots() throws Exception {
    FileHandler fh = createFileUnderRoot("testOverwiteBlocksWithoutSnapshots");
    final int L = (1 << 20);
    byte[] data1 = genRandomBytes(2 * L);
    byte[] data2 = genRandomBytes(2 * L);

    fh.write(0, ByteBuffer.wrap(data1));
    FileHandler.SnapshotInfo info = fh.takeSnapshot("Snapshot-1", null);
    long snapshotTime = info.createTime;
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), 0);

    fh.write(0, ByteBuffer.wrap(data2));
    final int nblocks = 2 * L / fh.getBlockSize();
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), 0);

    fh.write(0, ByteBuffer.wrap(data1));
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), nblocks);

    long ss = fh.deleteSnapshot("Snapshot-1").createTime;
    assertEquals(ss, snapshotTime);
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(),
        nblocks * 2);

    assertTrue(fh.delete());
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(),
        nblocks * 3);
  }

  @Test
  public void testSnapshotBlocksAreGCedUponFileDeletion() throws Exception {
    FileHandler fh = createFileUnderRoot("testOverwiteBlocksWithoutSnapshots");
    final int L = (1 << 20);
    byte[] data1 = genRandomBytes(L);
    byte[] data2 = genRandomBytes(L);

    fh.write(0, ByteBuffer.wrap(data1));
    long snapshotTime = fh.takeSnapshot("Snapshot-1", null).createTime;
    assertTrue(snapshotTime != 0);
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), 0);

    fh.write(0, ByteBuffer.wrap(data2));
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), 0);

    assertTrue(fh.delete());
    assertEquals(dummyGarbageCollector.getBlockCollector().getCollectedBlocks().size(), 2);
  }

  @Test
  public void testDummyDirectoryCollector() {
    // Create setup for this test case
    ObjectID oid = vh.newDirectoryOid();
    ObjectID oidNew = vh.newDirectoryOid();
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    String parentDirectoryName = "testParentDirectory";
    String childDirectoryName = "testChildGCDirectory";

    KurmaTransaction txn = zkClient.newTransaction();

    // Spy on DirectoryHandler and fail it on specific point
    DirectoryHandler dhSpy = Mockito.spy(new DirectoryHandler(oid, vh));

    dhSpy.create(rootDh.getOid(), parentDirectoryName, attrs, txn);
    rootDh.addChild(parentDirectoryName, oid);
    zkClient.submitTransaction(txn);

    Mockito.doReturn(false).when(dhSpy)._addChild(
        Mockito.matches(childDirectoryName),
        Mockito.same(oidNew),
        Mockito.isNotNull());

    // Test for directory GC when it fails to addChild inside parent
    // directory
    assertNull(dhSpy.createChildDirectory(oidNew, childDirectoryName, attrs, true));

    // Test for directory GC when it is actually removed
    rootDh.removeChild(parentDirectoryName);
    assertTrue(dummyGarbageCollector.getDirCollector().hasCollected(oid));
  }

  @Test
  public void testDummyFileCollector() {
    // Create setup for this test case
    ObjectID oidFileOne = vh.newFileOid();
    ObjectID oidFileTwo = vh.newFileOid();
    ObjectAttributes attrsFile = AttributesHelper.newFileAttributes();
    ObjectID oidDir = vh.newDirectoryOid();
    String testFileNameOne = "testCollectFileOne";
    String testFileNameTwo = "testCollectFileTwo";

    // Mock DirectoryHandler to fail while adding child file
    DirectoryHandler dhSpy = Mockito.spy(new DirectoryHandler(oidDir, vh));
    Mockito.doReturn(false).when(dhSpy)._addChild(
        Mockito.matches(testFileNameOne),
        Mockito.same(oidFileOne),
        Mockito.isNotNull());

    assertNotNull(rootDh.createChildFile(oidFileTwo, testFileNameTwo, attrsFile,
        vh.getFacadeManager().getDefaultFacade(), fileKey, keymap, true));

    // Make sure that operation fails as we have mocked its behavior
    assertNull(dhSpy.createChildFile(oidFileOne, testFileNameOne, attrsFile,
        vh.getFacadeManager().getDefaultFacade(), fileKey, keymap, true));

    // Make sure that file is GCed when it is actually removed
    rootDh.removeChild(testFileNameTwo);
    assertTrue(dummyGarbageCollector.getFileCollector().hasCollected(oidFileTwo));
  }

  @Test
  public void testDummyZnodeCollector() throws CuratorException {
    // Create setup for this test case
    ObjectID oid = vh.newFileOid();
    ObjectID oidDir = vh.newDirectoryOid();
    DirectoryHandler dh = new DirectoryHandler(oidDir, vh);
    ObjectAttributes attrsDir = AttributesHelper.newDirAttributes();
    ObjectAttributes attrsFile = AttributesHelper.newFileAttributes();
    String parentDirectory = "testZnodeParent";
    String testFileName = "testZnodeGCFile";

    KurmaTransaction txn = vh.getZkClient().newTransaction();
    dh.create(rootDh.getOid(), parentDirectory, attrsDir, txn);
    rootDh.addChild(parentDirectory, oid);

    TestFileHandler fh = new TestFileHandler(oid, vh);
    // Mock object and throw an exception to get intended behavior
    TWrapper<File> testWrapper = Mockito.spy(new TWrapper<File>(fh.getZpath(), fh.getFile()));
    Mockito.doReturn(false).when(testWrapper).create(Mockito.any(KurmaTransaction.class));
    fh.setWrapperForTest(testWrapper);

    // Make sure that file creation failed due to mocked behavior
    assertEquals(fh.create(dh.getOid(), testFileName, attrsFile, config.getDefaultKvsFacade(),
        fileKey, keymap, txn), false);
  }
}
