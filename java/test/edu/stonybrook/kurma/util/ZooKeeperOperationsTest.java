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

import java.util.Collection;
import static org.junit.Assert.assertFalse;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.util.RandomBuffer;

public class ZooKeeperOperationsTest {
  protected static final int RANDOM_SEED = 8839;
  protected static RandomBuffer randomBuffer;
  
  static byte data[];
  static RetryPolicy retryPolicy;

  @BeforeClass
  public static void setup() {
    randomBuffer = new RandomBuffer(RANDOM_SEED);
    data = genRandomBytes(64);
    retryPolicy = new ExponentialBackoffRetry(1000, 3);
  }

  @Test
  public void testSetup() throws Exception {
    TestingServer server = new TestingServer();
    server.start();
    CuratorFramework client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
    client.start();
    client.create().forPath("/TESTSETUP", data);
    client.delete().deletingChildrenIfNeeded().forPath("/TESTSETUP");
    client.close();
    server.close();
  }

  @Test
  public void testCreateWithDuplicateNames() throws Exception {
    for (int i = 0; i < 2; i++) {
      TestingServer server = new TestingServer();
      server.start();
      CuratorFramework client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      justCreate(client, "test_");
      client.close();
      server.close();
    }
  }

  @Test
  public void testCreateWithUniqueNames() throws Exception {
    for (int i = 0; i < 2; i++) {
      TestingServer server = new TestingServer();
      server.start();
      CuratorFramework client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      justCreate(client, "test_" + i);
      client.close();
      server.close();
    }
  }

  @Test
  public void testCreateDeleteWithDuplicateNames() throws Exception {
    for (int i = 0; i < 2; i++) {
      TestingServer server = new TestingServer();
      server.start();
      CuratorFramework client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      createDelete(client, "test");
      client.close();
      server.close();
    }
  }

  @Test
  public void testCreateDeleteWithUniqueNames() throws Exception {
    for (int i = 0; i < 2; i++) {
      TestingServer server = new TestingServer();
      server.start();
      CuratorFramework client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      createDelete(client, "test_I_" + i);
      client.close();
      server.close();

    }
  }

  protected static byte[] genRandomBytes(int len) {
    return randomBuffer.genRandomBytes(len);
  }

  public static String getConnectString(TestingServer server) {
    return String.format("localhost:%d", server.getPort());
  }

  public static void justCreate(CuratorFramework client, String suffix) {
    try {
      client.create().forPath("/Test" + suffix, data);
      Collection<CuratorTransactionResult> results =
          client.inTransaction().create().forPath("/CommitFile" + suffix, "some data".getBytes())
              .and()
              .create().forPath("/CommitFile2" + suffix)
              .and()
              .commit();

      for (CuratorTransactionResult result : results) {
        System.out.println(result.getForPath() + " - " + result.getType());
      }
    } catch (Exception e) {
      assertFalse(true);
    }
  }

  public static void createDelete(CuratorFramework client, String suffix) {
    try {
      client.create().forPath("/Test" + suffix, data);
      client.delete().forPath("/Test" + suffix);

      Collection<CuratorTransactionResult> results =
          client.inTransaction().create().forPath("/CommitFile" + suffix, data)
            .and()
            .create().forPath("/CommitFile2" + suffix)
            .and()
            .commit();
      Collection<CuratorTransactionResult> results2 =
          client.inTransaction().delete().forPath("/CommitFile" + suffix)
            .and()
            .delete().forPath("/CommitFile2" + suffix)
            .and()
            .commit();
      
      for (CuratorTransactionResult result : results) {
        System.out.println(result.getForPath() + " - " + result.getType());
      }
      for (CuratorTransactionResult result : results2) {
        System.out.println(result.getForPath() + " - " + result.getType());
      }
    } catch (Exception e) {
      assertFalse(true);
    }
    assertFalse(false);

  }

}
