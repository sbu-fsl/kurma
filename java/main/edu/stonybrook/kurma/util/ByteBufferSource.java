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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

/**
 * A ByteSource based on ByteBuffer.
 *
 * @author mchen
 *
 */
public class ByteBufferSource extends ByteSource {
  private ByteBuffer buf;

  public ByteBufferSource(ByteBuffer buf) {
    this.buf = buf.asReadOnlyBuffer();
  }

  @Override
  public boolean isEmpty() throws IOException {
    return !buf.hasRemaining();
  }

  @Override
  public long size() throws IOException {
    return buf.remaining();
  }

  @Override
  public InputStream openStream() throws IOException {
    return new ByteBufferBackedInputStream(buf.asReadOnlyBuffer());
  }

  public ByteBuffer copyToByteBuffer() {
    byte[] data = new byte[buf.remaining()];
    System.arraycopy(buf.array(), buf.position(), data, 0, buf.remaining());
    return ByteBuffer.wrap(data);
  }

  public ByteBuffer getBackingBuffer() {
    return buf;
  }

  /**
   * @param prefix
   * @param buf
   * @return
   */
  public static ByteSource getPrefixedByteSource(int prefix, ByteBuffer buf) {
    ByteBuffer prefixBuf = ByteBuffer.allocate(4);
    prefixBuf.mark();
    prefixBuf.putInt(prefix);
    prefixBuf.reset();
    ByteBufferSource bs1 = new ByteBufferSource(prefixBuf);
    ByteBufferSource bs2 = new ByteBufferSource(buf);
    return ByteSource.concat(bs1, bs2);
  }

  public static int readPrefix(InputStream stream) throws IOException {
    byte[] prefixBuf = new byte[4];
    if (ByteStreams.read(stream, prefixBuf, 0, 4) != 4) {
      throw new IOException("less than 4 bytes available");
    }
    return ByteBuffer.wrap(prefixBuf).getInt();
  }

  /**
   * This assumes the prefix is the size of othe buffer.
   * 
   * @param stream
   * @return
   * @throws IOException
   */
  public static ByteBuffer readPrefixedBuffer(InputStream stream) throws IOException {
    if (stream == null) {
      return null;
    }
    int size = readPrefix(stream);
    byte[] buf = new byte[size];
    int bytes = ByteStreams.read(stream, buf, 0, size);
    if (bytes != size) {
      throw new IOException(String.format("expecting %d bytes but got only %d", size, bytes));
    }
    return ByteBuffer.wrap(buf);
  }
}
