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

import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import edu.stonybrook.kurma.helpers.HedwigMessageHelper;
import edu.stonybrook.kurma.message.GatewayMessage;

public class MessageRecorder implements MessageHandler {
  private final static Logger LOGGER = LoggerFactory.getLogger(MessageRecorder.class);
  private final LinkedBlockingQueue<Message> messages = new LinkedBlockingQueue<>();

  @Override
  public void deliver(ByteString topic, ByteString subscriberId, Message msg,
      Callback<Void> callback, Object context) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        if (messages.offer(msg)) {
          LOGGER.debug("a new message received");
        } else {
          LOGGER.error("failed to insert message");
        }
      }
    }).start();
    callback.operationFinished(context, null);
  }

  public int getMessageCount() {
    return messages.size();
  }

  public GatewayMessage popMessage() throws Exception {
    Message msg = messages.take();
    return HedwigMessageHelper.fromHedwigMessage(msg);
  }

}
