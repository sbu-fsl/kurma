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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.drivers.FileKvs;
import edu.stonybrook.kurma.cloud.drivers.TransientKvs;
import edu.stonybrook.kurma.config.GatewayConfig;

@RunWith(Parameterized.class)
public class KvsFacadeParameterizedTest extends TestBase {
  private KvsFacade kf;

  /*
   * Max number of KVS required depending on number of 'k' (or 'm') in Erasure Test
   */
  private static int MAX_NO_OF_KVS = 15;

  private static TransientKvs[] transientKvsObj = new TransientKvs[MAX_NO_OF_KVS];
  private static FileKvs[] fileKvsObj = new FileKvs[MAX_NO_OF_KVS];
  private static final int TEST_REPITITION = 8;

  public KvsFacadeParameterizedTest(KvsFacade kf) {
    this.kf = kf;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> kvsFacadeObjects() {
    int index = 0;
    List<KvsFacade> kvsFacade = new ArrayList<KvsFacade>();
    GatewayConfig gatewayConfig = null;

    /*
     * In "r-n", literal 'r' is for replication and 'n' is number of Kvs instances for replication
     * In "e-k-m", literal 'e' is for erasure-coding and 'k' is data blocks and 'm' is coding blocks
     * In "s-n-m-r", literal 's' is for secret-sharing and 'n' is number of secrets
     */
    String[] kvsFacadeInstances =
        {"r-1", "r-2", "r-3", "r-4", "r-5", "r-6", "r-7", "r-8", "r-9", "r-10", "e-1-1", "e-2-1",
            "e-3-1", "e-4-2", "e-5-2", "e-6-2", "e-7-4", "e-8-3", "e-9-5", "e-10-5", "s-4-1-2"};
    /* Specifies ratio of Transient Kvs to File Kvs for kvsFacade */
    float[] kvsInstancesRatio =
        new float[] {0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f};

    /*
     * Create Transient and File Kvs once. Can be used for both replication and erasure
     */
    for (int i = 0; i < MAX_NO_OF_KVS; i++) {
      transientKvsObj[i] = new TransientKvs("transient" + i);
      fileKvsObj[i] = new FileKvs("testBasics" + i, "FileStore" + i, true, 1);
    }

    for (int i = 0; i < kvsFacadeInstances.length; i++) {
      for (int j = 0; j < kvsInstancesRatio.length; j++) {
        kvsFacade.add(createInstance(kvsFacadeInstances[i], kvsInstancesRatio[j]));
      }
    }
    /* Adding kvsFacade instances from config file */
    try {
      gatewayConfig = new GatewayConfig(GatewayConfig.KURMA_CLOUD_CONFIG_FILE);
    } catch (Exception e) {
    }
    if (gatewayConfig != null) {
      kvsFacade.add(gatewayConfig.getDefaultKvsFacade());
    }
    Object[][] facadeObjects = new Object[kvsFacade.size()][];
    for (KvsFacade kvFacade : kvsFacade) {
      facadeObjects[index] = new Object[1];
      facadeObjects[index++][0] = kvFacade;
    }
    return Arrays.asList(facadeObjects);
  }

  private static KvsFacade createInstance(String kvsType, float kvsRatio) {
    String[] partition = kvsType.split("-");
    int numberOfKvsInstances;
    int numberOfTransientKvsInstances;
    List<Kvs> kvsList = new ArrayList<Kvs>();
    if (kvsType.startsWith("r")) {
      numberOfKvsInstances = Integer.parseInt(partition[1]);
    } else if (kvsType.startsWith("e")) {
      int k = Integer.parseInt(partition[1]);
      int m = Integer.parseInt(partition[2]);
      numberOfKvsInstances = k + m;
    } else if (kvsType.startsWith("s")) {
      numberOfKvsInstances = Integer.parseInt(partition[1]);
    } else {
      return null;
    }

    numberOfTransientKvsInstances = (int) Math.floor(numberOfKvsInstances * kvsRatio);
    for (int i = 0; i < numberOfKvsInstances; i++) {
      if (i < numberOfTransientKvsInstances)
        kvsList.add(transientKvsObj[i]);
      else
        kvsList.add(fileKvsObj[i - numberOfTransientKvsInstances]);
    }

    return KvsFacade.newFacade(kvsType, kvsList, Integer.MAX_VALUE);
  }

  @Test
  public void testBasics() throws IOException {
    for (int n = 1; n <= TEST_REPITITION; n++) {
      ByteBuffer data = ByteBuffer.wrap(genRandomBytes(n));
      kf.put("aaa", data.duplicate());
      ByteBuffer out = kf.get("aaa", null);
      assertEquals(n, out.remaining());
      assertEquals(data, out);
    }
  }

  @Test
  public void testMultipleReads() throws Exception {
    for (int n = 1; n <= TEST_REPITITION; n++) {
      ByteBuffer data = ByteBuffer.wrap(genRandomBytes(n));
      kf.put("aa", data.duplicate());
      ByteBuffer value1 = kf.get("aa", null);
      ByteBuffer value2 = kf.get("aa", null);
      assertEquals(n, value1.remaining());
      assertEquals(n, value2.remaining());
      assertEquals(data, value1);
      assertEquals(value1, value2);
    }
  }

  @Test
  public void testGetAfterDelete() throws Exception {
    for (int n = 1; n <= TEST_REPITITION; n++) {
      ByteBuffer value1 = ByteBuffer.wrap(genRandomBytes(n));
      ByteBuffer value2 = ByteBuffer.wrap(genRandomBytes(n));
      kf.put("aaaa", value1);
      kf.put("bbbb", value2);
      kf.delete("aaaa");
      assertNull(kf.get("aaaa", null));
      ByteBuffer value3 = kf.get("bbbb", null);
      assertNotNull(value3);
      assertTrue(n == value3.remaining());
      assertTrue(value2.equals(value3));
    }
  }
}
