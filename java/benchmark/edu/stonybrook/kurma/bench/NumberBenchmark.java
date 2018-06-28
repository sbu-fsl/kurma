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
package edu.stonybrook.kurma.bench;

import java.util.function.BiFunction;

import com.google.caliper.Benchmark;

/**
 * Does not make a difference.
 * 
 * https://microbenchmarks.appspot.com/runs/990f2725-25a7-4ffa-9707-1d19c9e81a2e
 *
 * @author mchen
 *
 */
public final class NumberBenchmark {

  protected int timeIsAligned(int reps, BiFunction<Integer, Integer, Boolean> op) {
    int count = 0;
    final int shift = 14;
    for (int r = 0; r < reps; ++r) {
      for (int i = 0; i < (1 << (shift + 1)); ++i) {
        count += op.apply(i, shift) ? 1 : 0;
      }
    }
    return count;
  }

  @Benchmark
  public int timeIsAlignedShift(int reps) {
    return timeIsAligned(reps, (i, s) -> (i == ((i >> s) << s)));
  }

  @Benchmark
  public int timeIsAlignedZeros(int reps) {
    return timeIsAligned(reps, (i, s) -> (Integer.numberOfTrailingZeros(i) >= s));
  }

}
