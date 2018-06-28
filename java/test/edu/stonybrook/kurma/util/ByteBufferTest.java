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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Random;

import static org.junit.Assert.*;

import org.junit.Test;

public class ByteBufferTest {

  @Test
  public void testByteBuffer() {
    byte[] data = new byte[16];
    ByteBuffer buf1 = ByteBuffer.wrap(data, 0, 8);
    ByteBuffer buf2 = ByteBuffer.wrap(data, 8, 8);
    assertEquals(0, buf1.arrayOffset());
    assertEquals(0, buf2.arrayOffset());

    // position is an index relative to arrayOffset
    assertEquals(0, buf1.position());
    assertEquals(8, buf2.position());

    assertEquals(8, buf1.remaining());
    assertEquals(8, buf2.remaining());

    // limit is an index relative to arrayOffset
    assertEquals(8, buf1.limit());
    assertEquals(16, buf2.limit());

    // capacity is also an absolute index into the underlying array
    assertEquals(16, buf1.capacity());
    assertEquals(16, buf2.capacity());
  }

  @Test
  public void testByteArray() {
    byte[] data1 = new byte[100];
    byte[] data2 = new byte[100];
    for (byte i = 0; i < 100; ++i) {
      data1[i] = data2[i] = i;
    }
    // Commented out because FindBugs complains hashCode() of object arrays
    // assertNotEquals(data1.hashCode(), data2.hashCode());
    assertEquals(ByteBuffer.wrap(data1), ByteBuffer.wrap(data2));
    assertEquals(ByteBuffer.wrap(data1).hashCode(), ByteBuffer.wrap(data2).hashCode());
    data1[0] = data1[1];
    assertNotEquals(ByteBuffer.wrap(data1), ByteBuffer.wrap(data2));
    assertNotEquals(ByteBuffer.wrap(data1).hashCode(), ByteBuffer.wrap(data2).hashCode());
  }

  @Test
  public void testBufferSlice() {
    byte[] data = new byte[16];
    ByteBuffer buf = ByteBuffer.wrap(data);
    buf.putLong(1234);
    assertEquals(0, buf.arrayOffset());
    assertEquals(8, buf.position());
    assertEquals(8, buf.remaining());
    assertEquals(16, buf.limit());
    assertEquals(16, buf.capacity());

    ByteBuffer slice = buf.slice();
    assert (data == slice.array());
    assertEquals(8, slice.arrayOffset());
    assertEquals(0, slice.position());
    assertEquals(8, slice.remaining());
    assertEquals(8, slice.limit());
    assertEquals(8, slice.capacity());
  }

  @Test
  public void testHashOfByteBuffer() {
    final int N = 16;
    byte[] data = new byte[N * 2];
    byte[] data2 = new byte[N];
    for (int i = 0; i < N; ++i) {
      data[i] = data[N + i] = data2[i] = (byte) i;
    }
    ByteBuffer buf1 = ByteBuffer.wrap(data, 0, N);
    ByteBuffer buf2 = ByteBuffer.wrap(data, N, N);
    ByteBuffer buf3 = ByteBuffer.wrap(data2, 0, N);
    assertFalse(buf1 == buf2);
    assertEquals(buf1, buf2);
    assertEquals(buf1, buf3);
    assertEquals(buf2, buf3);
    HashMap<ByteBuffer, Integer> map = new HashMap<>();
    map.put(buf1, Integer.valueOf(1));
    assertTrue(map.containsKey(buf2));

    // change value of buf2
    data[0] = (byte) (data[0] + 1);
    assertNotEquals(buf1, buf2);
  }

  @Test
  public void testMultipleSources() {
    Random random = new Random(8889);
    byte[] buf1 = new byte[16];
    random.nextBytes(buf1);
    byte[] buf2 = new byte[16];
    random.nextBytes(buf2);

    ByteBuffer buf = ByteBuffer.allocate(32).put(buf1).put(buf2);
    for (int i = 0; buf.hasRemaining(); ++i) {
      if (i < 16) {
        assertEquals(buf1[i], buf.get());
      } else {
        assertEquals(buf2[i - 16], buf.get());
      }
    }
  }

  @Test
  public void testNewlyAllocatedBufferAreZeros() {
    ByteBuffer buf = ByteBuffer.allocate(1024);
    assertTrue(buf.hasArray());
    byte[] array = buf.array();
    for (int i = 0; i < 1024; ++i) {
      assertEquals(0, array[i]);
    }
  }

}
