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
import java.nio.charset.Charset;

import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;

import org.slf4j.Logger;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;

public class LoggingUtils {
  private static XXHash32 xxhashFn = XXHashFactory.nativeInstance().hash32();

  /**
   * A util of conditional debugging. It can saves calls to toString() methods of the arguments.
   *
   * Not that it is not helpful if the arguments themselves are expensive to evaluate.
   *
   * @param logger
   * @param format
   * @param arguments
   */
  static public void debugIf(Logger logger, String format, Object... arguments) {
    if (logger.isDebugEnabled()) {
      logger.debug(format, arguments);
    }
  }

  static public String hash(byte[] buf) {
    if (buf == null) {
      return "null";
    }
    return String.format("%08x", xxhashFn.hash(buf, 0, buf.length, 0));
  }

  static public String hash(ByteBuffer buf) {
    return String.format("%08x", xxhashFn.hash(buf.array(), buf.position(), buf.remaining(), 0));
  }

  static public String hash(ByteSource bs) {
    try {
      return hash(bs.read());
    } catch (IOException e) {
      return String.format("%08x", 0);
    }
  }

  static public String murhash(byte[] buf) {
    return Hashing.murmur3_32().hashBytes(buf).toString();
  }

  static public String murhash(ByteBuffer buf) {
    return Hashing.murmur3_32().hashBytes(buf.array(), buf.position(), buf.remaining()).toString();
  }

  static public String ascii(ByteBuffer buf) {
    return new String(buf.array(), buf.position(), buf.remaining(), Charset.forName("US-ASCII"));
  }

  static public String binary(ByteBuffer buf) {
    byte[] ba = buf.array();
    StringBuilder sb = new StringBuilder(String.format("%d byte: ", buf.remaining()));
    for (int i = buf.position(); i < buf.position() + buf.remaining(); ++i) {
      sb.append(String.format("%02x", (0xFF & ba[i])));
    }
    return sb.toString();
  }
}
