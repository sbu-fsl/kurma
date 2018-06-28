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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.util.RandomBuffer;

public class KvsFacadeTest {
  public static void testKvs(KvsFacade kf) throws Exception {
    RandomBuffer rand = new RandomBuffer(8887);
    testBasics(kf, rand);
    testMultipleReads(kf, rand);
    testGetAfterDelete(kf, rand);
  }

  public static void testBasics(KvsFacade kf, RandomBuffer rand) throws IOException {
   ByteBuffer data = ByteBuffer.wrap(rand.genRandomBytes(32));
   kf.put("aaa", data.duplicate());
   ByteBuffer out = kf.get("aaa", null);
   assertEquals(32, out.remaining());
   assertEquals(data, out);
  }

  public static void testMultipleReads(KvsFacade kf, RandomBuffer rand) throws Exception {
    ByteBuffer data = ByteBuffer.wrap(rand.genRandomBytes(32));
    kf.put("aa", data.duplicate());
    ByteBuffer value1 = kf.get("aa", null);
    ByteBuffer value2 = kf.get("aa", null);
    assertEquals(32, value1.remaining());
    assertEquals(32, value2.remaining());
    assertEquals(data, value1);
    assertEquals(value1, value2);
  }

  public static void testGetAfterDelete(KvsFacade kf, RandomBuffer rand) throws Exception {
    ByteBuffer value1 = ByteBuffer.wrap(rand.genRandomBytes(32));
    ByteBuffer value2 = ByteBuffer.wrap(rand.genRandomBytes(32));
    kf.put("aaaa", value1);
    kf.put("bbbb", value2);
    kf.delete("aaaa");
    assertNull(kf.get("aaaa", null));
    ByteBuffer value3 = kf.get("bbbb", null);
    assertNotNull(value3);
    assertTrue(32 == value3.remaining());
    assertTrue(value2.equals(value3));
  }
}
