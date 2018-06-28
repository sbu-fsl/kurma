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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.junit.Test;

public class LoggingUtilsTest {

  @Test
  public void test() {
    byte[] buf = new byte[6];
    // abc012
    buf[0] = 0x61;
    buf[1] = 0x62;
    buf[2] = 0x63;
    buf[3] = 0x30;
    buf[4] = 0x31;
    buf[5] = 0x32;
    assertEquals("abc012", LoggingUtils.ascii(ByteBuffer.wrap(buf)));
    assertEquals("cf398cc3", LoggingUtils.hash(buf));

    // ABCDEF
    buf[0] = 0x41;
    buf[1] = 0x42;
    buf[2] = 0x43;
    buf[3] = 0x44;
    buf[4] = 0x45;
    buf[5] = 0x46;
    assertEquals("ABCDEF", LoggingUtils.ascii(ByteBuffer.wrap(buf)));
    assertEquals("0cbf28ae", LoggingUtils.hash(buf));
  }

}
