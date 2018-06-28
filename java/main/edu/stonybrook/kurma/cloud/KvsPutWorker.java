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

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;

/**
 * Worker thread class in charge of asynchronously performing write operations on cloud stores.
 *
 * @author p.viotti
 */
public class KvsPutWorker implements Callable<Kvs> {
  private static final Logger LOGGER = LoggerFactory.getLogger(KvsPutWorker.class);

  private final Kvs kvStore;
  private final String key;
  private final ByteSource value;
  private final int size;

  public KvsPutWorker(Kvs kvStore, String key, ByteSource value, int size) {
    this.kvStore = kvStore;
    this.key = key;
    this.value = value;
    this.size = size;
  }

  @Override
  public Kvs call() {
    InputStream vs = null;
    try {
      vs = value.openStream();
      kvStore.put(key, vs, size);
      return kvStore;
    } catch (Exception e) {
      LOGGER.warn("KvsPutWorker failed", e);
      kvStore.logFailure();
      return null;
    } finally {
      if (vs != null) {
        try {
          vs.close();
        } catch (IOException e) {
          LOGGER.warn(String.format("failed to close stream for key '%s'", key), e);
        }
      }
    }
  }
}
