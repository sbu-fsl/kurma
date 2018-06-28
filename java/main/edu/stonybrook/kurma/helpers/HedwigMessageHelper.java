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
package edu.stonybrook.kurma.helpers;

import java.nio.ByteBuffer;
import java.util.Map;

import com.google.protobuf.ByteString;

import org.apache.hedwig.protocol.PubSubProtocol;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageHeader;
import org.apache.hedwig.protoextensions.MapUtils;

import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.util.ThriftUtils;

public class HedwigMessageHelper {
  public final static String HEADER_SOURCE_GATEWAY = "SOURCE_GATEWAY";

  public static Message toHedwigMessage(GatewayMessage gm) {
    byte[] data = ThriftUtils.encode(gm, true);
    ByteString gwid = ByteString.copyFrom(ByteBuffer.allocate(2).putShort(gm.getGwid()).array());
    PubSubProtocol.Map.Builder propsBuilder = PubSubProtocol.Map.newBuilder().addEntries(
        PubSubProtocol.Map.Entry.newBuilder().setKey(HEADER_SOURCE_GATEWAY).setValue(gwid));
    MessageHeader.Builder headerBuilder = MessageHeader.newBuilder().setProperties(propsBuilder);
    final ByteString body = ByteString.copyFrom(data);
    Message msg = Message.newBuilder().setBody(body).setHeader(headerBuilder).build();
    return msg;
  }

  public static short readSourceGateway(Message msg) {
    if (!msg.hasHeader())
      return 0;
    short gatewayId = 0;
    MessageHeader header = msg.getHeader();
    if (header.hasProperties()) {
      Map<String, ByteString> props = MapUtils.buildMap(header.getProperties());
      ByteString value = props.get(HEADER_SOURCE_GATEWAY);
      if (null == value || value.size() != 2) {
        return 0;
      }
      gatewayId = ByteBuffer.wrap(value.toByteArray()).getShort();
    }
    return gatewayId;
  }

  public static GatewayMessage fromHedwigMessage(Message msg) throws Exception {
    GatewayMessage gm = new GatewayMessage();
    ThriftUtils.decode(msg.getBody().toByteArray(), gm, true);
    return gm;
  }
}
