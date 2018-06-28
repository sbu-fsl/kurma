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
/**
 * Copied from http://stackoverflow.com/questions/4332264/wrapping-a-bytebuffer-with-an-inputstream
 */
package edu.stonybrook.kurma.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class ByteBufferBackedOutputStream extends OutputStream {
  ByteBuffer buf;

  public ByteBufferBackedOutputStream(ByteBuffer buf) {
    this.buf = buf;
  }

  @Override
  public void write(int b) throws IOException {
    buf.put((byte) b);
  }

  @Override
  public void write(byte[] bytes, int off, int len) throws IOException {
    buf.put(bytes, off, len);
  }

}
