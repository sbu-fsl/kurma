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

public class ProviderAccount {
  private String id;
  private String type;
  private String accessKey;
  private String secretKey;
  private String bucket;
  private int cost;
  private boolean enabled;

  public int getCost() {
    return cost;
  }

  public void setCost(int cost) {
    this.cost = cost;
  }

  public String getId() {
    return id;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public String getBucket() {
    return bucket;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getType() {
    return type;
  }

  public ProviderAccount(String id, String type, String aKey, String sKey, String bucket, int cost,
      boolean enabled) {
    this.id = id;
    this.type = type;
    this.accessKey = aKey;
    this.secretKey = sKey;
    this.bucket = bucket;
    this.cost = cost;
    this.enabled = enabled;
  }
}
