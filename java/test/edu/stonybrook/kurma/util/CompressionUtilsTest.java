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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.DataFormatException;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Before;

public class CompressionUtilsTest {
  private static int RANDOM_SEED = 8889;
  private Random random = null;

  @Before
  public void setUp() throws Exception {
    random = new Random(RANDOM_SEED);
  }

  public byte[] getEncodiedIntegers(int length, int ceil) {
    ByteBuffer data = ByteBuffer.allocate(length * 4);
    for (int i = 0; i < length; ++i) {
      data.putInt(random.nextInt(ceil));
    }
    return data.array();
  }

  @Test
  public void testCorrectness() {
    byte[] original = getEncodiedIntegers(1024, 128);
    byte[] compressed = CompressionUtils.compress(original);
    byte[] recovered = null;
    try {
      recovered = CompressionUtils.decompress(compressed);
      assertArrayEquals(original, recovered);
    } catch (IOException e) {
      e.printStackTrace();
    } catch (DataFormatException e) {
      e.printStackTrace();
    }
  }

  @Test
  public void testEffectiveness() {
    byte[] original1 = getEncodiedIntegers(1024, 128);
    byte[] compressed1 = CompressionUtils.compress(original1);
    assertTrue("compression reduce size", compressed1.length < original1.length);

    byte[] original2 = getEncodiedIntegers(1024, 1024);
    byte[] compressed2 = CompressionUtils.compress(original2);
    assertTrue("smaller integers are more compressible", compressed1.length < compressed2.length);
  }

}
