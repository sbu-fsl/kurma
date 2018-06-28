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

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import edu.stonybrook.kurma.cloud.drivers.FaultyKvs;
import edu.stonybrook.kurma.cloud.drivers.FileKvs;
import edu.stonybrook.kurma.cloud.drivers.TransientKvs;

public class ReplicationFacadeTest {
  private List<Kvs> kvsList = new ArrayList<>();

  @Test
  public void testTransientKvs() throws Exception {
    kvsList.add(new TransientKvs("transient"));
    kvsList.add(new TransientKvs("transient"));
    KvsFacadeTest.testKvs(new ReplicationFacade(kvsList, Integer.MAX_VALUE));
    kvsList.clear();
  }

  @Test
  public void testFileKvs() throws Exception {
    kvsList.add(new FileKvs("filekvs"));
    kvsList.add(new FileKvs("filekvs"));
    KvsFacadeTest.testKvs(new ReplicationFacade(kvsList, Integer.MAX_VALUE));
    kvsList.clear();
  }

  // Test Below simulates replication among the multiple cloud providers
  @Test
  public void testBothFileAndransientKvs() throws Exception {
    kvsList.add(new FileKvs("filekvs"));
    kvsList.add(new FileKvs("filekvs"));
    kvsList.add(new TransientKvs("transient"));
    kvsList.add(new TransientKvs("transient"));
    KvsFacadeTest.testKvs(new ReplicationFacade(kvsList, Integer.MAX_VALUE));
    kvsList.clear();
  }

  @Test
  public void testFaultyKvs() throws Exception {
    List<Kvs> kvs = new ArrayList<>();
    kvs.add(new FaultyKvs("testFaultyKvs-1", true, 1, 1));
    ReplicationFacade rf = new ReplicationFacade(kvs, Integer.MAX_VALUE);
    rf.put("aaa", ByteBuffer.wrap("bbb".getBytes()));
    assertEquals(null, rf.get("aaa", null));
    kvs.clear();
    kvs.add(new FaultyKvs("testFaultyKvs-2", true, 1, 1));
    kvs.add(new TransientKvs("transient"));
    rf.put("aaa", ByteBuffer.wrap("bbb".getBytes()));
    assertEquals(ByteBuffer.wrap("bbb".getBytes()), rf.get("aaa", null));
  }
}
