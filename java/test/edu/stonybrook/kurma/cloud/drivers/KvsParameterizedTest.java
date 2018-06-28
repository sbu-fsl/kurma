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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.DelayFilter;
import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsFilterInterface;
import edu.stonybrook.kurma.util.ByteBufferOutputStream;

@RunWith(Parameterized.class)
public class KvsParameterizedTest extends TestBase {
  private Kvs kvs;

  /*
   * these are keys from a personal test account and is expected to expire after 2016-05-20
   */
  private static String GOOGLE_ACCESS_KEY = "GOOGGSBUAKFK6YNZM6NU";
  private static String GOOGLE_SECRET_KEY = "aNYcDdyjLDt75ceb8lZm5y2WT+boTedEdL1RhzGy";
  private static String GOOGLE_CONTAINER = "kurma_bucket";

  private static final Logger LOGGER = LoggerFactory.getLogger(KvsParameterizedTest.class);

  public KvsParameterizedTest(Kvs kvs) {
    this.kvs = kvs;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> kvsObjects() {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    List<Kvs> kvsList = new ArrayList<Kvs>();
    DelayFilter delayFilter = new DelayFilter(1000);
    int index = 0;
    TransientKvs transientKvsWithDelay = new TransientKvs("testBasicsWithDelay");
    List<KvsFilterInterface> filterList = new ArrayList<KvsFilterInterface>();
    filterList.add(delayFilter);
    transientKvsWithDelay.installFilters(filterList);
    kvsList.add(transientKvsWithDelay);

    kvsList.add(new TransientKvs("testBasics"));
    kvsList.add(new FileKvs("filekvs"));

    try {
      Kvs googleKvs =
          new GoogleKvs("GTest", GOOGLE_ACCESS_KEY, GOOGLE_SECRET_KEY, GOOGLE_CONTAINER, true, 1);
      kvsList.add(googleKvs);
    } catch (Exception e) {
      LOGGER.error("Unable to create Google KVS object: {}", e);
    }

    try {
      Kvs azureKvs = new AzureKvs("GTest", AzureKvsTest.AZURE_ACCESS_KEY,
          AzureKvsTest.AZURE_SECRET_KEY, AzureKvsTest.AZURE_CONTAINER, true, 1);
      kvsList.add(azureKvs);
    } catch (Exception e) {
      LOGGER.error("Unable to create Azure KVS object: {}", e);
    }

    Object[][] kvsObjects = new Object[kvsList.size()][];
    for (Kvs kvs : kvsList) {
      kvsObjects[index] = new Object[1];
      kvsObjects[index++][0] = kvs;
    }
    return Arrays.asList(kvsObjects);
  }

  protected ByteBuffer readAsByteBuffer(String key, int size) throws Exception {
    InputStream in = kvs.get(key);
    ByteBufferOutputStream out = new ByteBufferOutputStream(size);
    ByteStreams.copy(in, out);
    in.close();
    return out.getByteBuffer();
  }

  @Test
  public void testBasics() throws Exception {
    for (int i = 1; i <= 32; i++) {
      byte[] data = genRandomBytes(i);
      kvs.put("aaa", new ByteArrayInputStream(data), i);
      ByteBuffer value = readAsByteBuffer("aaa", i);
      assertEquals(value, ByteBuffer.wrap(data));
    }
  }

  @Test
  public void testMultipleReads() throws Exception {
    for (int i = 1; i <= 32; i++) {
      byte[] data = genRandomBytes(i);
      kvs.put("aaa", new ByteArrayInputStream(data), i);
      ByteBuffer value1 = readAsByteBuffer("aaa", i);
      ByteBuffer value2 = readAsByteBuffer("aaa", i);
      assertEquals(value1, value2);
    }
  }

  @Test
  public void testGetAfterDelete() throws Exception {
    for (int i = 1; i <= 32; i++) {
      byte[] aaa = genRandomBytes(i);
      byte[] bbb = genRandomBytes(i);
      kvs.put("aaa", new ByteArrayInputStream(aaa), i);
      kvs.put("bbb", new ByteArrayInputStream(bbb), i);
      kvs.delete("aaa");
      InputStream ais = kvs.get("aaa");
      InputStream bis = kvs.get("bbb");
      assertNull(ais);
      assertNotNull(bis);
      bis.close();
    }
  }

  @Test
  public void testListOpeartion() throws Exception {
    byte[] aaa = genRandomBytes(32);
    List<String> keys = kvs.list();
    kvs.put("keyForList", new ByteArrayInputStream(aaa), 32);
    assertFalse(Arrays.equals(keys.toArray(), (kvs.list()).toArray()));
    kvs.delete("keyForList");
    assertTrue(Arrays.equals(keys.toArray(), (kvs.list()).toArray()));
  }
}
