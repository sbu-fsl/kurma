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
package edu.stonybrook.kurma.helpers;

import java.math.BigInteger;
import java.nio.ByteBuffer;

import edu.stonybrook.kurma.meta.Int128;

public class Int128Helper {
  // Add i to v.
  public static Int128 add(Int128 v, long i) {
    assert (i > 0);
    BigInteger bi = toBigInteger(v);
    BigInteger res = bi.add(BigInteger.valueOf(i));
    return valueOf(res);
  }

  public static Int128 increment(Int128 v) {
    return add(v, 1);
  }

  public static long compare(Int128 a, Int128 b) {
    if (a.id2 == b.id2) {
      return a.id1 - b.id1;
    } else {
      return a.id2 - b.id2;
    }
  }

  public static byte[] encode(Int128 v) {
    return ByteBuffer.allocate(16).putLong(v.id1).putLong(v.id2).array();
  }

  public static Int128 decode(byte[] v) {
    Int128 res = new Int128();
    ByteBuffer buf = ByteBuffer.wrap(v);
    res.id1 = buf.getLong();
    res.id2 = buf.getLong();
    return res;
  }

  public static BigInteger toUnsignedLong(long unsigned_long) {
    return new BigInteger(Long.toUnsignedString(unsigned_long, 16), 16);
  }

  public static BigInteger toBigInteger(Int128 v) {
    BigInteger lower = toUnsignedLong(v.id1);
    BigInteger higher = toUnsignedLong(v.id2);
    return higher.shiftLeft(64).add(lower);
  }

  public static Int128 valueOf(BigInteger v) {
    Int128 res = new Int128();
    res.id1 = v.and(toUnsignedLong(-1)).longValue();
    res.id2 = v.shiftRight(64).longValue();
    return res;
  }

  public static Int128 getRootId() {
    return newId(0, 1);
  }

  public static boolean isRootId(Int128 id) {
    return id.id1 == 0 && id.id2 == 1;
  }

  public static boolean isValid(Int128 id) {
    return id.id2 != Long.MIN_VALUE;
  }

  /**
   * Get the first non-root Id that can be allocated.
   * 
   * @return the first Id
   */
  public static Int128 getFirstId() {
    return newId(1, 1); // we reserve the IDs smaller than 2^64
  }

  public static Int128 newId(long lower, long upper) {
    Int128 res = new Int128();
    res.id1 = lower;
    res.id2 = upper;
    return res;
  }
}
