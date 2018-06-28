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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.helpers.GatewayHelper;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;

public class BlockKeyGeneratorTest extends TestBase {
  @Test
  public void testBasics() throws Exception {
    short gwid = GatewayHelper.valueOf("ny");
    BlockKeyGenerator generator = new BlockKeyGenerator(
        ObjectIdHelper.newFileOid(Int128Helper.newId(12, 34), gwid), genRandomBytes(16));
    // same offset, version, and gateway should be the same
    assertTrue(Arrays.equals(generator.getBlockKey(0, 1, gwid), generator.getBlockKey(0, 1, gwid)));

    // different offset, verion, or gateway should differ
    assertFalse(
        Arrays.equals(generator.getBlockKey(0, 1, gwid), generator.getBlockKey(8192, 1, gwid)));
    assertFalse(
        Arrays.equals(generator.getBlockKey(0, 1, gwid), generator.getBlockKey(0, 2, gwid)));
    assertFalse(Arrays.equals(generator.getBlockKey(0, 1, gwid),
        generator.getBlockKey(0, 1, (short) (gwid + 1))));
  }

  @Test
  public void testKeyRandomness() throws Exception {
    short gwid = GatewayHelper.valueOf("ny");
    BlockKeyGenerator generator = new BlockKeyGenerator(
        ObjectIdHelper.newFileOid(Int128Helper.newId(12, 34), gwid), genRandomBytes(16));
    for (int offset = 0; offset <= 10; ++offset) {
      byte[] key = generator.getBlockKey(offset, 1, gwid);
      StringBuilder sb = new StringBuilder();
      for (byte b : key) {
        sb.append(String.format("%02x", b));
      }
      System.out.println(sb.toString());
    }
  }
}
