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

import java.io.IOException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.util.AuthenticatedEncryption;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;

/*
 * This test class 'TestKurmaGC' makes sure that GC code actually collects files, directories,
 * blocks and Znodes unlike 'TestKurmaDummyGC' which just test for collections without removing it.
 */

@RunWith(MockitoJUnitRunner.class)
public class TestKurmaGC extends TestBase {

  KeyGenerator keyGenerator;
  SecretKey fileKey;
  KeyMap keymap;
  AuthenticatedEncryption aeCipher;

  @Before
  public void setUp() throws Exception {
    startTestServer(DEFAULT_GARBAGE_COLLECTOR);
    keyGenerator = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    keyGenerator.init(128);
    fileKey = keyGenerator.generateKey();
    keymap = KeyMapHelper.newKeyMap(fileKey, config);
    aeCipher = new EncryptThenAuthenticate(fileKey);
  }

  @After
  public void finish() throws IOException {
    closeTestServer();
  }

  @Test
  public void testBlockCollector() {
    // TODO Find a way to test this
  }

  @Test
  public void testDirectoryCollector() throws Exception {
    // Create setup for this test case
    ObjectID oid = vh.newDirectoryOid();
    ObjectID oidNew = vh.newDirectoryOid();
    ObjectAttributes attrs = AttributesHelper.newDirAttributes();
    String parentDirectoryName = "testParentDirectory";
    String childDirectoryName = "testChildGCDirectory";

    // Spy on DirectoryHandler and fail it on specific point
    DirectoryHandler dhSpy = Mockito.spy(new DirectoryHandler(oid, vh));
    KurmaTransaction txn = zkClient.newTransaction();
    dhSpy.create(rootDh.getOid(), parentDirectoryName, attrs, txn);
    zkClient.submitTransaction(txn);
    rootDh.addChild(parentDirectoryName, oid);

    Mockito.doReturn(false).when(dhSpy)._addChild(
        Mockito.same(childDirectoryName),
        Mockito.same(oidNew),
        Mockito.isNotNull());

    // Test for directory GC when it fails to addChild inside parent
    // directory
    assertNull(dhSpy.createChildDirectory(oidNew, childDirectoryName, attrs, true));
    String zpath = vh.getObjectZpath(oidNew);
    Thread.sleep(2000);
    assertNull(client.checkExists().forPath(zpath));

    // Test for directory GC when it is actually removed
    rootDh.removeChild(parentDirectoryName);
    Thread.sleep(2000);
    assertNull(client.checkExists().forPath(dhSpy.getZpath()));
  }

  @Test
  public void testFileCollector() throws Exception {
    // Create setup for this test case
    ObjectID oidFileOne = vh.newFileOid();
    ObjectID oidFileTwo = vh.newFileOid();
    ObjectAttributes attrsFile = AttributesHelper.newFileAttributes();
    ObjectAttributes attrsDir = AttributesHelper.newDirAttributes();
    ObjectID oidDir = vh.newDirectoryOid();
    String testFileNameOne = "testCollectFileOne";
    String testFileNameTwo = "testCollectFileTwo";

    KurmaTransaction txn = zkClient.newTransaction();
    DirectoryHandler dhSpy = Mockito.spy(new DirectoryHandler(oidDir, vh));
    dhSpy.create(rootDh.getOid(), "testDir", attrsDir, txn);
    zkClient.submitTransaction(txn);
    rootDh.addChild("testDir", vh.newDirectoryOid());

    assertNotNull(rootDh.createChildFile(oidFileTwo, testFileNameTwo, attrsFile,
        vh.getFacadeManager().getDefaultFacade(), fileKey, keymap, true));

    // Mock DirectoryHandler to fail while adding child file
    Mockito.doReturn(false).when(dhSpy)._addChild(
        Mockito.same(testFileNameOne),
        Mockito.same(oidFileOne),
        Mockito.isNotNull());

    // Make sure that operation fails as we have mocked its behavior
    assertNull(dhSpy.createChildFile(oidFileOne, testFileNameOne, attrsFile,
        vh.getFacadeManager().getDefaultFacade(), fileKey, keymap, true));

    // Make sure that created file is garbage collected
    Thread.sleep(2000);
    assertFalse(zkClient.checkExists(vh.getObjectZpath(oidFileOne)));

    // Make sure that file is GCed when it is actually removed
    rootDh.removeChild(testFileNameTwo);
    Thread.sleep(2000);
    assertFalse(zkClient.checkExists(vh.getObjectZpath(oidFileTwo)));
  }

  @Test
  public void testZnodeCollector() throws Exception {
    // Create setup for this test case
    ObjectID oid = vh.newFileOid();
    ObjectID oidDir = vh.newDirectoryOid();
    DirectoryHandler dh = new DirectoryHandler(oidDir, vh);
    ObjectAttributes attrsDir = AttributesHelper.newDirAttributes();
    ObjectAttributes attrsFile = AttributesHelper.newFileAttributes();
    String parentDirectory = "testZnodeParent";
    String testFileName = "testZnodeGCFile";

    KurmaTransaction txn = zkClient.newTransaction();
    dh.create(rootDh.getOid(), parentDirectory, attrsDir, txn);
    zkClient.submitTransaction(txn);
    rootDh.addChild(parentDirectory, oid);

    // Defined in 'TestKurmaDummyGC.java'
    TestFileHandler fh = new TestFileHandler(oid, vh);
    // Mock object and throw an exception to get intended behavior
    TWrapper<File> testWrapper = Mockito.spy(new TWrapper<File>(fh.getZpath(), fh.getFile()));
    Mockito.doReturn(false).when(testWrapper).create(Mockito.any(KurmaTransaction.class));
    fh.setWrapperForTest(testWrapper);

    // Make sure that operation failed due to mocked behavior
    txn = zkClient.newTransaction();
    assertEquals(fh.create(dh.getOid(), testFileName, attrsFile, config.getDefaultKvsFacade(),
        fileKey, keymap, txn), false);

    // Make sure that zpaths are GCed
    String path = fh.getZpath();
    Thread.sleep(2000);
    assertNull(client.checkExists().forPath(path));
  }
}
