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

import org.junit.Test;

import static org.junit.Assert.*;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.meta.Int128;

public class Int128HelperTest {

  @Test
  public void testMinusOneIsMaxUnsignedLong() {
    BigInteger bi = Int128Helper.toUnsignedLong(-1);
    Int128 i = Int128Helper.valueOf(bi);
    assertEquals(0, i.id2);
    Int128 n = Int128Helper.increment(i);
    assertEquals(0, n.id1);
    assertEquals(1, n.id2);
  }

}
