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

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.gc.DefaultCollector;
import edu.stonybrook.kurma.gc.GarbageCollector;

public class KurmaModule extends AbstractModule {

  @Override
  protected void configure() {
    bind(GarbageCollector.class).to(DefaultCollector.class);
    bind(IGatewayConfig.class).to(GatewayConfig.class);
  }

  @Provides
  CuratorFramework provideCuratorFramework() {
    String connectString = "localhost:2181";
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework client = CuratorFrameworkFactory.builder().connectString(connectString)
        .retryPolicy(retryPolicy).build();
    client.start();
    return client;
  }

}
