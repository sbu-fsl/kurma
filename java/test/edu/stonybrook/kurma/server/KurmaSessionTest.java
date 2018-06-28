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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.google.common.hash.Hashing;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.fs.OpenFlags;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.meta.ObjectID;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class KurmaSessionTest extends TestBase {
  private SecureRandom rand = new SecureRandom();

  private byte[] clientId = genRandomBytes(16);
  KurmaSession sess;

  public KurmaSessionTest() throws Exception {
    sess = new KurmaSession(ByteBuffer.wrap(clientId), vh, Hashing.sha1(), rand.nextLong());
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  @Test
  public void testOpenNonExistingFiles() throws Exception {
    ObjectID oid = ObjectIdHelper.newFileOid(Int128Helper.newId(123, 456), config.getGatewayId());
    assertNull(sess.findOpenFile(oid));

    // The file does not have ZK backend data.
    FileHandler fh = sess.openFile(oid, OpenFlags.READ);
    assertNull(fh);
  }

  @Test
  public void testOpenAndClose() throws Exception {
    FileHandler fh =
        rootDh.createChildFile("testOpenAndClose", AttributesHelper.newFileAttributes());
    assertNotNull(fh);
    assertNull(sess.findOpenFile(fh.getOid()));
    FileHandler openFh = sess.openFile(fh.getOid(), OpenFlags.READ);
    assertNotNull(openFh);
    assertEquals(fh.getOid(), openFh.getOid());
    assertTrue(openFh.isLoaded());
    assertEquals(1, openFh.getRefCount());

    assertNotNull(sess.closeFile(openFh.getOid()));
    assertEquals(0, openFh.getRefCount());
  }

  @Test
  public void testMultipleOpenAndClose() throws Exception {
    FileHandler fh =
        rootDh.createChildFile("testMultipleOpenAndClose", AttributesHelper.newFileAttributes());
    ObjectID oid = fh.getOid();
    FileHandler fh1 = sess.openFile(oid, OpenFlags.READ);
    assertEquals(1, fh1.getRefCount());

    // Re-open of a file will fail.
    assertNull(sess.openFile(oid, OpenFlags.READ));

    assertNotNull(sess.closeFile(oid));
    assertEquals(0, fh1.getRefCount());
    assertNull(sess.closeFile(oid));
  }

  @Test
  public void testCloseAllFiles() throws Exception {
    for (int i = 0; i < 10; ++i) {
      FileHandler fh = rootDh.createChildFile(String.format("testCloseAllFiles%d", i),
          AttributesHelper.newFileAttributes());
      sess.openFile(fh.getOid(), OpenFlags.READ);
    }

    assertTrue(sess.closeAllFiles());
    assertEquals(0, sess.countOpenFiles());
  }

  @Test
  public void testClientIdAsPartOfArray() throws Exception {
    final int N = 16;
    byte[] data = new byte[N * 2];
    byte[] data2 = new byte[N];
    for (int i = 0; i < N; ++i) {
      data[i] = data[i + N] = data2[i] = (byte) i;
    }

    long random = rand.nextLong();
    ByteBuffer sid1 =
        new KurmaSession(ByteBuffer.wrap(data, 0, N), vh, Hashing.sha1(), random).getSessionId();
    ByteBuffer sid2 =
        new KurmaSession(ByteBuffer.wrap(data, N, N), vh, Hashing.sha1(), random).getSessionId();
    ByteBuffer sid3 =
        new KurmaSession(ByteBuffer.wrap(data2, 0, N), vh, Hashing.sha1(), random).getSessionId();

    assertEquals(sid1, sid2);
    assertEquals(sid1, sid3);
    assertEquals(sid2, sid3);

    data2[0]++;
    ByteBuffer sid4 =
        new KurmaSession(ByteBuffer.wrap(data2), vh, Hashing.sha1(), random).getSessionId();
    assertNotEquals(sid3, sid4);
  }
}
