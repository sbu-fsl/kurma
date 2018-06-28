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
package edu.stonybrook.kurma;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.commons.io.FileUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.config.TestGatewayConfig;
import edu.stonybrook.kurma.gc.DefaultCollector;
import edu.stonybrook.kurma.gc.JournaledBlockCollector;
import edu.stonybrook.kurma.gc.TestBlockCollector;
import edu.stonybrook.kurma.gc.TestDirCollector;
import edu.stonybrook.kurma.gc.TestFileCollector;
import edu.stonybrook.kurma.gc.TestGarbageCollector;
import edu.stonybrook.kurma.gc.TestJournaledGarbageCollector;
import edu.stonybrook.kurma.gc.TestZnodeCollector;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.VolumeInfoHelper;
import edu.stonybrook.kurma.replicator.ConflictResolver;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.server.FileHandler;
import edu.stonybrook.kurma.server.KurmaHandler;
import edu.stonybrook.kurma.server.VolumeHandler;
import edu.stonybrook.kurma.transaction.ZkClient;
import edu.stonybrook.kurma.util.RandomBuffer;

public class TestBase {
  public static String TEST_DIRECTORY = "/tmp/kurma-test/";
  public static final int DUMMY_GARBAGE_COLLECTOR = 0;
  public static final int DEFAULT_GARBAGE_COLLECTOR = 1;
  public static final int JOURNALED_GARBAGE_COLLECTOR = 2;

  protected static final int RANDOM_SEED = 8889;

  protected static TestingServer server;
  private static CuratorFramework rootClient;
  protected static CuratorFramework client;
  protected static ZkClient zkClient;
  protected static String volumeId = "TestVolumeN";
  protected static KurmaHandler kh;
  protected static ConflictResolver conflictResolver;

  protected static TestGarbageCollector dummyGarbageCollector = null;
  protected static TestJournaledGarbageCollector journaledGarbageCollector = null;
  protected static DefaultCollector garbageCollector = null;

  protected static IGatewayConfig config;
  protected static VolumeHandler vh;
  protected static DirectoryHandler rootDh;

  @Rule
  public ExpectedException exception = ExpectedException.none();

  public static Path getTestDirectory(String subDir) {
    return Paths.get(TEST_DIRECTORY, subDir);
  }

  public static void startTestServer(int garbageCollection) throws Exception {
    server = new TestingServer(true);
    config = new TestGatewayConfig();

    FileUtils.cleanDirectory(fetchJournalDirectory(config));

    rootClient = connectToServer(null);
    client = rootClient.usingNamespace(KurmaHandler.getZkNamespace(config.getGatewayName()));
    conflictResolver = new ConflictResolver(config);
    JournaledBlockCollector jc = null;
    if (garbageCollection == DUMMY_GARBAGE_COLLECTOR) {
      dummyGarbageCollector = new TestGarbageCollector(new TestDirCollector(),
          new TestFileCollector(), new TestBlockCollector(), new TestZnodeCollector());
      kh = new KurmaHandler(rootClient, dummyGarbageCollector, config, conflictResolver);

    } else if (garbageCollection == JOURNALED_GARBAGE_COLLECTOR) {
      jc = new JournaledBlockCollector(config.getDefaultKvsFacade(), config.getGatewayId());
      journaledGarbageCollector = new TestJournaledGarbageCollector(new TestDirCollector(),
          new TestFileCollector(), jc, new TestZnodeCollector());
      kh = new KurmaHandler(rootClient, journaledGarbageCollector, config, conflictResolver);

    } else {
      garbageCollector = new DefaultCollector(client, config);
      kh = new KurmaHandler(rootClient, garbageCollector, config, conflictResolver);
    }

    vh = kh.createVolume(VolumeInfoHelper.newVolumeInfo(volumeId));
    rootDh = vh.getRootDirectory();
    zkClient = vh.getZkClient();

    if (garbageCollection == JOURNALED_GARBAGE_COLLECTOR) {
      jc.setup(vh);
    }
  }

  public static void startTestServerWithConfig(String configFile) throws Exception {
    server = new TestingServer(true);
    config = new GatewayConfig(configFile);
    rootClient = connectToServer(null);
    conflictResolver = new ConflictResolver(config);

    kh = new KurmaHandler(rootClient, garbageCollector, config, conflictResolver);
    client = kh.getClient();
    garbageCollector = new DefaultCollector(client, config);

    vh = kh.createVolume(VolumeInfoHelper.newVolumeInfo(volumeId));
    rootDh = vh.getRootDirectory();
    zkClient = vh.getZkClient();
  }

  public static Object[] toObjectArray(List<Object> items) {
    Object[] objs = new Object[items.size()];
    for (int i = 0; i < items.size(); ++i) {
      objs[i] = items.get(i);
    }
    return objs;
  }

  public static void closeTestServer() throws IOException{
    rootClient.close();
    vh.getTransactionManager().stop();
    vh.getJournalManager().closeAll();

    if (garbageCollector != null)
      garbageCollector.flush();
    if (dummyGarbageCollector != null)
      dummyGarbageCollector.flush();

    server.close();
  }

  public static String getConnectString() {
    return String.format("localhost:%d", server.getPort());
  }

  public static FileHandler createFileUnderRoot(String name) {
    return rootDh.createChildFile(name, AttributesHelper.newFileAttributes());
  }

  public static CuratorFramework connectToServer(String namespace) {
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework client = CuratorFrameworkFactory.builder().namespace(namespace)
        .retryPolicy(retryPolicy).connectString(getConnectString()).build();
    client.start();
    return client;
  }

  public static void cleanTestDirectory(String subDir) {
    try {
      File testDir = getTestDirectory(subDir).toFile();
      if (testDir.isDirectory()) {
        FileUtils.cleanDirectory(testDir);
      } else {
        FileUtils.deleteDirectory(testDir);
        assertTrue(testDir.mkdirs());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void assertParallel(final int nthreads, Function<Integer, Boolean> work)
      throws Exception {
    Thread[] threads = new Thread[nthreads];
    final CountDownLatch startSignal = new CountDownLatch(1);
    AtomicBoolean failed = new AtomicBoolean();
    for (int i = 0; i < nthreads; ++i) {
      final int index = i;
      threads[i] = new Thread() {
        @Override
        public void run() {
          try {
            startSignal.await();
          } catch (InterruptedException e) {
            e.printStackTrace();
            failed.set(true);
          }
          if (!work.apply(index)) {
            failed.set(true);
          }
        }
      };
      threads[i].start();
    }
    startSignal.countDown();
    for (int i = 0; i < nthreads; ++i) {
      threads[i].join();
    }
    assertFalse(failed.get());
  }

  public static void doParallel(final int nthreads, Consumer<Integer> work) throws Exception {
    Thread[] threads = new Thread[nthreads];
    for (int i = 0; i < nthreads; ++i) {
      final int index = i;
      threads[i] = new Thread() {
        @Override
        public void run() {
          work.accept(index);
        }
      };
      threads[i].start();
    }
    for (int i = 0; i < nthreads; ++i) {
      threads[i].join();
    }
  }

  protected RandomBuffer randomBuffer;

  public TestBase() {
    randomBuffer = new RandomBuffer(RANDOM_SEED);
  }

  protected void setRandomSeed(long seed) {
    randomBuffer.setRandomSeed(seed);
  }

  protected byte[] genRandomBytes(int len) {
    return randomBuffer.genRandomBytes(len);
  }

  protected ByteBuffer genRandomBuffer(int len) {
    return randomBuffer.genRandomBuffer(len);
  }

  private static File fetchJournalDirectory(IGatewayConfig config) throws Exception{
    File journal_directory = new File(config.getJournalDirectory());
    if (journal_directory.exists() && !journal_directory.canWrite()) {
      throw new Exception("No write access for journal directory");
    }
    if (!journal_directory.exists()) {
      if (!journal_directory.mkdirs())
        throw new Exception("No write access for journal directory");
    }
    return journal_directory;
  }
}
