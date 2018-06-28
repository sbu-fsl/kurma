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
package edu.stonybrook.kurma.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.junit.Test;

import edu.stonybrook.kurma.KurmaGateway;
import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.KvsFacade;

public class GatewayConfigTest extends TestBase {

  @Test
  public void testBasics() throws Exception {
    IGatewayConfig config = new TestGatewayConfig();
    KurmaGateway local = config.getLocalGateway();
    assertEquals("ny", local.getName());
    assertFalse(local.isRemote());

    for (KurmaGateway gw : config.getGateways()) {
      assertTrue(gw == config.getGateway(gw.getId()));
      if (gw != local) {
        assertTrue(gw.isRemote());
      }
    }
  }

  @Test
  public void testKvsBasics() throws Exception {
    IGatewayConfig config = new TestGatewayConfig();
    String key = String.valueOf(genRandomBytes(16));
    ByteBuffer value = ByteBuffer.wrap(genRandomBytes(64));
    value.mark();
    KvsFacade kvs = config.getDefaultKvsFacade();
    kvs.put(key, value);
    ByteBuffer value2 = kvs.get(key, null);
    assertNotNull(value2);
    assertTrue(64 == value2.remaining());
    value.reset();
    assertTrue(64 == value.remaining());
    assertTrue(value.equals(value2));
  }

}
