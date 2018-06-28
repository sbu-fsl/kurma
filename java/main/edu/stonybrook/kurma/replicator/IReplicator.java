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
 * Interface for replicating (synchronizing) files among gateways.
 * 
 * @author mchen
 *
 */
public interface IReplicator {
  /**
   * Get global-unique ID of the object this replicator is responsible for. For example,
   * VolumeHandler's ID can be the volumeId.
   * 
   * @return replicationId
   */
  public String getReplicationId();

  /**
   * Publish a local change to all other gateways.
   * 
   * @param msg the message that encodes the local change
   * @return whether the operation is successful
   */
  public boolean broadcast(GatewayMessage msg);

}
