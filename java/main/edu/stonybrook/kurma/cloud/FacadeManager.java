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
package edu.stonybrook.kurma.cloud;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import edu.stonybrook.kurma.config.IGatewayConfig;

public class FacadeManager {
  private ConcurrentHashMap<String, KvsFacade> facades = new ConcurrentHashMap<>();
  private KvsManager kvsManager;
  private IGatewayConfig config;
  private KvsFacade defaultFacade;

  public FacadeManager(KvsManager kvsManager, IGatewayConfig config) {
    this.kvsManager = kvsManager;
    this.config = config;
    this.defaultFacade = config.getDefaultKvsFacade();
    facades.put(defaultFacade.getFacadeKey(), defaultFacade);
  }

  private String buildKey(String type, String ids) {
    return type + "+" + ids;
  }

  public KvsFacade findOrBuild(final String kvsType, final String kvsIds) {
    String key = buildKey(kvsType, kvsIds);
    return facades.computeIfAbsent(key, k -> {
      try {
        List<Kvs> kvsList = kvsManager.stringToKvsList(kvsIds);
        return KvsFacade.newFacade(kvsType, kvsList, config.getKvsSortPeriod());
      } catch (Exception e) {
        return null;
      }
    });
  }

  public KvsFacade getDefaultFacade() {
    return defaultFacade;
  }
}
