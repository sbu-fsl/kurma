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

import org.junit.Test;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;

import java.util.Iterator;
import java.util.Map.Entry;

public class TreeRangeMapTest {
  @Test
  public void testOverlappingPutOverwrites() {
    TreeRangeMap<Long, Integer> tm = TreeRangeMap.create();
    tm.put(Range.closedOpen(0L, 2L), Integer.valueOf(1));
    tm.put(Range.closedOpen(0L, 2L), Integer.valueOf(3));
    for (Entry<Range<Long>, Integer> e : tm.asMapOfRanges().entrySet()) {
      assertEquals(Range.closedOpen(0L, 2L), e.getKey());
    }
  }

  @Test
  public void testOverlappingCanBreakRanges() {
    TreeRangeMap<Long, Integer> tm = TreeRangeMap.create();
    tm.put(Range.closedOpen(0L, 5L), Integer.valueOf(1));
    assertEquals(Integer.valueOf(1), tm.get(3L));
    tm.put(Range.closedOpen(3L, 5L), Integer.valueOf(2));

    assertEquals(Range.closedOpen(3L, 5L), tm.getEntry(3L).getKey());
    assertEquals(Integer.valueOf(2), tm.get(3L));

    assertEquals(Range.closedOpen(0L, 3L), tm.getEntry(2L).getKey());
    assertEquals(Integer.valueOf(1), tm.get(2L));
  }

  @Test
  public void testRemoveCanBreakRanges() {
    TreeRangeMap<Long, Integer> tm = TreeRangeMap.create();
    tm.put(Range.closedOpen(0L, 5L), Integer.valueOf(1));
    tm.remove(Range.closedOpen(0L, 3L));
    assertNull(tm.get(2L));
    assertEquals(Integer.valueOf(1), tm.get(3L));
    assertEquals(Integer.valueOf(1), tm.get(4L));
    assertNull(tm.get(5L));
  }

  @Test
  public void testTouchingRangesAreNotJoined() {
    TreeRangeMap<Long, Integer> tm = TreeRangeMap.create();
    tm.put(Range.closedOpen(0L, 2L), 1);
    tm.put(Range.closedOpen(2L, 4L), 1);
    assertEquals(Range.closedOpen(2l, 4L), tm.getEntry(2L).getKey());
  }

  @Test
  public void testEmptySubRange() {
    TreeRangeMap<Long, Integer> tm = TreeRangeMap.create();
    tm.put(Range.closedOpen(0L, 1L), 1);
    tm.put(Range.closedOpen(2L, 3L), 1);
    assertTrue(tm.subRangeMap(Range.closedOpen(1L, 2L)).asMapOfRanges().isEmpty());
  }

  @Test
  public void testInsertsIntoSubrangeInvalidateInterators() {
    TreeRangeMap<Long, Integer> tm = TreeRangeMap.create();
    tm.put(Range.closedOpen(0L, 2L), 1);
    tm.put(Range.closedOpen(3L, 4L), 3);
    tm.put(Range.closedOpen(4L, 6L), 4);

    RangeMap<Long, Integer> rm = tm.subRangeMap(Range.closedOpen(1L, 9L));
    Iterator<Entry<Range<Long>, Integer>> it = rm.asMapOfRanges().entrySet().iterator();
    assertTrue(it.hasNext());
    Entry<Range<Long>, Integer> entry = it.next();
    assertEquals(Range.closedOpen(1L, 2L), entry.getKey());

    assertTrue(it.hasNext());
    entry = it.next();
    assertEquals(Range.closedOpen(3L, 4L), entry.getKey());
    tm.put(Range.closedOpen(2L, 3L), Integer.valueOf(2));

    // throws ConcurrentModificationException
    // assertTrue(it.hasNext());
  }
}
