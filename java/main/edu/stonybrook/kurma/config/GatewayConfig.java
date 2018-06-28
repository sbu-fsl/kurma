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

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.util.HedwigSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import secretsharing.CDCodecJNI;
import edu.stonybrook.kurma.KurmaGateway;
import edu.stonybrook.kurma.cloud.ErasureFacade;
import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.cloud.KvsManager;
import edu.stonybrook.kurma.cloud.ReplicationFacade;
import edu.stonybrook.kurma.cloud.SecretSharingFacade;

public class GatewayConfig implements IGatewayConfig {
  public static final String KURMA_DEFAULT_CONFIG_FILE = "kurma.properties";
  public static final String KURMA_LOCAL_CONFIG_FILE = "local-kurma.properties";
  public static final String KURMA_TEST_CONFIG_FILE = "test-kurma.properties";
  public static final String KURMA_REMOTE_CONFIG_FILE = "remote-kurma.properties";
  public static final String KURMA_CLOUD_CONFIG_FILE = "cloud-config-test.properties";
  public static final String KURMA_KVS_SEPARATOR = ";";

  /**
   * Configure keys to read the parameters of key-value store. The placeholder should be replaced by
   * the ID of the KV store.
   */
  public static final String C_TYPE = "kurma.kvs.drivers.%s.type";
  public static final String C_AKEY = "kurma.kvs.drivers.%s.akey";
  public static final String C_SKEY = "kurma.kvs.drivers.%s.skey";
  public static final String C_BUCKET = "kurma.kvs.drivers.%s.bucket";
  public static final String C_ENABLED = "kurma.kvs.drivers.%s.enabled";
  public static final String C_COST = "kurma.kvs.drivers.%s.cost";

  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayConfig.class);

  private KurmaGateway localGateway;
  private HashMap<Short, KurmaGateway> gateways;
  private HashMap<Short, KurmaGateway> deleteGateways;

  private CompositeConfiguration config;

  private KvsFacade kvsFacade;

  private ClientConfiguration hedwigConfig;

  private KvsManager kvsManager;

  private int kvsSortPeriodSec;
  private int blockShift;

  public GatewayConfig(String configFile) throws Exception {
    LOGGER.info("Kurma config file: {}", configFile);
    LOGGER.info("java.library.path: {}", System.getProperty("java.library.path"));
    try {
      config = new CompositeConfiguration();
      PropertiesConfiguration pconfig = new PropertiesConfiguration(configFile);
      LOGGER.info("Kuram config file path: {}", pconfig.getBasePath());
      config.addConfiguration(pconfig);
      String name = config.getString("kurma.gateway");
      String priKeyFile = config.getString("kurma.gateway.private.keyfile");
      String pubKeyDir = config.getString("kurma.gateways.public.keyfile.dir");

      gateways = new HashMap<>();
      for (String gw : config.getStringArray("kurma.gateways")) {
        if (gw.equals(name)) {
          localGateway =
              new KurmaGateway(gw, buildPubKeyFile(pubKeyDir, gw), getResourceFilePath(priKeyFile));
          gateways.put(localGateway.getId(), localGateway);
        } else {
          KurmaGateway kg = new KurmaGateway(gw, buildPubKeyFile(pubKeyDir, gw));
          gateways.put(kg.getId(), kg);
        }
      }

      blockShift = config.getInt("kurma.block.shift", 0);
      kvsSortPeriodSec = config.getInt("kurma.cloud.kvs.sort.period", Integer.MAX_VALUE);
      kvsManager = new KvsManager((1 << blockShift), kvsSortPeriodSec);

      deleteGateways = new HashMap<>();
      String deletedKeyDir = config.getString("kurma.deleted.gateways.keyfile.dir");
      for (String deletedGw : config.getStringArray("kurma.deleted.gateways")) {
        String filePath =
            Paths.get(deletedKeyDir, String.format("%s-pri.der", deletedGw)).toString();
        KurmaGateway kg = new KurmaGateway(deletedGw, buildPubKeyFile(pubKeyDir, deletedGw),
            getResourceFilePath(filePath));
        deleteGateways.put(kg.getId(), kg);
      }

      parseKvsProviders(config.getString("kurma.cloud.kvs.replication", "replication"),
          config.getString("kurma.cloud.kvs.providers"));

      String replicator = config.getString("kurma.replicator", null);
      if (replicator == null) {
        LOGGER.error("using the default NullReplicator");
        hedwigConfig = null;
      } else if (!"hedwig".equals(replicator)) {
        LOGGER.error("unknown replicator type: {}; the only supported type is 'hedwig'",
            replicator);
        System.exit(1);
      } else {
        String hedwigServer = config.getString("kurma.hedwig.hub", "localhost");
        LOGGER.info("Connecting to hedwig server: {}", hedwigServer);
        hedwigConfig = new ClientConfiguration() {
          @Override
          public HedwigSocketAddress getDefaultServerHedwigSocketAddress() {
            return new HedwigSocketAddress(hedwigServer, 4080, 9876);
          }

          @Override
          public boolean isSSLEnabled() {
            return config.getBoolean("kurma.hedwig.ssl", true);
          }

          @Override
          public int getConsumedMessagesBufferSize() {
            return 1;
          }
        };
      }

    } catch (Exception e) {
      LOGGER.error(e.getMessage());
      e.printStackTrace();
      throw e;
    }
  }

  @Override
  public KurmaGateway getGateway(short gwid) {
    return gateways.get(gwid);
  }

  @Override
  public Collection<KurmaGateway> getGateways() {
    return gateways.values();
  }

  private Kvs parseKvs(String kvsId) {
    Kvs kvs = kvsManager.getKvs(kvsId);
    if (kvs == null) {
      ProviderAccount account = getProviderAccount(kvsId);
      kvs = KvsManager.newKvs(account.getType(), kvsId, account);
      kvsManager.addKvs(kvsId, kvs);
    }
    return kvs;
  }

  private void parseKvsProviders(String replicationType, String providerString) throws Exception {
    LOGGER.info("Cloud providers: {}", providerString);
    String[] providers = providerString.split(KURMA_KVS_SEPARATOR);
    List<Kvs> kvsList = new ArrayList<Kvs>();
    for (String kvsId : providers) {
      Kvs kvs = parseKvs(kvsId);
      if (kvs == null) {
        LOGGER.warn("failed to parse Kvs string {}; ignoring", kvsId);
        continue;
      } else {
        LOGGER.info("Cloud provider {} loaded", kvsId);
      }
      kvsList.add(kvs);
    }

    KvsFacade facade = null;

    if (replicationType == null || replicationType.isEmpty()) {
      LOGGER.warn("replicationType not specified, using the default in-memory key-value store");
      facade = new ReplicationFacade(kvsList, getKvsSortPeriod());
    } else if ("replication".equals(replicationType)) {
      facade = new ReplicationFacade(kvsList, getKvsSortPeriod());
    } else if ("erasure".equals(replicationType)) {
      int dataBlocks = config.getInt("kurma.cloud.kvs.erasure.k");
      int codingBlocks = config.getInt("kurma.cloud.kvs.erasure.m");
      if (dataBlocks == 0 || codingBlocks == 0 || dataBlocks < codingBlocks
          || (dataBlocks + codingBlocks) != kvsList.size()) {
        LOGGER.error("Numbers of data and coding blocks are incorrect "
            + "or list of providers are not equal to total blocks used for erasure");
        System.exit(1);
      }
      facade = new ErasureFacade(dataBlocks, codingBlocks, kvsList, getKvsSortPeriod());
    } else if ("secretsharing".equals(replicationType)) {
      String typeStr = config.getString("kurma.cloud.kvs.secretsharing.algorithm");
      int type = CDCodecJNI.AONT_RS;
      if ("CRSSS".equals(typeStr)) {
        type = CDCodecJNI.CRSSS;
      } else if ("AONT_RS".equals(typeStr)) {
        type = CDCodecJNI.AONT_RS;
      } else if ("OLD_CAONT_RS".equals(typeStr)) {
        type = CDCodecJNI.OLD_CAONT_RS;
      } else if ("CAONT_RS".equals(typeStr)) {
        type = CDCodecJNI.CAONT_RS;
      } else {
        LOGGER.warn("unknow secretsharing algorithm {}: using the default AONT_RS algorithm"
            + "valid algirithms are CRSSS, AONT_RS, OLD_CAONT_RS, and CAONT_RS", typeStr);
        type = CDCodecJNI.AONT_RS;
      }
      int n = config.getInt("kurma.cloud.kvs.secretsharing.n");
      int m = config.getInt("kurma.cloud.kvs.secretsharing.m");
      int r = config.getInt("kurma.cloud.kvs.secretsharing.r");
      if (n < kvsList.size()) {
        LOGGER.error("Need {} KV providers but provided only {}", n, kvsList.size());
        System.exit(1);
      }
      facade = new SecretSharingFacade(type, n, m, r, kvsList, getKvsSortPeriod());
    } else {
      LOGGER.error(
          "unknown replication type: '{}'; "
              + "supported types are 'replication', 'erasure', and 'secretsharing'",
          replicationType);
      System.exit(1);
    }

    setKvsFacade(facade);
  }

  @Override
  public KvsFacade getDefaultKvsFacade() {
    return kvsFacade;
  }

  public void setKvsFacade(KvsFacade kvsFacade) {
    this.kvsFacade = kvsFacade;
  }

  private String buildPubKeyFile(String dir, String gatewayName) {
    String filePath = Paths.get(dir, String.format("%s-pub.der", gatewayName)).toString();
    LOGGER.debug("reading public key from {}", filePath);
    return getResourceFilePath(filePath);
  }

  private String getResourceFilePath(String resourcePath) {
    return GatewayConfig.class.getResource(resourcePath).getPath();
  }

  @Override
  public short getGatewayId() {
    return localGateway.getId();
  }

  @Override
  public String getGatewayName() {
    return localGateway.getName();
  }

  @Override
  public KurmaGateway getLocalGateway() {
    return localGateway;
  }

  @Override
  public int getIdAllocationUnit() {
    return config.getInt("kurma.id.allocation.unit", 1024);
  }

  @Override
  public int getDirectoryCacheSize() {
    return config.getInt("kurma.directory.cache.size", 10240);
  }

  @Override
  public int getFileCacheSize() {
    return config.getInt("kurma.file.cache.size", 102400);
  }

  @Override
  public int getSessionTimeout() {
    return config.getInt("kurma.session.timeout.seconds");
  }

  @Override
  public ClientConfiguration getHedwigConfig() {
    return hedwigConfig;
  }

  @Override
  public int getNegativeCacheSize() {
    return config.getInt("kurma.volume.negative.cache.size", 1024);
  }

  @Override
  public String getJournalDirectory() {
    return config.getString("kurma.gateway.journal.dir", "/tmp/Journals/");
  }

  @Override
  public int getJournalCleanFrequency() {
    return config.getInt("kurma.gateway.journal.cleanup.frequency", 10);
  }

  @Override
  public int getTransactionCommitCount() {
    return config.getInt("kurma.gateway.transaction.commit.count", 50);
  }

  @Override
  public int getTransactionCommitInterval() {
    return config.getInt("kurma.gateway.transaction.commit.interval", 1);
  }

  // NOTE: If deleting this method, remove it from parent interface
  @Override
  public ProviderAccount getProviderAccount(String kvsId) {
    return parseProviderAccount(config, kvsId);
  }

  public static ProviderAccount parseProviderAccount(AbstractConfiguration config, String kvsId) {
    String kvsType = config.getString(String.format(C_TYPE, kvsId), null);
    String accessKey = config.getString(String.format(C_AKEY, kvsId), null);
    String secretKey = config.getString(String.format(C_SKEY, kvsId), null);
    String bucket = config.getString(String.format(C_BUCKET, kvsId), null);
    int cost = config.getInt(String.format(C_COST, kvsId), 1);
    boolean enabled = config.getBoolean(String.format(C_ENABLED, kvsId), true);
    return new ProviderAccount(kvsId, kvsType, accessKey, secretKey, bucket, cost, enabled);
  }

  // private static GatewayConfig instance = null;
  //
  // public static GatewayConfig getInstance() {
  // if (instance == null) {
  // instance = new GatewayConfig(KURMA_DEFAULT_CONFIG_FILE);
  // }
  // return instance;
  // }

  @Override
  public boolean getAutoSnapshotBeforeRestore() {
    return config.getBoolean("kurma.auto.snapshot.before.restore", false);
  }

  @Override
  public int getBlockShift() {
    return this.blockShift;
  }

  @Override
  public KurmaGateway getDeletedGateway(short gwid) {
    if (deleteGateways == null) {
      return null;
    }
    return deleteGateways.get(gwid);
  }

  @Override
  public boolean isFileHoleSupported() {
    return config.getBoolean("kurma.support.file.holes", true);
  }

  @Override
  public int getMaxServerThreads() {
    return config.getInt("kurma.max.server.threads", 16);
  }

  @Override
  public int getRequestTimeoutSeconds() {
    return config.getInt("kurma.request.timeout.seconds", 120);
  }

  @Override
  public int getKvsSortPeriod() {
    return this.kvsSortPeriodSec;
  }

  @Override
  public KvsManager getKvsManager() {
    return kvsManager;
  }

  @Override
  public int getReplicationThreads() {
    return config.getInt("kurma.replication.threads", 8);
  }

  @Override
  public boolean useKeyMap() {
    return config.getBoolean("kurma.replication.use.keymap", true);
  }

  @Override
  public boolean compressMetadata() {
    return config.getBoolean("kurma.metadata.compress", true);
  }

  @Override
  public String getTimeStatsFile() {
    return config.getString("kurma.stats.time.file", "/tmp/kurma-time-stats.txt");
  }
}
