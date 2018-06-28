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

import edu.stonybrook.kurma.message.GatewayMessage;

/**
 * A replicator that does nothing. Used mainly for testing.
 * 
 * @author mchen
 *
 */
public final class NullReplicator implements IReplicator {

  private final String volumeId;

  public NullReplicator(String volumeId) {
    this.volumeId = volumeId;
  }

  @Override
  public boolean broadcast(GatewayMessage msg) {
    return true;
  }

  @Override
  public String getReplicationId() {
    return volumeId;
  }

}
