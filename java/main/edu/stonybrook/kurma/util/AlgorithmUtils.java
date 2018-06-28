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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AlgorithmUtils {
  public static <T extends Comparable<T>> T clamp(T v, T min, T max) {
    if (v.compareTo(min) < 0)
      v = min;
    if (v.compareTo(max) > 0)
      v = max;
    return v;
  }

  public static String summarizeBuffer(String name, ByteBuffer buf) {
    ByteBuffer bufCopy = buf.slice();
    StringBuilder sb = new StringBuilder(
        String.format("%s: len=%06d hash=%8x: ", name, bufCopy.remaining(), bufCopy.hashCode()));
    for (int i = 0; i < 8; ++i) {
      sb.append(String.format("%02x ", bufCopy.get()));
    }
    sb.append("...");
    return sb.toString();
  }

  public static <T> void unorderedRemove(List<T> items, T t) {
    if (items instanceof ArrayList) {
      int i = items.indexOf(t);
      int s = items.size();
      items.set(i, items.get(s - 1));
      items.remove(s - 1);
    } else {
      items.remove(t);
    }
  }
}
