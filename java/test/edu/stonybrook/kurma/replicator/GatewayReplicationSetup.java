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
package edu.stonybrook.kurma.replicator;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.fs.KurmaResult;
import edu.stonybrook.kurma.gc.DefaultCollector;
import edu.stonybrook.kurma.gc.GarbageCollector;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.StatusHelper;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.KurmaHandler;
import edu.stonybrook.kurma.server.KurmaServiceHandler;
import edu.stonybrook.kurma.server.SessionManager;
import edu.stonybrook.kurma.util.RandomBuffer;

/*
 * Creates two gateways running from a single machine Used when actual message replication between
 * them is desired
 */
public class GatewayReplicationSetup extends ReplicatorTestBase {
  protected static Logger LOGGER = LoggerFactory.getLogger(KurmaReplicatorTest.class);
  protected static TestingServer zkServer;
  protected static CuratorFramework curator;
  protected static ConflictResolver conflictResolver;

  protected KurmaHandler localKurmaHandler;
  protected KurmaHandler remoteKurmaHandler;

  protected IGatewayConfig localConfig;
  protected IGatewayConfig remoteConfig;

  protected KurmaReplicator localReplicator;
  protected KurmaReplicator remoteReplicator;

  protected SessionManager localSessionManager;
  protected SessionManager remoteSessionManager;

  protected KurmaServiceHandler localService;
  protected KurmaServiceHandler remoteService;

  protected static boolean testingVolumeCreated = false;
  protected RandomBuffer randomBuffer;

  protected static final int HEDWIG_DELAY_MS = 25000;
  protected static final String TEST_VOLUME_NAME = "KurmaReplicatorTestVolume";

  protected static final int BLOCKSIZE = 64 * 1024;

  protected static KurmaHandler buildKurmaHandler(IGatewayConfig config) throws Exception {
    String zkConnectString = String.format("localhost:%d", zkServer.getPort());
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    curator = CuratorFrameworkFactory.builder().retryPolicy(retryPolicy)
        .connectString(zkConnectString).build();
    curator.start();
    conflictResolver = new ConflictResolver(config);
    GarbageCollector gc = new DefaultCollector(curator, config);
    KurmaHandler kh = new KurmaHandler(curator, gc, config, conflictResolver);
    return kh;
  }

  public String getLocalZpath(String volume) {
    return "/" + KurmaHandler.getZkNamespace(localConfig.getGatewayName())
        + localKurmaHandler.getVolumeZpath(volume);
  }

  public String getRemoteZpath(String volume) {
    return "/" + KurmaHandler.getZkNamespace(remoteConfig.getGatewayName())
        + remoteKurmaHandler.getVolumeZpath(volume);
  }

  /**
   * Test that a volume created locally is also created remotely.
   *
   * @throws Exception
   */
  public void createTestingVolume() throws Exception {
    if (!testingVolumeCreated) {
      KurmaResult kr = localService.format_volume(TEST_VOLUME_NAME, 0);
      assertTrue(StatusHelper.isOk(kr.getStatus()));
      assertNotNull(
          curator.usingNamespace(null).checkExists().forPath(getLocalZpath(TEST_VOLUME_NAME)));

      Thread.sleep(HEDWIG_DELAY_MS);
      assertNotNull(
          curator.usingNamespace(null).checkExists().forPath(getRemoteZpath(TEST_VOLUME_NAME)));
      testingVolumeCreated = true;
    }
  }

  protected ByteBuffer createSession(KurmaServiceHandler service) throws Exception {
    ByteBuffer clientId = randomBuffer.genRandomBuffer(32);
    KurmaResult kr = service.create_session(clientId, TEST_VOLUME_NAME);
    assertTrue(StatusHelper.isOk(kr.getStatus()));
    return ByteBuffer.wrap(kr.getSessionid());
  }

  protected KurmaResult listdirRemote(ObjectID dirOid) throws Exception {
    ByteBuffer sessionId = createSession(remoteService);
    KurmaResult kr = remoteService.listdir(sessionId, dirOid);
    return kr;
  }

  protected KurmaResult listRootDirRemote() throws Exception {
    return listdirRemote(ObjectIdHelper.getRootOid(remoteConfig.getGatewayId()));
  }

  protected KurmaResult getattrs(KurmaServiceHandler service, ObjectID oid, String path)
      throws Exception {
    ByteBuffer sessionId = createSession(service);
    KurmaResult kr = service.lookup(sessionId, oid, path);
    return kr;
  }

  protected KurmaResult getattrsLocal(String path) throws Exception {
    return getattrs(localService, ObjectIdHelper.getRootOid(localConfig.getGatewayId()), path);
  }

  protected KurmaResult getattrsRemote(String path) throws Exception {
    return getattrs(remoteService, ObjectIdHelper.getRootOid(remoteConfig.getGatewayId()), path);
  }
}
