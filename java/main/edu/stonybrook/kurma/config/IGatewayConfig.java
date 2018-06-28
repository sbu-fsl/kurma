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

import java.util.Collection;

import org.apache.hedwig.client.conf.ClientConfiguration;

import edu.stonybrook.kurma.KurmaGateway;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.cloud.KvsManager;

public interface IGatewayConfig {

  public KurmaGateway getGateway(short gwid);

  public Collection<KurmaGateway> getGateways();

  public short getGatewayId();

  public String getGatewayName();

  public KurmaGateway getLocalGateway();

  public int getIdAllocationUnit();

  public int getDirectoryCacheSize();

  public int getFileCacheSize();

  public int getNegativeCacheSize();

  public KvsFacade getDefaultKvsFacade();

  public int getSessionTimeout();

  public int getMaxServerThreads();

  public int getRequestTimeoutSeconds();

  public boolean getAutoSnapshotBeforeRestore();

  public boolean isFileHoleSupported();

  public String getJournalDirectory();

  public int getJournalCleanFrequency();
  
  public int getTransactionCommitInterval();
  
  public int getTransactionCommitCount();

  /**
   * Get the shift of the block size. It can be zero, which means the block size is dynamically
   * determined.
   *
   * @return log2 of the block size.
   */
  public int getBlockShift();

  /**
   * Get the specified deleted gateway this gateway represents.
   *
   * @param gwid The Id of the deleted gateway
   * @return The deleted gateway or null if not exist.
   */
  public KurmaGateway getDeletedGateway(short gwid);

  public ClientConfiguration getHedwigConfig();

  public int getKvsSortPeriod();

  ProviderAccount getProviderAccount(String provider);

  public KvsManager getKvsManager();

  public int getReplicationThreads();

  public boolean useKeyMap();

  public boolean compressMetadata();

  public String getTimeStatsFile();
}
