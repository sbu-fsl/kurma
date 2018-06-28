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

import java.util.Base64;

public class PathUtils {
  /**
   * Encode a binary to a legal path string.
   * 
   * We could not use Base64 directly because Base64 use '/'. So we simply replace '/' with '-',
   * which is not used by Base64.
   * 
   * @param binary
   * @return A legal path string representing the binary array.
   */
  public static String encodePath(byte[] binary) {
    return Base64.getEncoder().encodeToString(binary).replace('/', '-');
  }

  /**
   * Inverse of {@link PathUtils#encodePath(byte[])}.
   * 
   * @param path
   * @return The original byte array.
   */
  public static byte[] decodePath(String path) {
    return Base64.getDecoder().decode(path.replace('-', '/'));
  }
}
