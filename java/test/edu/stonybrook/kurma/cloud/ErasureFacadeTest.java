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

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.stonybrook.kurma.cloud.drivers.FileKvs;
import edu.stonybrook.kurma.cloud.drivers.TransientKvs;

public class ErasureFacadeTest {
  private int[] testKValues;
  private int[] testMValues;

  @Before
  public void setUp() throws Exception {
    // Specify different values of k (Data blocks) and m (Coding blocks)
    testKValues = new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    testMValues = new int[] {1, 1, 1, 2, 2, 2, 4, 3, 5, 5};
  }

  @Test
  public void testTransientKvs() throws Exception {
    List<Kvs> kvsList = new ArrayList<>();
    for (int i = 0; i < testKValues.length; i++) {
      int k = testKValues[i];
      int m = testMValues[i];
      for (int j = 0; j < k + m; j++) {
        kvsList.add(new TransientKvs("testBasics" + j));
      }
      KvsFacadeTest.testKvs(new ErasureFacade(k, m, kvsList, Integer.MAX_VALUE));
      kvsList.clear();
    }
  }

  @Test
  public void testFileKvs() throws Exception {
    List<Kvs> kvsList = new ArrayList<>();
    for (int i = 0; i < testKValues.length; i++) {
      int k = testKValues[i];
      int m = testMValues[i];
      for (int j = 0; j < k + m; j++) {
        kvsList.add(new FileKvs("testBasics" + j, "FileStore" + j, true, 1));
      }
      KvsFacadeTest.testKvs(new ErasureFacade(k, m, kvsList, Integer.MAX_VALUE));
      kvsList.clear();
    }
  }

  /**
   * Test Below simulates Erasure coding among the multiple cloud providers
   */
  @Test
  public void testBothFileAndTransientKvs() throws Exception {
    List<Kvs> kvsList = new ArrayList<>();
    for (int i = 0; i < testKValues.length; i++) {
      int k = testKValues[i];
      int m = testMValues[i];
      for (int j = 0; j < k + m; j++) {
        // Selecting same number of File Kvs and half Transient Kvs
        if (j < ((k + m) / 2))
          kvsList.add(new FileKvs("testBasics" + j, "FileStore" + j, true, 1));
        else
          kvsList.add(new TransientKvs("testBasics" + j));
      }
      KvsFacadeTest.testKvs(new ErasureFacade(k, m, kvsList, Integer.MAX_VALUE));
      kvsList.clear();
    }
  }
}
