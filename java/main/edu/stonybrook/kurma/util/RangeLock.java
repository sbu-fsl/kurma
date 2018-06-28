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

import java.util.ArrayList;
import java.util.Map.Entry;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;

public class RangeLock {
  private final Integer WRITE_LOCK = Integer.valueOf(1);
  private final Integer READ_LOCK = Integer.valueOf(2);

  private Range<Long> newRange(long b, long e) {
    return Range.closedOpen(b, e);
  }

  public boolean lockWholeRange(boolean forRead) {
    return forRead ? lockRead(0, Long.MAX_VALUE) : lockWrite(0, Long.MAX_VALUE);
  }

  public void unlockWholeRange(boolean forRead) {
    if (forRead) {
      unlockRead(0, Long.MAX_VALUE);
    } else {
      unlockWrite(0, Long.MAX_VALUE);
    }
  }

  public boolean lockRead(long begin, long end) {
    RangeMap<Long, Integer> overlaps = space.subRangeMap(Range.closedOpen(begin, end));
    for (Integer v : overlaps.asMapOfRanges().values()) {
      if (WRITE_LOCK.equals(v)) {
        return false;
      }
    }

    long b = begin;
    RangeMap<Long, Integer> inserts = TreeRangeMap.create();
    for (Entry<Range<Long>, Integer> entry : overlaps.asMapOfRanges().entrySet()) {
      long lower = entry.getKey().lowerEndpoint();
      if (b < lower) {
        inserts.put(newRange(b, lower), READ_LOCK);
      }
      b = entry.getKey().upperEndpoint();
      inserts.put(newRange(lower, b), READ_LOCK + entry.getValue());
    }
    if (b < end) {
      inserts.put(newRange(b, end), READ_LOCK);
    }

    space.putAll(inserts);

    return true;
  }

  public void unlockRead(long begin, long end) {
    RangeMap<Long, Integer> overlaps = space.subRangeMap(Range.closedOpen(begin, end));
    RangeMap<Long, Integer> inserts = TreeRangeMap.create();
    ArrayList<Range<Long>> removes = new ArrayList<>();
    for (Entry<Range<Long>, Integer> entry : overlaps.asMapOfRanges().entrySet()) {
      int v = entry.getValue() - READ_LOCK;
      assert (v >= 0 && (v & 0x1) == 0);
      if (v > 0) {
        inserts.put(entry.getKey(), v);
      } else {
        removes.add(entry.getKey());
      }
    }

    space.putAll(inserts);
    for (Range<Long> r : removes) {
      space.remove(r);
    }
  }

  public boolean lockWrite(long begin, long end) {
    RangeMap<Long, Integer> overlaps = space.subRangeMap(Range.closedOpen(begin, end));
    if (!overlaps.asMapOfRanges().isEmpty()) {
      return false;
    }
    space.put(newRange(begin, end), 1);
    return true;
  }

  public void unlockWrite(long begin, long end) {
    space.remove(newRange(begin, end));
  }

  public boolean isLocked(long pos) {
    return space.get(pos) != null;
  }

  private TreeRangeMap<Long, Integer> space = TreeRangeMap.create();
}
