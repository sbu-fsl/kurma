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

public class GoogleKvsTest {
  private transient static Logger LOGGER = LoggerFactory.getLogger(GoogleKvsTest.class);

  protected static String GOOGLE_ACCESS_KEY = "GOOGMB6H34EIK274ZKIX";
  protected static String GOOGLE_SECRET_KEY = "VIa8gvP+v9SDb9HS0x/Ol9LBF7HOAytECvVgyvWO";
  protected static String GOOGLE_CONTAINER = "kurma-gcp-bucket";

  private Kvs googleKvs = null;

  @Before
  public void setUp() throws Exception {
    try {
      googleKvs =
          new GoogleKvs("GTest", GOOGLE_ACCESS_KEY, GOOGLE_SECRET_KEY, GOOGLE_CONTAINER, true, 1);
    } catch (Exception e) {
      LOGGER.error("Unable to create Google KVS object: {}", e);
    }
  }

  @Test
  public void testGoogleBasics() {
    try {
      googleKvs.delete("testGoogleBasics");
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      String msg = "hello Google!";
      googleKvs.put("testGoogleBasics", new ByteArrayInputStream(msg.getBytes()),
          msg.getBytes().length);
      InputStream in = googleKvs.get("testGoogleBasics");
      String res = IOUtils.toString(in, "UTF-8");
      assertEquals(msg, res);
    } catch (IOException e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
}
