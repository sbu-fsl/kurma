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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionOptions;
import org.apache.hedwig.util.Callback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.protobuf.ByteString;

public class BasicHedwigTest extends ReplicatorTestBase {
  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
  }

  @Override
  @After
  public void tearDown() throws Exception {
    hedwigClient.close();
  }

  @Test(timeout = 60000)
  public void testBasicPubSub() throws Exception {
    ByteString topic = ByteString.copyFromUtf8("testBasicPubSub");
    ByteString subid = ByteString.copyFromUtf8("mysubid");
    subscriber.subscribe(topic, subid, newSubscriptionOptions());
    subscriber.startDelivery(topic, subid, messageRecorder);

    publisher.asyncPublish(topic, newMessage("Hello World!"), new TestCallback(), null);
    assertTrue(queue.take());

    expectMessages(1);
  }

  /**
   * Test subscribe to two topics and publish three messages to the topics.
   *
   * @throws Exception
   */
  @Test(timeout = 10000)
  public void testAddSubscriptionTopic() throws Exception {
    ConcurrentHashMap<ByteString, AtomicInteger> msgCount = new ConcurrentHashMap<>();
    ByteString topic1 = ByteString.copyFromUtf8("testAddSubscriptionTopic1");
    msgCount.put(topic1, new AtomicInteger(0));

    ByteString subid = ByteString.copyFromUtf8("mysubid");
    SubscriptionOptions opts =
        SubscriptionOptions.newBuilder().setCreateOrAttach(CreateOrAttach.CREATE_OR_ATTACH).build();
    subscriber.subscribe(topic1, subid, opts);

    CountDownLatch doneSignal = new CountDownLatch(3);
    MessageHandler msgHdl = new MessageHandler() {
      @Override
      public void deliver(ByteString topic, ByteString subscriberId, Message msg, Callback<Void> cb,
          Object context) {
        assertEquals(subid, subscriberId);
        AtomicInteger count = msgCount.get(topic);
        count.incrementAndGet();
        doneSignal.countDown();
      }
    };
    subscriber.startDelivery(topic1, subid, msgHdl);

    publisher.asyncPublish(topic1, newMessage("Hello Topic1!"), null, null);

    // Add another topic, and subscribe it.
    ByteString topic2 = ByteString.copyFromUtf8("testAddSubscriptionTopic2");
    msgCount.put(topic2, new AtomicInteger(0));
    subscriber.subscribe(topic2, subid, opts);
    subscriber.startDelivery(topic2, subid, msgHdl);

    publisher.asyncPublish(topic2, newMessage("Hello Topic2!"), null, null);
    publisher.asyncPublish(topic2, newMessage("Hello Topic2 again!"), null, null);

    // Wait until the three messages are received.
    doneSignal.await();

    assertEquals(1, msgCount.get(topic1).get());
    assertEquals(2, msgCount.get(topic2).get());
  }

}
