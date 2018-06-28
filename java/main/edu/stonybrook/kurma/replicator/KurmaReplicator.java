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

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hedwig.client.HedwigClient;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.client.api.Publisher;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.exceptions.AlreadyStartDeliveryException;
import org.apache.hedwig.client.exceptions.InvalidSubscriberIdException;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.exceptions.PubSubException.ClientAlreadySubscribedException;
import org.apache.hedwig.exceptions.PubSubException.ClientNotSubscribedException;
import org.apache.hedwig.exceptions.PubSubException.CouldNotConnectException;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.filter.ClientMessageFilter;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionOptions;
import org.apache.hedwig.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.HedwigMessageHelper;
import edu.stonybrook.kurma.message.GatewayMessage;

/**
 * Replicate all file systems of the gateway by exchanging messages with other gateways.
 *
 * @author mchen
 *
 */
public final class KurmaReplicator implements IReplicator {
  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaReplicator.class);
  public static final String ROOT_REPLICATOR_ID = "__KURMA_REPLICATOR__";
  private final IGatewayConfig gateway;
  private final HedwigClient hedwigClient;
  private final Publisher publisher;
  private final Subscriber subscriber;

  private final MessageHandler handler;
  private final ClientMessageFilter filter;

  private final ByteString topic;
  private final ByteString subscriberId;

  private AtomicInteger sent = new AtomicInteger();
  private AtomicInteger failed = new AtomicInteger();

  public KurmaReplicator(IGatewayConfig gateway, HedwigClient hc, MessageHandler handler,
      ClientMessageFilter filter) throws Exception {
    this.gateway = gateway;
    this.hedwigClient = hc;
    this.topic = ByteString.copyFromUtf8(ROOT_REPLICATOR_ID);
    this.subscriberId = ByteString.copyFromUtf8(this.gateway.getLocalGateway().getName());

    this.publisher = hedwigClient.getPublisher();
    this.subscriber = hedwigClient.getSubscriber();

    this.handler = handler;
    this.filter = filter;

    initPubSub();
  }

  protected void initPubSub() throws Exception {
    SubscriptionOptions opts =
        SubscriptionOptions.newBuilder().setCreateOrAttach(CreateOrAttach.CREATE_OR_ATTACH).build();
    try {
      subscriber.subscribe(topic, subscriberId, opts);
      if (filter == null) {
        subscriber.startDelivery(topic, subscriberId, handler);
      } else {
        subscriber.startDeliveryWithFilter(topic, subscriberId, handler, filter);
      }
    } catch (CouldNotConnectException | ClientAlreadySubscribedException | ServiceDownException
        | InvalidSubscriberIdException | ClientNotSubscribedException
        | AlreadyStartDeliveryException e) {
      LOGGER.error("initialization failed", e);
      throw e;
    }
  }

  @Override
  public boolean broadcast(GatewayMessage msg) {
    publisher.asyncPublish(topic, HedwigMessageHelper.toHedwigMessage(msg), new Callback<Void>() {
      @Override
      public void operationFailed(Object context, PubSubException e) {
        failed.incrementAndGet();
        e.printStackTrace();
      }

      @Override
      public void operationFinished(Object context, Void arg1) {
        sent.incrementAndGet();
      }
    }, this);
    return true;
  }

  @Override
  public String getReplicationId() {
    // For now, let's use one single global replication ID, instead of
    // volume-specific ones.
    return ROOT_REPLICATOR_ID;
  }

  public int getSuccessCount() {
    return sent.get();
  }

  public int getFailureCount() {
    return failed.get();
  }
}
