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
package edu.stonybrook.kurma.cloud.drivers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.util.ByteBufferOutputStream;

public class TransientKvsTest extends TestBase {
  protected TransientKvs kvs;

  @Before
  public void setUp() {
    kvs = new TransientKvs("testBasics");
  }

  protected ByteBuffer readAsByteBuffer(String key, int size) throws Exception {
    InputStream in = kvs.get(key);
    ByteBufferOutputStream out = new ByteBufferOutputStream(size);
    ByteStreams.copy(in, out);
    return out.getByteBuffer();
  }

  @Test
  public void testBasics() throws Exception {
    byte[] data = genRandomBytes(32);
    kvs.put("aaa", new ByteArrayInputStream(data), 32);
    ByteBuffer value = readAsByteBuffer("aaa", 32);
    assertEquals(value, ByteBuffer.wrap(data));
  }

  @Test
  public void testMultipleReads() throws Exception {
    byte[] data = genRandomBytes(32);
    kvs.put("aaa", new ByteArrayInputStream(data), 32);
    ByteBuffer value1 = readAsByteBuffer("aaa", 32);
    ByteBuffer value2 = readAsByteBuffer("aaa", 32);
    assertEquals(value1, value2);
  }

  @Test
  public void testGetAfterDelete() throws Exception {
    byte[] aaa = genRandomBytes(32);
    byte[] bbb = genRandomBytes(32);
    kvs.put("aaa", new ByteArrayInputStream(aaa), 32);
    kvs.put("bbb", new ByteArrayInputStream(bbb), 32);
    kvs.delete("aaa");
    assertNull(kvs.get("aaa"));
    assertNotNull(kvs.get("bbb"));
  }

  @Test
  public void testListOpeartion() throws Exception {
    byte[] aaa = genRandomBytes(32);
    List<String> keys = kvs.list();
    kvs.put("keyForList", new ByteArrayInputStream(aaa), 32);
    assertFalse(Arrays.equals(keys.toArray(), (kvs.list()).toArray()));
    kvs.delete("keyForList");
    assertTrue(Arrays.equals(keys.toArray(), (kvs.list()).toArray()));
  }
}
