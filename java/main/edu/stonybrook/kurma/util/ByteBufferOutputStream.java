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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An output stream that write to a ByteBuffer.
 *
 * @author mchen
 *
 */
public final class ByteBufferOutputStream extends ByteArrayOutputStream {
  public ByteBufferOutputStream() {
    super();
  }

  public ByteBufferOutputStream(int initialCapacity) {
    super(initialCapacity);
  }

  public ByteBuffer getByteBuffer() {
    return ByteBuffer.wrap(buf, 0, count);
  }

  public ByteBuffer getByteBuffer(int offset, int length) {
    return ByteBuffer.wrap(buf, offset, length);
  }

  public InputStream toInputStream() {
    return new ByteArrayInputStream(buf, 0, count);
  }
}
