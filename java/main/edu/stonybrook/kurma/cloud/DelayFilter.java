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
package edu.stonybrook.kurma.cloud;

import java.io.InputStream;

public class DelayFilter implements KvsFilterInterface {

  protected int inBoundDelay;
  protected int outBoundDelay;

  public DelayFilter(int delayMs) {
    this(delayMs, delayMs);
  }

  public DelayFilter(int inBoundDelay, int outBoundDelay) {
    this.inBoundDelay = inBoundDelay;
    this.outBoundDelay = outBoundDelay;
  }

  public void beforePut(String key, InputStream value) {
    try {
      Thread.sleep(inBoundDelay);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void afterPut(String key, InputStream value) {
    try {
      Thread.sleep(outBoundDelay);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void beforeGet(String key) {
    try {
      Thread.sleep(inBoundDelay);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void afterGet(String key, InputStream value) {
    try {
      Thread.sleep(outBoundDelay);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
