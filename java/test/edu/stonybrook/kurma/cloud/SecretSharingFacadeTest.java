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
package edu.stonybrook.kurma.cloud;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import secretsharing.CDCodecJNI;
import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.drivers.FileKvs;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.server.FileHandler;
import edu.stonybrook.kurma.util.RandomBuffer;

public class SecretSharingFacadeTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(SecretSharingFacadeTest.class);

  @BeforeClass
  public static void setUpClass() throws Exception {
    startTestServerWithConfig("secretsharing-test.properties");
  }

  @Test
  public void testSecretSharingBasics() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    final int n = 4;
    final int m = 1;
    List<Kvs> kvsList = new ArrayList<>();
    for (int i = 0; i < n; ++i) {
      kvsList.add(new FileKvs("testBasics" + i, "FileStore" + i, true, 1));
    }
    SecretSharingFacade facade =
        new SecretSharingFacade(CDCodecJNI.AONT_RS, n, m, 2, kvsList, Integer.MAX_VALUE);
    RandomBuffer rand = new RandomBuffer();
    byte[] secret1 = rand.genRandomBytes(1024 * 1024);
    facade.put("key1", ByteBuffer.wrap(secret1));
    ByteBuffer value = facade.get("key1", (k, v) -> {
      return true;
    });
    assertEquals(value, ByteBuffer.wrap(secret1));
  }

  @Test
  public void testWriteFilesWithSecretSharing() throws Exception {
    FileHandler fh = createFileUnderRoot("secretsharingfile");
    final int N = 1024 * 1024;
    byte[] data = genRandomBytes(N);
    fh.write(0, ByteBuffer.wrap(data));
    Entry<ByteBuffer, ObjectAttributes> res = fh.read(0, N);
    assertEquals(ByteBuffer.wrap(data), res.getKey());
  }

}
