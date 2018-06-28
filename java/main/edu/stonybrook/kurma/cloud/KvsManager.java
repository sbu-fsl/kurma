/**
 * Copyright (C) 2013 EURECOM (www.eurecom.fr)
 *
 * Copyright (C) 2015-2017 Ming Chen <v.mingchen@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package edu.stonybrook.kurma.cloud;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jclouds.rest.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.cloud.drivers.AmazonKvs;
import edu.stonybrook.kurma.cloud.drivers.AzureKvs;
import edu.stonybrook.kurma.cloud.drivers.FaultyKvs;
import edu.stonybrook.kurma.cloud.drivers.FileKvs;
import edu.stonybrook.kurma.cloud.drivers.GoogleKvs;
import edu.stonybrook.kurma.cloud.drivers.RackspaceKvs;
import edu.stonybrook.kurma.cloud.drivers.TransientKvs;
import edu.stonybrook.kurma.config.ProviderAccount;

/**
 * KvsManager exposes a simple common cloud storage API for saving, retrieving and deleting data on
 * the supported cloud storage services.
 *
 * @author p.viotti
 */
public class KvsManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(KvsManager.class);

  private HashMap<String, Kvs> kvStores;
  private List<Kvs> kvsLst; // kvStores list (not sorted)
  private List<Kvs> kvsLstByReads; // kvStores sorted by read latency
  private List<Kvs> kvsLstByWrites; // kvStores sorted by write latency

  private int latencyTestDataSize;

  private Thread latencyTestThread;
  private AtomicBoolean running;

  public static final String FAIL_PREFIX = "FAIL-";

  public enum KvsType {
    AMAZON((short) 0), AZURE((short) 1), GOOGLE((short) 2), RACKSPACE((short) 3), MEMORY(
        (short) 4), FAULTY((short) 5), FILE((short) 6);

    private short serialNum;

    private KvsType(short sn) {
      this.serialNum = sn;
    }

    public int getSerial() {
      return this.serialNum;
    }

    public static KvsType getIdFromSerial(short num) {
      switch (num) {
        case 0:
          return AMAZON;
        case 1:
          return AZURE;
        case 2:
          return GOOGLE;
        case 3:
          return RACKSPACE;
        case 4:
          return MEMORY;
        case 5:
          return FAULTY;
        case 6:
          return FILE;
        default:
          throw new IllegalArgumentException();
      }
    }

    @Override
    public String toString() {
      return super.toString().toLowerCase();
    }
  };

  public static String kvsListToString(List<Kvs> kvsList) {
    StringBuilder sb = new StringBuilder();
    for (Kvs kvs : kvsList) {
      sb.append(kvs.getId());
      sb.append(';');
    }
    return sb.toString();
  }

  public List<Kvs> stringToKvsList(String kvsStr) {
    List<Kvs> kvsList = new ArrayList<>();
    for (String kvsId : kvsStr.split(";")) {
      Kvs kvs = getKvs(kvsId);
      if (kvs == null) {
        return null;
      }
      kvsList.add(kvs);
    }
    return kvsList;
  }

  public static Kvs newKvs(String kvsType, String kvsId, ProviderAccount account) {
    Kvs kvs = null;
    try {
      switch (KvsType.valueOf(kvsType.toUpperCase())) {
        case FILE:
          kvs = new FileKvs(kvsId, account.getBucket(), account.isEnabled(), account.getCost());
          break;
        case MEMORY:
          kvs = new TransientKvs(kvsId);
          break;
        case AMAZON:
          kvs = new AmazonKvs(kvsId, account.getAccessKey(), account.getSecretKey(),
              account.getBucket(), account.isEnabled(), account.getCost());
          break;
        case AZURE:
          kvs = new AzureKvs(kvsId, account.getAccessKey(), account.getSecretKey(),
              account.getBucket(), account.isEnabled(), account.getCost());
          break;
        case GOOGLE:
          kvs = new GoogleKvs(kvsId, account.getAccessKey(), account.getSecretKey(),
              account.getBucket(), account.isEnabled(), account.getCost());
          break;
        case RACKSPACE:
          kvs = new RackspaceKvs(kvsId, account.getAccessKey(), account.getSecretKey(),
              account.getBucket(), account.isEnabled(), account.getCost());
          break;
        case FAULTY:
          kvs = new FaultyKvs(kvsId, account.getAccessKey(), account.getSecretKey(),
              account.getBucket(), account.isEnabled(), account.getCost());
        default:
          LOGGER.error("unknown provider type: '{}'; valid types are: "
              + "'File', 'Memory', 'Amazon', 'Google', and 'Azure'.", kvsType);
          System.exit(1);
      }
    } catch (IOException e) {
      LOGGER.error("failed to create kvs", e);
      kvs = null;
    }
    return kvs;
  }

  public KvsManager(int blockSize, int kvsSortPeriodSec) {
    this.latencyTestDataSize = blockSize;
    kvStores = new HashMap<>();
    kvsLst = new ArrayList<>();
    running = new AtomicBoolean(true);
    if (kvsSortPeriodSec != Integer.MAX_VALUE) {
      latencyTestThread = new Thread() {
        @Override
        public void run() {
          ExecutorService executor = Executors.newFixedThreadPool(4);
          while (running.get()) {
            try {
              Thread.sleep(kvsSortPeriodSec * 1000);
            } catch (InterruptedException e1) {
              e1.printStackTrace();
            }
            if (kvsLst.isEmpty()) {
              continue;
            }

            List<FutureTask<Object>> futureLst = new ArrayList<FutureTask<Object>>(kvsLst.size());
            for (Kvs kvStore : kvsLst) {
              FutureTask<Object> f =
                  new FutureTask<Object>(new LatencyTester(kvStore, latencyTestDataSize), null);
              futureLst.add(f);
              executor.execute(f);
            }

            for (FutureTask<Object> future : futureLst) {
              try {
                future.get();
              } catch (InterruptedException | ExecutionException e) {
                LOGGER.warn("Exception while running latency test.", e);
              } finally {
                future.cancel(true);
              }
            }
          }

          executor.shutdown();
        }
      };
      latencyTestThread.start();
    }
  }

  public Kvs getKvs(String kvsId) {
    return kvStores.getOrDefault(kvsId, null);
  }

  public void addKvs(String kvsId, Kvs kvs) {
    kvStores.put(kvsId, kvs);
    kvsLst.add(kvs);
  }

  public List<Kvs> getKvsList() {
    return this.kvsLst;
  }

  public List<Kvs> getKvsListByIds(List<String> keyIds) {
    List<Kvs> l = new ArrayList<>(keyIds.size());
    for (String id : keyIds) {
      l.add(getKvs(id));
    }
    return l;
  }

  public List<Kvs> getKvsSortedByReadLatency() {
    return this.kvsLstByReads;
  }

  public List<Kvs> getKvsSortedByWriteLatency() {
    return this.kvsLstByWrites;
  }

  /**
   * Worker thread class in charge of testing read and write latencies of a KvStore.
   *
   * @author p.viotti
   */
  public class LatencyTester implements Runnable {
    private static final String TEST_KEY = "latency_test-";
    private static final int TEST_ITERATIONS = 3;
    private final Kvs kvStore;
    private int size;

    public LatencyTester(Kvs kvStore, int testDataBytes) {
      this.kvStore = kvStore;
      this.size = testDataBytes;
    }

    @Override
    public void run() {
      byte[] testData = new byte[size];
      long start, end = 0;
      Random random = new Random();

      // Write
      try {
        LongSummaryStatistics stat = new LongSummaryStatistics();
        for (int i = 0; i < TEST_ITERATIONS; ++i) {
          String testKey = TEST_KEY + i;
          start = System.currentTimeMillis();
          random.nextBytes(testData);
          KvsFacade.putValue(kvStore, testKey, ByteBuffer.wrap(testData));
          end = System.currentTimeMillis();
          stat.accept(end - start);
          // this.kvStore.logWriteLatency(end - start);
        }
        this.kvStore.logWriteLatency((long) stat.getAverage());
        LOGGER.info("log write latency {} of {}, result: {}", stat.getAverage(), kvStore.getId(),
            kvStore.getWriteLatency());
      } catch (Exception e) {
        this.kvStore.logFailure();
        if (e instanceof AuthorizationException) {
          this.kvStore.setEnabled(false);
        }
        return;
      }

      // Read
      byte[] retrieved = null;
      try {
        LongSummaryStatistics stat = new LongSummaryStatistics();
        for (int i = 0; i < TEST_ITERATIONS; ++i) {
          String testKey = TEST_KEY + i;
          start = System.currentTimeMillis();
          retrieved = KvsFacade.getValue(kvStore, testKey).array();
          if (retrieved == null) {
            --i;
            continue;
          }
          end = System.currentTimeMillis();
          stat.accept(end - start);
          // this.kvStore.logReadLatency(end - start);
        }
        this.kvStore.logReadLatency((long) stat.getAverage());
        LOGGER.info("log read latency {} of {}, result: {}", stat.getAverage(), kvStore.getId(),
            kvStore.getReadLatency());
      } catch (Exception e) {
        this.kvStore.logFailure();
        if (e instanceof AuthorizationException)
          this.kvStore.setEnabled(false);
      }

      // Clean up
      /*
       * try { for (int i = 0; i < TEST_ITERATIONS; ++i) { String testKey = TEST_KEY + i;
       * this.kvStore.delete(testKey); } } catch (IOException e) { }
       */
    }
  }

  public void shutdown() {
    running.set(false);
  }

}
