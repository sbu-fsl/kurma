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
package edu.stonybrook.kurma.cloud;

import java.io.InputStream;

public interface KvsFilterInterface {
  /**
   * Apply any filter at different time.
   * 
   * @param key
   * @param value The stream provides the value.
   */

  public void beforePut(String key, InputStream value);

  public void afterPut(String key, InputStream value);

  public void beforeGet(String key);

  public void afterGet(String key, InputStream value);

}
