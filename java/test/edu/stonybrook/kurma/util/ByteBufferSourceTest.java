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
package edu.stonybrook.kurma.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Test;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.TestBase;

public class ByteBufferSourceTest extends TestBase {

  @Test
  public void testOpenStreamMultipleTimes() throws IOException {
    ByteBuffer garbage = ByteBuffer.wrap(genRandomBytes(32));
    ByteBuffer source = ByteBuffer.wrap(genRandomBytes(32));
    assertNotEquals(source, garbage);

    ByteBufferSource bbs = new ByteBufferSource(source);
    ByteBufferOutputStream bos = new ByteBufferOutputStream(32);
    ByteStreams.copy(bbs.openStream(), bos);
    assertEquals(source, bos.getByteBuffer());
    assertNotEquals(garbage, bos.getByteBuffer());

    bos = new ByteBufferOutputStream(32);
    ByteStreams.copy(bbs.openBufferedStream(), bos);
    assertEquals(source, bos.getByteBuffer());
  }

  @Test
  public void testSourceConcat() throws IOException {
    byte[] data = genRandomBytes(64);
    byte[] exchanged = new byte[64]; // first and second half exchanged
    System.arraycopy(data, 0, exchanged, 0, 32);
    System.arraycopy(data, 32, exchanged, 32, 32);

    ByteBufferSource bbs1 = new ByteBufferSource(ByteBuffer.wrap(data, 0, 32));
    ByteBufferSource bbs2 = new ByteBufferSource(ByteBuffer.wrap(data, 32, 32));
    ByteSource bs = ByteSource.concat(bbs2, bbs1);
    assertEquals(32 + 32, bs.size());
    assertFalse(bs.contentEquals(new ByteBufferSource(ByteBuffer.wrap(data))));
    assertFalse(bs.contentEquals(new ByteBufferSource(ByteBuffer.wrap(exchanged))));
  }

  @Test
  public void testOpenStream() throws IOException {
    for (int size : Arrays.asList(4096, 100, 1000, 65536)) {
      byte[] data = genRandomBytes(size);
      ByteBufferSource bbs = new ByteBufferSource(ByteBuffer.wrap(data));
      byte[] data2 = new byte[size];
      ByteStreams.read(bbs.openStream(), data2, 0, data2.length);
      assertTrue(Arrays.equals(data, data2));
    }
  }

  @Test
  public void testConcatOpenStream() throws IOException {
    ByteBuffer sizeBuf = ByteBuffer.allocate(4);
    sizeBuf.mark();
    sizeBuf.putInt(4096);
    sizeBuf.reset();
    ByteBuffer dataBuf = genRandomBuffer(4096);
    ByteBufferSource bbs1 = new ByteBufferSource(sizeBuf);
    ByteBufferSource bbs2 = new ByteBufferSource(dataBuf);
    ByteSource bs = ByteSource.concat(bbs1, bbs2);
    assertEquals(4 + 4096, bs.size());
    byte[] res1 = bs.read();
    byte[] res2 = new byte[4 + 4096];
    InputStream ins = bs.openStream();
    int n = ByteStreams.read(ins, res2, 0, 4 + 4096);
    assertEquals(4 + 4096, n);
    assertTrue(Arrays.equals(res1, res2));
  }

  @Test
  public void testPrefixBuffer() throws IOException {
    byte[] buf1 = genRandomBytes(4000);
    ByteSource bs = ByteBufferSource.getPrefixedByteSource(buf1.length, ByteBuffer.wrap(buf1));
    byte[] buf2 = ByteBufferSource.readPrefixedBuffer(bs.openStream()).array();
    assertTrue(Arrays.equals(buf1, buf2));
  }
}
