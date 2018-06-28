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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

public class CopyOnWriteListTest {

  @SuppressWarnings("unchecked")
  @Test
  public void testCOWList() {
    CopyOnWriteArrayList<Integer> la = new CopyOnWriteArrayList<>();
    la.add(1);
    la.add(2);
    CopyOnWriteArrayList<Integer> lref = la;
    assertTrue(lref.contains(Integer.valueOf(1)));
    assertTrue(lref.contains(Integer.valueOf(2)));
    assertFalse(lref.contains(Integer.valueOf(3)));

    CopyOnWriteArrayList<Integer> lcopy = (CopyOnWriteArrayList<Integer>) la.clone();
    assertTrue(lcopy.contains(Integer.valueOf(1)));
    assertTrue(lcopy.contains(Integer.valueOf(2)));
    assertFalse(lcopy.contains(Integer.valueOf(3)));

    la.remove(1);
    assertTrue(lref.contains(Integer.valueOf(1)));
    assertFalse(lref.contains(Integer.valueOf(2)));
    assertTrue(lcopy.contains(Integer.valueOf(1)));
    assertTrue(lcopy.contains(Integer.valueOf(2)));

    la.add(3);
    assertTrue(lref.contains(Integer.valueOf(1)));
    assertTrue(lref.contains(Integer.valueOf(3)));
    assertTrue(lcopy.contains(Integer.valueOf(1)));
    assertTrue(lcopy.contains(Integer.valueOf(2)));
  }

}
