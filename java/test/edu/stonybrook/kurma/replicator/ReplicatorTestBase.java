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

import java.util.concurrent.SynchronousQueue;

import org.apache.hedwig.client.HedwigClient;
import org.apache.hedwig.client.api.Publisher;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionEvent;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionOptions;
import org.apache.hedwig.server.PubSubServerStandAloneTestBase;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.ConcurrencyUtils;
import org.apache.hedwig.util.HedwigSocketAddress;
import org.apache.hedwig.util.SubscriptionListener;
import org.junit.After;
import org.junit.Before;

import com.google.protobuf.ByteString;

import edu.stonybrook.kurma.message.GatewayMessage;

public class ReplicatorTestBase extends PubSubServerStandAloneTestBase {
  // Client side variables
  protected HedwigClient hedwigClient;
  protected Publisher publisher;
  protected Subscriber subscriber;

  // SynchronousQueues to verify async calls
  protected final SynchronousQueue<Boolean> queue = new SynchronousQueue<Boolean>();
  protected final SynchronousQueue<SubscriptionEvent> eventQueue =
      new SynchronousQueue<SubscriptionEvent>();

  // A MessageHandler that simply records the messages it is handling.
  protected final MessageRecorder messageRecorder = new MessageRecorder();

  protected class RetentionServerConfiguration extends StandAloneServerConfiguration {
    @Override
    public boolean isStandalone() {
      return true;
    }

    @Override
    public int getRetentionSecs() {
      return 10;
    }
  }

  protected boolean isSubscriptionChannelSharingEnabled;

  public ReplicatorTestBase() {
    this.isSubscriptionChannelSharingEnabled = true;
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    hedwigClient = new HedwigClient(new ClientConfiguration() {
      @Override
      public HedwigSocketAddress getDefaultServerHedwigSocketAddress() {
        return getDefaultHedwigAddress();
      }

      @Override
      public boolean isSubscriptionChannelSharingEnabled() {
        return ReplicatorTestBase.this.isSubscriptionChannelSharingEnabled;
      }
    });
    publisher = hedwigClient.getPublisher();
    subscriber = hedwigClient.getSubscriber();
  }

  @Override
  @After
  public void tearDown() throws Exception {
    hedwigClient.close();
    super.tearDown();
  }

  // Test implementation of Callback for async client actions.
  class TestCallback implements Callback<Void> {

    @Override
    public void operationFinished(Object ctx, Void resultOfOperation) {
      new Thread(new Runnable() {
        @Override
        public void run() {
          if (logger.isDebugEnabled())
            logger.debug("Operation finished!");
          ConcurrencyUtils.put(queue, true);
        }
      }).start();
    }

    @Override
    public void operationFailed(Object ctx, final PubSubException exception) {
      new Thread(new Runnable() {
        @Override
        public void run() {
          logger.error("Operation failed!", exception);
          ConcurrencyUtils.put(queue, false);
        }
      }).start();
    }
  }

  class TestSubscriptionListener implements SubscriptionListener {
    SynchronousQueue<SubscriptionEvent> eventQueue;

    public TestSubscriptionListener() {
      this.eventQueue = ReplicatorTestBase.this.eventQueue;
    }

    public TestSubscriptionListener(SynchronousQueue<SubscriptionEvent> queue) {
      this.eventQueue = queue;
    }

    @Override
    public void processEvent(final ByteString topic, final ByteString subscriberId,
        final SubscriptionEvent event) {
      new Thread(new Runnable() {
        @Override
        public void run() {
          logger.debug("Event {} received for subscription(topic:{}, subscriber:{})",
              new Object[] {event, topic.toStringUtf8(), subscriberId.toStringUtf8()});
          ConcurrencyUtils.put(TestSubscriptionListener.this.eventQueue, event);
        }
      }).start();
    }
  }

  protected void expectMessages(int n) throws Exception {
    while (messageRecorder.getMessageCount() < n) {
      Thread.sleep(10);
    }
  }

  protected GatewayMessage popMessage() throws Exception {
    return messageRecorder.popMessage();
  }

  protected Message newMessage(String msg) {
    return Message.newBuilder().setBody(ByteString.copyFromUtf8(msg)).build();
  }

  protected SubscriptionOptions newSubscriptionOptions() {
    return SubscriptionOptions.newBuilder().setCreateOrAttach(CreateOrAttach.CREATE_OR_ATTACH)
        .build();
  }

}
