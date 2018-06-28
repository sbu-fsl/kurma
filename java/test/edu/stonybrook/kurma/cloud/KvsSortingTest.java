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

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.cloud.drivers.FileKvs;

@RunWith(Parameterized.class)
public class KvsSortingTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(KvsSortingTest.class);
  private static final int kvsSortPeriodSec = 3;
  private static final int latencyTestDataSize = 64;

  private KvsFacade facade;
  private KvsManager kvsManager;

  public KvsSortingTest(String kvsType) {
    super();
    LOGGER.info("testing Facade {}", kvsType);
    List<Kvs> kvsList =
        Arrays.asList(newDelayedFileKvs("FileStore0", 100), newDelayedFileKvs("FileStore1", 50),
            newDelayedFileKvs("FileStore2", 50), newDelayedFileKvs("FileStore3", 50));
    kvsManager = new KvsManager(latencyTestDataSize, kvsSortPeriodSec - 1);
    for (Kvs kvs : kvsList) {
      kvsManager.addKvs(kvs.getId(), kvs);
    }
    facade = KvsFacade.newFacade(kvsType, kvsList, kvsSortPeriodSec);
  }

  @After
  public void shutdown() {
    kvsManager.shutdown();
  }

  @Parameters
  public static Collection<Object[]> kvsObjects() {
    return Arrays.asList(TestBase.toObjectArray(Arrays.asList("r-4")),
        TestBase.toObjectArray(Arrays.asList("e-3-1")),
        TestBase.toObjectArray(Arrays.asList("s-4-1-2")));
  }

  public static FileKvs newDelayedFileKvs(String kvsName, int delayMs) {
    FileKvs kvs = new FileKvs(kvsName);
    DelayFilter delay = new DelayFilter(delayMs);
    kvs.installFilters(Arrays.asList(delay));
    return kvs;
  }

  @Test
  public void testBasicSorting() throws IOException, InterruptedException {
    final int N = 10;
    facade.put("aaa", genRandomBuffer(64));

    long start = System.currentTimeMillis();
    for (int i = 0; i < N; ++i) {
      facade.get("aaa", null);
    }
    long end = System.currentTimeMillis();
    double latency_ms = (end - start + 0.0) / N;
    assertTrue(latency_ms >= 200);

    Thread.sleep(2000);
    start = System.currentTimeMillis();
    for (int i = 0; i < N; ++i) {
      facade.get("aaa", null);
    }
    end = System.currentTimeMillis();
    latency_ms = (end - start + 0.0) / N;
    LOGGER.info("new latency (ms): {}", latency_ms);
    assertTrue(latency_ms < 150);
    assertTrue(latency_ms >= 100);
  }

}
