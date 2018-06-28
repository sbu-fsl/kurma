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
package edu.stonybrook.kurma.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.runner.CaliperMain;

import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsManager;
import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.ProviderAccount;
import edu.stonybrook.kurma.util.ByteBufferBackedInputStream;
import edu.stonybrook.kurma.util.RandomBuffer;

/**
 * Benchmark the latency of read from and write to cloud providers
 *
 * @author mchen
 *
 */
public class CloudLatencyBench {
  // @Param({"google0", "amazon0", "azure0", "rackspace0"})
  // @Param({"azure0", "google0"})
  @Param({"amazon0"})
  String kvsId;

  // @Param({"16", "64", "256", "1024", "4096"})
  @Param({"16"})
  int sizeKb;

  private static String DEFAULT_CONFIG_FILE = "clouds.properties";

  private static AbstractConfiguration config = null;

  private Kvs getKvs() {
    ProviderAccount account = GatewayConfig.parseProviderAccount(config, kvsId);
    return KvsManager.newKvs(account.getType(), kvsId, account);
  }

  private RandomBuffer rand = new RandomBuffer(8887);

  @BeforeExperiment
  void setUp() {
    try {
      config = new PropertiesConfiguration(DEFAULT_CONFIG_FILE);
    } catch (ConfigurationException e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  @Benchmark
  void timeWrite(int reps) {
    int failure = 0;
    Kvs kvs = getKvs();
    int size = sizeKb * 1024;
    for (int i = 0; i < reps; ++i) {
      String key = "benchmark-key-" + i;
      ByteBuffer buf = rand.genRandomBuffer(size);
      InputStream input = new ByteBufferBackedInputStream(buf);
      try {
        kvs.put(key, input, size);
      } catch (IOException e) {
        ++failure;
        System.err.println(e.getMessage());
      }
    }
    System.err.printf("%d of %d writes failed on %s with %d-KB keys\n", failure, reps, kvsId,
        sizeKb);
  }

  public static void main(String[] args) {
    CaliperMain.main(CloudLatencyBench.class, args);
  }
}
