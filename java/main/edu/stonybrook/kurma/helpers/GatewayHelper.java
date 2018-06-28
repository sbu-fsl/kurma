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

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class GatewayHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayHelper.class);
  public static String nameOf(short gwid) {
    try {
      return new String(ByteBuffer.allocate(2).putShort(gwid).array(), "US-ASCII");
    } catch (UnsupportedEncodingException e) {
      LOGGER.error(String.format("invaliad gateway id: %d", gwid), e);
    }
    return "xx";
  }

  public static short valueOf(String name) {
    Preconditions.checkArgument(name.length() == 2, "gateway name should be a 2-char string");
    byte[] values = null;
    try {
      values = name.getBytes("US-ASCII");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    return values == null ? 0 : ByteBuffer.wrap(values).getShort();
  }
}
