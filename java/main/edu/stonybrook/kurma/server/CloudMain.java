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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsManager;
import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.ProviderAccount;

public class CloudMain {

  public static void main(String[] args) {

    AbstractConfiguration config = null;
    try {
      config = new PropertiesConfiguration("clouds.properties");
    } catch (ConfigurationException e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }

    String[] kvsIds = new String[] {"amazon0", "azure0", "google0", "rackspace0"};
    List<Kvs> kvsList = new ArrayList<>();
    for (String kvsId : kvsIds) {
      ProviderAccount account = GatewayConfig.parseProviderAccount(config, kvsId);
      Kvs kvs = KvsManager.newKvs(account.getType(), kvsId, account);
      kvsList.add(kvs);
    }

    for (Kvs kvs : kvsList) {
      System.out.printf("##### cleaning up cloud %s #####\n", kvs.getId());
      try {
        long count = Kvs.emptyStorageContainer(kvs);
        System.out.printf("deleted %d objects\n", count);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

}
