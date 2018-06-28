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

public class AzureKvsTest {
  private transient static Logger LOGGER = LoggerFactory.getLogger(AzureKvsTest.class);

  protected static String AZURE_ACCESS_KEY = "kurma";
  protected static String AZURE_SECRET_KEY =
      "7D+s2E9/nKaBxOS3+ZweEnz+T4fzKhuDiFB3Xm3aXubqZtijOyTyr98c+pMeSiTPNHPVFjHQUJeitSq9GyuomQ==";
  protected static String AZURE_CONTAINER = "testcontainer";

  private Kvs azureKvs = null;

  @Before
  public void setUp() throws Exception {
    try {
      azureKvs =
          new AzureKvs("GTest", AZURE_ACCESS_KEY, AZURE_SECRET_KEY, AZURE_CONTAINER, true, 1);
    } catch (Exception e) {
      LOGGER.error("Unable to create Azure KVS object: {}", e);
    }
  }

  @Test
  public void testAzureBasics() {
    try {
      azureKvs.delete("testAzureBasics");
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      String msg = "hello azure!";
      azureKvs.put("testAzureBasics", new ByteArrayInputStream(msg.getBytes()),
          msg.getBytes().length);
      InputStream in = azureKvs.get("testAzureBasics");
      String res = IOUtils.toString(in, "UTF-8");
      assertEquals(msg, res);
    } catch (IOException e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

}
