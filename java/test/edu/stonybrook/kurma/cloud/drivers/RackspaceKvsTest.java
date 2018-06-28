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
package edu.stonybrook.kurma.cloud.drivers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.cloud.Kvs;

public class RackspaceKvsTest {
  private transient static Logger LOGGER = LoggerFactory.getLogger(RackspaceKvsTest.class);

  protected static String RACKSPACE_ACCESS_KEY = "fslkurma";
  protected static String RACKSPACE_SECRET_KEY = "32275bceaf7d4cccb1a9bece6916ef4c";
  protected static String RACKSPACE_CONTAINER = "testkurma";

  private Kvs rackspaceKvs = null;

  @Before
  public void setUp() throws Exception {
    try {
      rackspaceKvs = new RackspaceKvs("GTest", RACKSPACE_ACCESS_KEY, RACKSPACE_SECRET_KEY,
          RACKSPACE_CONTAINER, true, 1);
    } catch (Exception e) {
      LOGGER.error("Unable to create Rackspace KVS object: {}", e);
    }
  }

  @Test
  public void testRackspaceBasics() {
    try {
      rackspaceKvs.delete("testRackspaceBasics");
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      String msg = "hello Rackspace!";
      rackspaceKvs.put("testRackspaceBasics", new ByteArrayInputStream(msg.getBytes()),
          msg.getBytes().length);
      InputStream in = rackspaceKvs.get("testRackspaceBasics");
      String res = IOUtils.toString(in, "UTF-8");
      assertEquals(msg, res);
    } catch (IOException e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
}
