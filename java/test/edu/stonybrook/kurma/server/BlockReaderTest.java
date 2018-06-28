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
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import javax.crypto.SecretKey;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.cloud.ReplicationFacade;
import edu.stonybrook.kurma.cloud.drivers.TransientKvs;
import edu.stonybrook.kurma.util.AuthenticatedEncryption;
import edu.stonybrook.kurma.util.CryptoUtils;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;

public class BlockReaderTest extends TestBase {
  private SecretKey secretKey;
  private KvsFacade kvsFacade;
  private AuthenticatedEncryption aeCipher;
  private FileHandler fileHandler;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  @Before
  public void setUp() throws Exception {
    List<Kvs> kvs = new ArrayList<>();
    kvs.add(new TransientKvs("id"));
    ReplicationFacade facade = new ReplicationFacade(kvs, Integer.MAX_VALUE);
    kvsFacade = facade;
    secretKey = CryptoUtils.generateAesKey();
    aeCipher = new EncryptThenAuthenticate(secretKey);

    fileHandler = createFileUnderRoot("BlockReaderTestFile");
  }

  @Test
  public void testBasics() throws Exception {
    final int BLOCKSIZE = 64 * 1024;
    for (int i = 1; i <= 100; ++i) {
      ByteBuffer dataIn = genRandomBuffer(BLOCKSIZE);
      FileBlock fbIn =
          new FileBlock(fileHandler, 0, BLOCKSIZE, i, config.getGatewayId(), dataIn, true);
      BlockWriter writer = new BlockWriter(fbIn, aeCipher, kvsFacade);
      Entry<Boolean, FileBlock> writeRes = writer.call();
      assertTrue(writeRes.getKey());

      ByteBuffer dataOut = ByteBuffer.allocate(BLOCKSIZE);
      FileBlock fbOut =
          new FileBlock(fileHandler, 0, BLOCKSIZE, i, config.getGatewayId(), dataOut, false);
      BlockReader reader = new BlockReader(fbOut, aeCipher, kvsFacade);
      Entry<Boolean, FileBlock> readRes = reader.call();
      assertTrue(readRes.getKey());
      dataIn.rewind();
      dataOut.rewind();
      assertEquals(dataIn, dataOut);
    }
  }

}
