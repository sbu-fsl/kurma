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
package edu.stonybrook.kurma.server;

import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.gc.GarbageCollector;
import edu.stonybrook.kurma.helpers.VolumeInfoHelper;
import edu.stonybrook.kurma.meta.VolumeInfo;
import edu.stonybrook.kurma.replicator.ConflictResolver;
import edu.stonybrook.kurma.util.ZkUtils;

/**
 * Root handler of operations on Kurma's metadata.
 *
 * It is responsible for managing all volume handlers.
 *
 * @author mchen
 *
 */
public class KurmaHandler {
  private static String KURMA_ZK_NAMESPACE_PREFIX = "kurma-namespace-";

  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaHandler.class);

  // CuratorFramework is thread-safe, and will be shared among all
  // VolumeHandlers.
  private final CuratorFramework client;

  private final GarbageCollector gc;

  private final ConflictResolver conflictResolver;

  private final IGatewayConfig config;

  private ConcurrentHashMap<String, VolumeHandler> volumes;

  @Inject
  public KurmaHandler(CuratorFramework client, GarbageCollector gc, IGatewayConfig config2,
      ConflictResolver conflictResolver) throws Exception {
    String namespace = getZkNamespace(config2.getGatewayName());
    this.client = client.usingNamespace(namespace);
    this.gc = gc;
    this.config = config2;
    this.conflictResolver = conflictResolver;

    ZkUtils.ensurePath(client, ZKPaths.fixForNamespace(namespace, "/"), false);

    volumes = new ConcurrentHashMap<>();
    // TODO load volumes periodically so that volumes created by remote
    // gateways can be picked up
    loadVolumes();
  }

  /**
   * Load all volumes from ZooKeeper
   *
   * @return whether new volumes are loaded
   */
  private boolean loadVolumes() throws Exception {
    boolean changed = false;
    // load all volumes
    for (String volumeId : client.getChildren().forPath("/")) {
      if (!volumes.containsKey(volumeId)) {
        VolumeInfo vi = VolumeInfoHelper.newVolumeInfo(volumeId);
        VolumeHandler vh = new VolumeHandler(vi, getVolumeZpath(volumeId), client, gc, config,
            new BlockExecutor());
        if (vh.load()) {
          LOGGER.info("volume {} loaded at Gateway-{}.", volumeId, config.getGatewayName());
          volumes.putIfAbsent(volumeId, vh);
          changed = true;
        } else {
          LOGGER.error("failed to load volume {} at Gateway-{}. Ignored.", volumeId,
              config.getGatewayName());
        }
      }
    }
    return changed;
  }

  public Enumeration<String> getVolumes() {
    return volumes.keys();
  }

  /**
   * Get zpath root of the volume inside Kurma namespace.
   *
   * @param volumeId
   * @return
   */
  public String getVolumeZpath(String volumeId) {
    return "/" + volumeId;
  }

  public VolumeHandler createVolume(VolumeInfo vi) {
    String volumeId = vi.getId();
    LOGGER.debug("creating volume {} at Gateway-{}", volumeId, config.getGatewayName());
    VolumeHandler vh = null;
    try {
      vh = new VolumeHandler(vi, getVolumeZpath(volumeId), client, gc, config, new BlockExecutor());
      vh.create();
      volumes.put(volumeId, vh);
    } catch (Exception e) {
      LOGGER.error(String.format("could not create volume %s", volumeId), e);
      e.printStackTrace();
      return null;
    }

    return vh;
  }

  public boolean deleteVolume(String volumeId) {
    LOGGER.debug("deleting volume {}", volumeId);
    VolumeHandler vh = getVolumeHandler(volumeId);
    if (vh == null) {
      LOGGER.debug("volume {} does not exist", volumeId);
      return true; // Deleting an nonexisting volume is always a success.
    }
    try {
      // TODO remove all directories, files, and data blocks
      volumes.remove(volumeId);
      vh.delete();
    } catch (Exception e) {
      LOGGER.error(e.getMessage());
      e.printStackTrace();
      return false;
    }
    return true;
  }

  public VolumeHandler getVolumeHandler(String volumeId) {
    assert (volumeId != null);
    VolumeHandler vh = volumes == null ? null : volumes.get(volumeId);
    try {
      if (vh == null && loadVolumes()) {
        vh = volumes.get(volumeId);
      }
    } catch (Exception e) {
      LOGGER.error(e.getMessage());
      e.printStackTrace();
    }
    return vh;
  }

  public GarbageCollector getGarbageCollector() {
    return gc;
  }

  /**
   * Get ZooKeeper namespace of the given Kurma gateway's meta-data znodes.
   *
   * @param localGatewayName
   * @return ZooKeeper namespace
   */
  public static String getZkNamespace(String localGatewayName) {
    return KURMA_ZK_NAMESPACE_PREFIX + localGatewayName;
  }

  public ConflictResolver getConflictResolver() {
    return this.conflictResolver;
  }

  public CuratorFramework getClient() {
    return client;
  }

}
