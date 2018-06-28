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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;


public class CompressionUtils {

  public static byte[] compress(byte[] data) {
    ByteArrayOutputStream baos = null;
    Deflater dfl = new Deflater(Deflater.BEST_SPEED, true);
    dfl.setInput(data);
    dfl.finish();
    baos = new ByteArrayOutputStream();
    byte[] tmp = new byte[4 * 1024];
    try {
      while (!dfl.finished()) {
        int size = dfl.deflate(tmp);
        baos.write(tmp, 0, size);
       // System.out.printf("calling compression utili\n");
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      return data;
    } finally {
      try {
        baos.close();
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
    return baos.toByteArray();
  }

  public static byte[] decompress(byte[] data) throws IOException, DataFormatException {
    Inflater inflater = new Inflater(true);
    inflater.setInput(data);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
    byte[] buffer = new byte[1024];
    while (!inflater.finished()) {
      int count = inflater.inflate(buffer);
      outputStream.write(buffer, 0, count);
    }
    outputStream.close();
    byte[] output = outputStream.toByteArray();

    inflater.end();

    return output;
  }
}
