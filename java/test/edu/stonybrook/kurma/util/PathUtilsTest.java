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

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class PathUtilsTest {

  private RandomBuffer randomBuffer = new RandomBuffer(8887);

  @Test
  public void testPathEncoding() {
    for (int len = 1; len <= 64; ++len) {
      for (int i = 0; i < 10; ++i) {
        byte[] binary = randomBuffer.genRandomBytes(len);
        String path = PathUtils.encodePath(binary);
        assertTrue(path.indexOf('/') == -1);
        byte[] recover = PathUtils.decodePath(path);
        assertTrue(Arrays.equals(binary, recover));
      }
    }
  }

}
