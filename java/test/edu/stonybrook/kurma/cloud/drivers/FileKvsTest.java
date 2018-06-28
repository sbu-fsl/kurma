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

public class FileKvsTest extends TestBase {
  protected FileKvs kvs;

  @Before
  public void setUp() {
    kvs = new FileKvs("filekvs");
  }

  protected ByteBuffer readAsByteBuffer(String key, int size) throws Exception {
    InputStream in = kvs.get(key);
    ByteBufferOutputStream out = new ByteBufferOutputStream(size);
    ByteStreams.copy(in, out);
    return out.getByteBuffer();
  }

  @Test
  public void testBasics() throws Exception {
    for (int n = 1; n <= 1024; ++n) {
      byte[] data = genRandomBytes(n);
      String fileName = "hi";
      kvs.put(fileName, new ByteArrayInputStream(data), n);
      ByteBuffer value = readAsByteBuffer(fileName, n);
      assertEquals(value, ByteBuffer.wrap(data));
    }
  }

  @Test
  public void testMultipleReads() throws Exception {
    for (int n = 1; n <= 1024; ++n) {
      byte[] data = genRandomBytes(n);
      String fileName = "File_Name New";
      kvs.put(fileName, new ByteArrayInputStream(data), n);
      ByteBuffer value1 = readAsByteBuffer(fileName, n);
      ByteBuffer value2 = readAsByteBuffer(fileName, n);
      assertEquals(value1, value2);
    }
  }

  @Test
  public void testGetAfterDelete() throws Exception {
    for (int n = 1; n <= 1024; ++n) {
      byte[] aaa = genRandomBytes(n);
      byte[] bbb = genRandomBytes(n);
      String firstFileName = "File_Name?First";
      String secondFileName = "File_Name\\?@#Second";
      kvs.put(firstFileName, new ByteArrayInputStream(aaa), n);
      kvs.put(secondFileName, new ByteArrayInputStream(bbb), n);
      kvs.delete(firstFileName);
      assertNull(kvs.get(firstFileName));
      assertNotNull(kvs.get(secondFileName));
    }
  }

  @Test
  public void testListOpeartion() throws Exception {
    byte[] aaa = genRandomBytes(32);
    List<String> fileNames = kvs.list();
    String newFileName = "FileNameForList";
    kvs.put(newFileName, new ByteArrayInputStream(aaa), 32);
    assertFalse(Arrays.equals(fileNames.toArray(), (kvs.list()).toArray()));
    kvs.delete(newFileName);
    assertTrue(Arrays.equals(fileNames.toArray(), (kvs.list()).toArray()));
  }
}
