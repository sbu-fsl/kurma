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

import org.bouncycastle.util.Arrays;

public class TimeWindowSum {
  private int[] circleBuffer;
  private int circleIndex;

  private long timestamp = 0;
  private long seconds = 0;
  private long sum = 0;

  public TimeWindowSum() {
    this(600); // default value is 10 minutes
  }

  public TimeWindowSum(int seconds) {
    this.seconds = seconds;
    this.circleIndex = 0;
    this.circleBuffer = new int[seconds];
  }

  public long increment() {
    long now = System.currentTimeMillis();
    return incrementAt(now);
  }

  public synchronized long incrementAt(long time_ms) {
    if (sum == 0) {
      circleIndex = 0;
      circleBuffer[circleIndex] = 1;
      sum = 1;
      timestamp = time_ms;
      return sum;
    }
    long diff = (time_ms - timestamp) / 1000; // to seconds
    if (diff <= 0) {
      circleBuffer[circleIndex] += 1;
      sum += 1;
    } else if (diff >= seconds) {
      Arrays.fill(circleBuffer, 0);
      circleIndex = 0;
      circleBuffer[circleIndex] = 1;
      sum = 1;
    } else {
      for (int i = 0; i < diff; ++i) {
        if (++circleIndex == seconds) {
          circleIndex = 0;
        }
        sum -= circleBuffer[circleIndex];
        circleBuffer[circleIndex] = 0;
      }
      circleBuffer[circleIndex] = 1;
      sum += 1;
    }
    timestamp = time_ms;
    return sum;
  }

  public synchronized long get() {
    return sum;
  }
}
