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
package edu.stonybrook.kurma.util;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransport;

public class ThriftUtils {

  @SuppressWarnings("rawtypes")
  public static byte[] encode(TBase obj, boolean compress) {
    TMemoryBuffer buffer = new TMemoryBuffer(1024);
    TProtocol protocol = compress ? new TCompactProtocol(buffer) : new TBinaryProtocol(buffer);
    try {
      obj.write(protocol);
    } catch (TException e1) {
      e1.printStackTrace();
      return null;
    }
    return compress ? CompressionUtils.compress(buffer.getArray()) : buffer.getArray();
  }

  @SuppressWarnings("rawtypes")
  public static boolean decode(byte[] data, TBase obj, boolean compress) throws Exception {
    TTransport xport =
        new TMemoryInputTransport(compress ? CompressionUtils.decompress(data) : data);
    TProtocol protocol = compress ? new TCompactProtocol(xport) : new TBinaryProtocol(xport);
    try {
      obj.read(protocol);
    } catch (TException e) {
      e.printStackTrace();
      return false;
    }
    return true;
  }

  @SuppressWarnings("rawtypes")
  public static byte[] encodeCompact(TBase obj) {
    return encode(obj, true);
  }

  @SuppressWarnings("rawtypes")
  public static byte[] encodeBinary(TBase obj) {
    return encode(obj, false);
  }

  @SuppressWarnings("rawtypes")
  public static void decodeCompact(byte[] data, TBase obj) throws Exception {
    decode(data, obj, true);
  }

  @SuppressWarnings("rawtypes")
  public static void decodeBinary(byte[] data, TBase obj) throws Exception {
    decode(data, obj, false);
  }

  @SuppressWarnings("rawtypes")
  public static byte[] encodeZlib(TBase obj) {
    return CompressionUtils.compress(encodeCompact(obj));
  }

  @SuppressWarnings("rawtypes")
  public static void decodeZlib(byte[] data, TBase obj) throws Exception {
    decodeCompact(CompressionUtils.decompress(data), obj);
  }
}
