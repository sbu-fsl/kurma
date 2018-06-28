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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.utils.EnsurePath;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.server.KurmaHandler;

@SuppressWarnings("deprecation")
public class CuratorTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(CuratorTest.class);

  @BeforeClass
  public static void setUp() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    closeTestServer();
  }

  @Test
  public void testCuratorNamespace() throws Exception {
    String namespace = KurmaHandler.getZkNamespace(config.getLocalGateway().getName());
    client.create().creatingParentsIfNeeded().forPath("/test1");
    String absPath = ZKPaths.makePath(namespace, "test1");
    assertNotNull(client.usingNamespace(null).checkExists().forPath(absPath));
    assertNotNull(client.checkExists().forPath("/test1"));
    assertNotNull(client.usingNamespace(namespace).checkExists().forPath("/test1"));
  }

  @Test
  public void testCuratorTransaction() throws Exception {
    byte[] data = genRandomBytes(64);
    client.create().forPath("/txn1", data);
    client.create().forPath("/txn2", data);

    Collection<CuratorTransactionResult> results = client.inTransaction().setData()
        .forPath("/txn1", data).and().setData().forPath("/txn2", data).and().commit();
    Iterator<CuratorTransactionResult> it = results.iterator();
    assertTrue(it.hasNext());
    assertNotNull(it.next().getResultStat());
    assertTrue(it.hasNext());
    assertNotNull(it.next().getResultStat());
    assertFalse(it.hasNext());

    exception.expect(NoNodeException.class);
    results = client.inTransaction().setData().forPath("/txn1", data).and().setData()
        .forPath("/non-exist", data).and().commit();
  }

  @Test
  public void testRemoveFileTransaction() throws Exception {
    byte[] data = genRandomBytes(16);
    client.create().forPath("/testfile", data);
    client.create().forPath("/testfile/BLOCKMAP.1", data);

    exception.expect(KeeperException.NotEmptyException.class);
    client.inTransaction().delete().forPath("/testfile").and().commit();
  }

  @Test
  public void testListChildren() throws Exception {
    CuratorFramework rootClient = client.usingNamespace(null);
    final String objectUnderRoot = "testListChildrenUnderRoot";
    rootClient.create().forPath("/" + objectUnderRoot);
    CuratorFramework namespacedClient = client.usingNamespace("namespace");
    final String objectUnderNamespace = "fileUnderNameSpace";
    namespacedClient.create().forPath("/" + objectUnderNamespace);

    List<String> objects = rootClient.getChildren().forPath("/");
    LOGGER.debug(objects.toString());
    assertTrue(objects.contains(objectUnderRoot));
    assertFalse(objects.contains(objectUnderNamespace));

    objects = namespacedClient.getChildren().forPath("/");
    LOGGER.debug(objects.toString());
    assertFalse(objects.contains(objectUnderRoot));
    assertTrue(objects.contains(objectUnderNamespace));
  }

  @Test
  public void testVersionMismatch() throws Exception {
    byte[] data = genRandomBytes(16);
    client.create().forPath("/versiontest");
    Stat stat = client.setData().forPath("/versiontest", data);

    exception.expect(KeeperException.BadVersionException.class);
    client.setData().withVersion(stat.getVersion() - 1).forPath("/versiontest", data);
  }

  @Test
  public void testCreateZnodeWithNoExistingParent() throws Exception {
    final String zpath = "/NotExistsParent/foo";
    CuratorTransaction txn = client.inTransaction();
    exception.expect(org.apache.zookeeper.KeeperException.NoNodeException.class);
    txn.create().forPath(zpath).and().commit();
  }

  @Test
  public void testZKPathMkdir() throws Exception {
    String zpath = "/a/b/c/d";
    // mkdirs will not prepend namespace
    ZKPaths.mkdirs(client.getZookeeperClient().getZooKeeper(), zpath);
    assertNull(client.checkExists().forPath(zpath));
    assertNotNull(client.usingNamespace(null).checkExists().forPath(zpath));
  }

  @Test
  public void testEmptyParentInTransaction() throws Exception {
    String zpath = "/nonexist/testEmptyParentInTransaction";
    exception.expect(KeeperException.NoNodeException.class);
    client.inTransaction().create().forPath(zpath).and().commit();
  }

  @Test
  public void testCreateExistingZpath() throws Exception {
    String zpath = "/nonexist/testCreateExistingZpath";
    ZKPaths.mkdirs(client.getZookeeperClient().getZooKeeper(), zpath);
    assertNotNull(client.usingNamespace(null).checkExists().forPath(zpath));
    exception.expect(KeeperException.NodeExistsException.class);
    client.usingNamespace(null).inTransaction().create().forPath(zpath).and().commit();
  }

  /**
   * Test that EnsurePath does not take namespace.
   *
   * @throws Exception
   */
  @Test
  public void testEnsurePath() throws Exception {
    String zpath = "/a/b/c";
    EnsurePath ep = new EnsurePath(zpath);
    ep.ensure(client.getZookeeperClient());
    assertNotNull(client.usingNamespace(null).checkExists().forPath(zpath));
    assertNull(client.checkExists().forPath(zpath));
  }

  @Test
  public void testParallelTransaction() throws Exception {
    doParallel(20, nth -> {
      CuratorTransaction transaction = client.inTransaction();
      try {
        for (int i = 0; i < 100; i++) {
          byte data[] = (""+nth+"-"+i).getBytes();
          transaction = transaction.create()
              .forPath("/CommitFile" + nth + "," + i, data).and();
        }
        ((CuratorTransactionFinal) transaction).commit();
        LOGGER.info("Transaction commited" + nth);
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
    for (int i=0; i<20; i++) {
      for (int j=0; j<100; j++) {
        byte data[] = (""+i+"-"+j).getBytes();
        byte readData[] = client.getData().forPath("/CommitFile" + i + "," + j);
        assert(Arrays.equals(data, readData));
      }
    }
  }

}
