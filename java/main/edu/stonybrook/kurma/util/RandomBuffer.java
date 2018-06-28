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
import java.util.Random;

public class RandomBuffer {
  public static long DEFAULT_RANDOM_SEED = 8887;
  private Random random;

  public RandomBuffer() {
    this(DEFAULT_RANDOM_SEED);
  }

  public RandomBuffer(long seed) {
    random = new Random(seed);
  }

  public void setRandomSeed(long seed) {
    random.setSeed(seed);
  }

  public byte[] genRandomBytes(int len) {
    byte[] data = new byte[len];
    random.nextBytes(data);
    return data;
  }

  public ByteBuffer genRandomBuffer(int len) {
    ByteBuffer buf = ByteBuffer.allocate(len);
    random.nextBytes(buf.array());
    return buf;
  }
}
