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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;

public class ZkUtilsTest extends TestBase {
  @BeforeClass
  public static void setUp() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    closeTestServer();
  }

  @Test
  public void testEnsurePath() throws Exception {
    String zpath = "/a/b/c";
    client.create().forPath("/a");
    assertNull(client.checkExists().forPath(zpath));
    ZkUtils.ensurePath(client, zpath, true);
    assertNull(client.checkExists().forPath(zpath));
    assertNotNull(client.checkExists().forPath("/a/b"));

    zpath = "/a/b/c/d";
    assertNull(client.checkExists().forPath(zpath));
    ZkUtils.ensurePath(client, zpath, false);
    assertNotNull(client.checkExists().forPath(zpath));

    ZkUtils.ensurePath(client, zpath, false);
    assertNotNull(client.checkExists().forPath(zpath));
  }

  @Test
  public void testParentZpath() throws Exception {
    assertEquals("/", ZkUtils.getParentZpath("/a"));
    assertEquals("/a/b", ZkUtils.getParentZpath("/a/b/c"));
  }
}
