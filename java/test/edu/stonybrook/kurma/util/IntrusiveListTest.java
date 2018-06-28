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

import java.util.Iterator;

import org.junit.Test;
import org.junit.Before;

public class IntrusiveListTest {
  IntrusiveList<IntrusiveNode<Integer>> list;
  IntrusiveNode<Integer> sentinel;

  @Before
  public void setUp() throws Exception {
    sentinel = new IntrusiveNode<Integer>();
    list = new IntrusiveList<IntrusiveNode<Integer>>(sentinel);
  }

  /**
   * Generate a list of "1, 2, 3, ..., N".
   * 
   * @param N
   * @return
   */
  protected int addN(int N) {
    for (int i = 1; i <= N; ++i) {
      list.add(new IntrusiveNode<Integer>(i));
    }
    return list.size();
  }

  protected IntrusiveNode<Integer> newNode(int i) {
    return new IntrusiveNode<Integer>(i);
  }

  @Test
  public void testEmptyList() {
    assertTrue(list.isEmpty());
    assertFalse(list.containsElement(sentinel));
    assertEquals(0, list.size());
  }

  @Test
  public void testAdd() {
    int N = 20;
    assertEquals(N, addN(N));
    for (int i = 1; i <= N; ++i) {
      assertEquals(Integer.valueOf(i), list.get(i - 1).getData());
    }
  }

  @Test
  public void testRemove() {
    IntrusiveNode<Integer> e = newNode(1);
    assertTrue(list.add(e));
    assertTrue(list.removeElement(e));
    assertTrue(list.isEmpty());
    assertEquals(0, list.size());
    assertTrue(list.add(e));
    assertEquals(1, list.size());
  }

  @Test
  public void testRemoveInTheMiddle() {
    int N = 20;
    addN(N);
    IntrusiveNode<Integer> e = list.get(9);
    assertEquals(Integer.valueOf(10), e.getData());
    assertTrue(e.removeFromList());
    assertEquals(N - 1, list.size());

    e = list.get(9);
    assertEquals(Integer.valueOf(11), e.getData());
    assertTrue(e.removeFromList());
    assertEquals(N - 2, list.size());
  }

  @Test
  public void testAddFirst() {
    int N = 20;
    // 1, 2, ..., N
    addN(N);
    // 0, 1, 2, ..., N
    list.addFirst(new IntrusiveNode<Integer>(0));
    for (int i = 0; i <= N; ++i) {
      assertEquals(Integer.valueOf(i), list.get(i).getData());
    }
  }

  @Test
  public void testIterator() {
    int N = 20;
    addN(N);
    Iterator<IntrusiveNode<Integer>> it = list.iterator();
    for (int i = 1; it.hasNext(); ++i) {
      IntrusiveNode<Integer> node = it.next();
      assertEquals(node.getData(), Integer.valueOf(i));
      it.remove();
    }
    assertTrue(list.isEmpty());
  }

  @Test
  public void testRemoveElement() {
    int N = 20;
    addN(N);
    for (int i = N; !list.isEmpty(); --i) {
      IntrusiveNode<Integer> node = list.getLast();
      assertEquals(node.getData(), Integer.valueOf(i));
      list.removeElement(node);
    }
  }

  @Test
  public void testReverse() {
    int N = 20;
    // 1, 2, ..., N
    addN(N);
    IntrusiveNode<Integer> pos = list.getLast();
    for (int i = 0; i < N - 1; ++i) {
      IntrusiveNode<Integer> node = list.remove(0);
      list.insertAt(pos, node);
    }
    // N, N-1, .., 1
    Iterator<IntrusiveNode<Integer>> it = list.iterator();
    for (int i = N; it.hasNext(); --i) {
      IntrusiveNode<Integer> node = it.next();
      assertEquals(node.getData(), Integer.valueOf(i));
    }
  }
}
