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

import static edu.stonybrook.kurma.TestBase.assertParallel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import secretsharing.CDCodecJNI;
import edu.stonybrook.kurma.util.RandomBuffer;

public class SecretSharingCodecTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(SecretSharingCodecTest.class);

  @Before
  public void setUp() throws Exception {}

  @Test
  public void testThreadedEncodeAndDecoding() throws Exception {
    final int nworkers = 8;
    final int n = 4;
    final int m = 1;
    final SecretSharingCodec codec = SecretSharingCodec.getCodec(CDCodecJNI.AONT_RS, n, m, 2);
    // We schedule twice the number of workers to exercise it.
    assertParallel(nworkers * 2, i -> {
      RandomBuffer rand = new RandomBuffer();
      int secretSize = 1024 * 1024;
      byte[] secret = rand.genRandomBytes(secretSize);
      int myShareSize = codec.getShareSize(secretSize);
      byte[] shares = new byte[(n + m) * myShareSize];
      int shareSize = codec.encode(secret, secretSize, shares);
      if (shareSize != myShareSize) {
        LOGGER.error("share size mismatch {} vs. {}", shareSize, myShareSize);
        return false;
      }

      int[] erasures = new int[n];
      for (int j = 0; j < n; ++j) {
        erasures[j] = j;
      }

      byte[] secret2 = new byte[secretSize];
      if (!codec.decode(shares, shareSize, erasures, secret2, secretSize)) {
        LOGGER.error("debuging of task-{} failed", i);
        return false;
      }
      return Arrays.equals(secret, secret2);
    });
  }

  @Test
  public void testDifferentBufferSize() {
    boolean[] aligns = new boolean[] {true, false};
    int[] sizes = new int[] {4096, 65535, 104836};
    RandomBuffer rand = new RandomBuffer();
    SecretSharingCodec codec = SecretSharingCodec.getCodec(CDCodecJNI.AONT_RS, 4, 1, 2);
    int[] erasures = new int[] {0, 1, 2, 3};
    for (boolean align : aligns) {
      for (int secretSize : sizes) {
        LOGGER.info("testing secret size: {} Align: {}", secretSize, align);
        int secretBufSize = align ? codec.getAlignedSecretSize(secretSize) : secretSize;
        int shareBufSize = codec.getSizeOfAllShares(secretSize);
        byte[] secret = rand.genRandomBytes(secretBufSize);
        byte[] shares = new byte[shareBufSize];
        int shareSize = codec.encode(secret, secretSize, shares);
        assertTrue(shareSize > 0);
        byte[] secret2 = new byte[secretBufSize];
        codec.decode(shares, shareSize, erasures, secret2, secretSize);
        assertEquals(ByteBuffer.wrap(secret, 0, secretSize),
            ByteBuffer.wrap(secret2, 0, secretSize));
      }
    }
  }

}
