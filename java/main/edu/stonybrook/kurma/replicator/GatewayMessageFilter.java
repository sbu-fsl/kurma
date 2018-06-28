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

import java.io.IOException;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.hedwig.filter.ClientMessageFilter;
import org.apache.hedwig.filter.MessageFilterBase;
import org.apache.hedwig.filter.ServerMessageFilter;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import edu.stonybrook.kurma.helpers.HedwigMessageHelper;

/**
 * A Hedwig filter that ignores messages the local gateway sends by itself.
 * 
 * @author mchen
 *
 */
public class GatewayMessageFilter implements ServerMessageFilter, ClientMessageFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMessageFilter.class);
  private final short localGatewayId;

  public GatewayMessageFilter(short gatewayId) {
    this.localGatewayId = gatewayId;
  }

  @Override
  public MessageFilterBase setSubscriptionPreferences(ByteString topic, ByteString subscriptorId,
      SubscriptionPreferences preference) {
    // Do nothing.
    return this;
  }

  @Override
  public boolean testMessage(Message msg) {
    short gwid = HedwigMessageHelper.readSourceGateway(msg);
    if (gwid == 0) {
      LOGGER.error("malformed message ignored: without header");
      return false;
    }
    return gwid != localGatewayId;
  }

  @Override
  public ServerMessageFilter initialize(Configuration arg0)
      throws ConfigurationException, IOException {
    // Do nothing.
    return this;
  }

  @Override
  public void uninitialize() {
    // Do nothing.
  }

}
