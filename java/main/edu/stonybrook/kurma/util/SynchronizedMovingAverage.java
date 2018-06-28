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

public class SynchronizedMovingAverage {
  private double avg = 0;
  private double weight = 0.8;

  public SynchronizedMovingAverage() {}

  public SynchronizedMovingAverage(double w) {
    this.weight = w;
  }

  public synchronized double add(double v) {
    avg = avg * (1 - weight) + v * weight;
    return avg;
  }

  public synchronized double get() {
    return avg;
  }
}
