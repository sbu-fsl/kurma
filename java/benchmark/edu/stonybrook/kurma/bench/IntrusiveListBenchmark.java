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

import java.util.ArrayList;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;

import edu.stonybrook.kurma.util.IntrusiveList;
import edu.stonybrook.kurma.util.IntrusiveNode;

/**
 * Please follow instructions from http://microbenchmarks.appspot.com/
 *
 * An example of the output of this benchmark is at:
 * https://microbenchmarks.appspot.com/runs/faf3aa92-c943-4dc4-b445-17ce6226fc63
 *
 * @author mchen
 *
 */
public final class IntrusiveListBenchmark {
  @Param({"10000"})
  int N;

  @Benchmark
  public int timeIntrusiveList(int reps) {
    int sum = 0;
    for (int r = 0; r < reps; ++r) {
      IntrusiveList<IntrusiveNode<Integer>> list =
          new IntrusiveList<>(new IntrusiveNode<Integer>());
      for (int i = 0; i < N; ++i) {
        list.add(new IntrusiveNode<>(i));
      }
      // remove lists from the 1st item
      while (!list.isEmpty()) {
        IntrusiveNode<Integer> node = list.getFirst();
        sum += node.getData();
        node.removeFromList();
      }
    }
    return sum;
  }

  @Benchmark
  public int timeArrayList(int reps) {
    ArrayList<Integer> list = new ArrayList<>(N);
    int sum = 0;
    for (int r = 0; r < reps; ++r) {
      for (int i = 0; i < N; ++i) {
        list.add(i);
      }
      while (!list.isEmpty()) {
        sum += list.get(0);
        list.remove(0);
      }
    }
    return sum;
  }

  public static void main(String[] args) {
    // com.google.caliper.Runner.main(args[0]);
  }
}
