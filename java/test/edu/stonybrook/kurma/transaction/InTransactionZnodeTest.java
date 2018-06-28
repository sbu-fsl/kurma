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
package edu.stonybrook.kurma.transaction;

import java.util.Arrays;

import org.junit.Test;

import edu.stonybrook.kurma.TestBase;

public class InTransactionZnodeTest extends TestBase {

  @Test
  public void testGetAfterPut() {
    InTransactionZnodes znodes = new InTransactionZnodes();
    byte data[] = "data".getBytes();
    final String zpath = "/InTransactionZnodeTest";
    znodes.put(zpath, data);
    assert(Arrays.equals(znodes.get(zpath), data));
  }
  @Test
  public void testDeletion() {
    InTransactionZnodes znodes = new InTransactionZnodes();
    byte data[] = "data".getBytes();
    final String zpath = "/testDeletion-";
    znodes.put(zpath, data);
    znodes.delete(zpath);
    assert (znodes.get(zpath) == null);
  }

  @Test
  public void testCreationAfterDeletion() {
    InTransactionZnodes znodes = new InTransactionZnodes();
    byte data[] = "data".getBytes();
    final String zpath = "/testCreationAfterDeletion-";
    znodes.put(zpath, data);
    znodes.delete(zpath);
    assert (znodes.get(zpath) == null);
    znodes.put(zpath, data);
    assert (Arrays.equals(znodes.get(zpath), data));
  }

  @Test
  public void testConcurrentOperations() throws Exception {
    InTransactionZnodes znodes = new InTransactionZnodes();
    final String zpath = "/testConcurrentOperations-"; 
    doParallel(100, i -> {
      byte data[] = ("data" + i).getBytes();
      znodes.put(zpath + i, data);
    });
    for (int i = 0; i < 10; i++) {
      byte data[] = ("data" + i).getBytes();
      assert (Arrays.equals(znodes.get(zpath + i), data));
    }
  }

}
