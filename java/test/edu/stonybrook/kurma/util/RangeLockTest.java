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

import org.junit.Test;

public class RangeLockTest {

  @Test
  public void testNonOverlappingLocks() {
    RangeLock lock = new RangeLock();
    assertTrue(lock.lockRead(0, 5));
    assertTrue(lock.lockRead(5, 10));
    assertTrue(lock.lockWrite(100, 105));

    assertTrue(lock.isLocked(0));
    assertTrue(lock.isLocked(1));
    assertTrue(lock.isLocked(5));
    assertFalse(lock.isLocked(10));
    assertTrue(lock.isLocked(100));
    assertFalse(lock.isLocked(105));

    lock.unlockRead(0, 5);
    lock.unlockRead(5, 10);
    lock.unlockWrite(100, 105);

    assertFalse(lock.isLocked(0));
    assertFalse(lock.isLocked(1));
    assertFalse(lock.isLocked(5));
    assertFalse(lock.isLocked(10));
    assertFalse(lock.isLocked(100));
    assertFalse(lock.isLocked(105));
  }

  @Test
  public void testReadLocksCanOverlap() {
    RangeLock lock = new RangeLock();
    assertTrue(lock.lockRead(10, 20));
    assertTrue(lock.isLocked(15));

    assertTrue(lock.lockRead(10, 30));
    assertTrue(lock.isLocked(15));
    assertTrue(lock.isLocked(25));

    lock.unlockRead(10, 30);
    assertTrue(lock.isLocked(15));
    assertFalse(lock.isLocked(25));

    lock.unlockRead(10, 20);
    assertFalse(lock.isLocked(15));
    assertFalse(lock.isLocked(25));
  }

  @Test
  public void testReadLocksDenyWrites() {
    RangeLock lock = new RangeLock();
    assertTrue(lock.lockRead(10, 20));
    assertFalse(lock.lockWrite(15, 100));
    assertTrue(lock.isLocked(10));
    assertFalse(lock.isLocked(20));

    lock.unlockRead(10, 20);
    assertTrue(lock.lockWrite(15, 100));
    assertFalse(lock.isLocked(10));
    assertTrue(lock.isLocked(20));

    lock.unlockWrite(15, 100);
    assertFalse(lock.isLocked(10));
    assertFalse(lock.isLocked(20));
  }

  @Test
  public void testWriteLocksDenyReadsAndWrites() {
    RangeLock lock = new RangeLock();
    assertTrue(lock.lockWrite(10, 20));
    assertTrue(lock.isLocked(15));

    assertFalse(lock.lockRead(5, 15));
    assertFalse(lock.lockRead(15, 25));
    assertFalse(lock.lockRead(5, 25));
    assertFalse(lock.isLocked(5));
    assertFalse(lock.isLocked(20));

    assertFalse(lock.lockWrite(5, 15));
    assertFalse(lock.lockWrite(15, 25));
    assertFalse(lock.lockWrite(5, 25));
    assertFalse(lock.isLocked(5));
    assertFalse(lock.isLocked(20));
  }

  @Test
  public void testWholeRange() {
    RangeLock lock = new RangeLock();
    assertTrue(lock.lockWholeRange(true));
    assertTrue(lock.isLocked(15));
    lock.unlockWholeRange(true);
    assertFalse(lock.isLocked(15));

    assertTrue(lock.lockWholeRange(true));
    assertTrue(lock.lockWholeRange(true));
    assertTrue(lock.isLocked(15));
    lock.unlockWholeRange(true);
    assertTrue(lock.isLocked(15));
    lock.unlockWholeRange(true);
    assertFalse(lock.isLocked(15));

    // Write locks exclude any other locks
    assertTrue(lock.lockWholeRange(false));
    assertFalse(lock.lockWholeRange(false));
    assertFalse(lock.lockWrite(0, 1024));
    assertFalse(lock.lockRead(0, 1024));

    lock.unlockWrite(0, 10240);
    assertTrue(lock.lockWrite(0, 1024));
    assertFalse(lock.lockRead(0, 1024));
    assertTrue(lock.lockRead(2048, 10240));
  }

}
