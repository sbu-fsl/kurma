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

import org.junit.Test;

public class TimeWindowSumTest {

  private static long s2ms(int s) {
    return s * 1000L;
  }

  @Test
  public void testBasics() throws InterruptedException {
    TimeWindowSum tws = new TimeWindowSum(100);
    assertEquals(0, tws.get());
    long nowSecond = System.currentTimeMillis() / 1000;
    tws.incrementAt(nowSecond);
    assertEquals(1, tws.get());
    tws.incrementAt(nowSecond + s2ms(1));
    assertEquals(2, tws.get());
    tws.incrementAt(nowSecond + s2ms(1));
    assertEquals(3, tws.get());

    tws.incrementAt(nowSecond + s2ms(100));
    assertEquals(3, tws.get());
    tws.incrementAt(nowSecond + s2ms(101));
    assertEquals(2, tws.get());
    tws.incrementAt(nowSecond + s2ms(101));
    assertEquals(3, tws.get());
    tws.incrementAt(nowSecond + s2ms(102));
    assertEquals(4, tws.get());
  }

}
