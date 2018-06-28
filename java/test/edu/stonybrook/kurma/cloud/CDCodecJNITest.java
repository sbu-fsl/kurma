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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import secretsharing.CDCodecJNI;
import edu.stonybrook.kurma.util.RandomBuffer;

public class CDCodecJNITest {
  private RandomBuffer rand = new RandomBuffer();

  @Test
  public void testBasicSecretSharing() {
    CDCodecJNI codecJni = new CDCodecJNI();
    final int n = 4;
    final int m = 1;
    final int k = n - m;
    int cid = codecJni.create(CDCodecJNI.AONT_RS, n, m, 2);
    final int secretSize = 2048 * 1024; // 65536;
    byte[] secret = rand.genRandomBytes(secretSize);
    byte[] shares = new byte[secretSize * 2];
    int shareSize = codecJni.encode(cid, secret, secretSize, shares);
    assertTrue(shareSize > 0);
    assertEquals(shareSize, codecJni.getShareSize(cid, secretSize));

    int[][] allErasures = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3},};

    for (int[] erasures : allErasures) {
      byte[] shares2 = new byte[shareSize * k];
      byte[] secret2 = new byte[secretSize];
      for (int i = 0; i < erasures.length; ++i) {
        System.arraycopy(shares, shareSize * erasures[i], shares2, shareSize * i, shareSize);
      }
      assertTrue(codecJni.decode(cid, shares2, shareSize, erasures, secret2, secretSize));
      assertTrue(Arrays.equals(secret, secret2));

      shares2[0] += (byte) 1;
      assertTrue(codecJni.decode(cid, shares2, shareSize, erasures, secret2, secretSize));
      assertFalse(Arrays.equals(secret, secret2));
    }

    codecJni.destroy(cid);
  }
}
