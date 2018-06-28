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
package edu.stonybrook.kurma.transaction;

import static edu.stonybrook.kurma.util.LoggingUtils.hash;
import edu.stonybrook.kurma.records.ZKOperationType;

public class OperationInfo {
  private ZKOperationType type;
  private String path;
  private long timestamp;
  private ResultProcessor rp;
  private byte[] data;
  private int version;
  private boolean ignoreVersion;

  public OperationInfo(ZKOperationType type, String path, long timestamp,
      ResultProcessor rp, byte[] data) {
    this.type = type;
    this.path = path;
    this.timestamp = timestamp;
    this.rp = rp;
    this.data = data;
  }

  public ZKOperationType getType() {
    return type;
  }

  public String getPath() {
    return path;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public ResultProcessor getRp() {
    return rp;
  }

  public byte[] getData() {
    return data;
  }

  @Override
  public String toString() {
    if (ZKOperationType.CREATE.equals(type)) {
      return "CREATE " + path + " with data " + hash(data);
    } else if (ZKOperationType.UPDATE.equals(type)) {
      return "UPDATE " + path + " to " + hash(data);
    } else if (ZKOperationType.REMOVE.equals(type)) {
      return "REMOVE " + path;
    } else if (ZKOperationType.COMMIT.equals(type)) {
      return "COMMIT";
    } else {
      return "Unknown operation: " + type;
    }
  }

  public int getVersion() {
    return version;
  }

  public boolean isIgnoreVersion() {
    return ignoreVersion;
  }

  public void setIgnoreVersion(boolean ignoreVersion) {
    this.ignoreVersion = ignoreVersion;
  }

  public void setVersion(int version) {
    this.version = version;
  }
}
