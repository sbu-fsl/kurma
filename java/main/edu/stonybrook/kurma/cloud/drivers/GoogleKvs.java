/**
 * Copyright (C) 2013 EURECOM (www.eurecom.fr)
 *
 * Copyright (C) 2015-2017 Ming Chen <v.mingchen@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package edu.stonybrook.kurma.cloud.drivers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.HttpStatus;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.GoogleStorageService;
import org.jets3t.service.model.GSObject;
import org.jets3t.service.security.GSCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.cloud.Kvs;

public class GoogleKvs extends Kvs {

  private static final Logger logger = LoggerFactory.getLogger(GoogleKvs.class);
  private String DEFAULT_KEY = "heartbit";
  private String DEFAULT_VALUE = "I am alive!";

  private transient final GoogleStorageService gsService;

  // Note: this is an estimation of bytes used, not necessary accurate.
  private AtomicLong bytesUsed = new AtomicLong();
  private AtomicLong count = new AtomicLong();

  public GoogleKvs(String id, String accessKey, String secretKey, String container, boolean enabled,
      int cost) throws IOException {
    super(id, container, enabled, cost);

    GSCredentials gsCredentials = new GSCredentials(accessKey, secretKey);
    try {
      this.gsService = new GoogleStorageService(gsCredentials);
      /*
       * This is to test the service availability in addition to credentials checking
       */
      InputStream is = get(DEFAULT_KEY);
      if (is == null) {
        put(DEFAULT_KEY, new ByteArrayInputStream(DEFAULT_VALUE.getBytes(StandardCharsets.UTF_8)),
            DEFAULT_VALUE.length());
      } else
        is.close();
    } catch (ServiceException e) {
      logger.error("Could not initialize {} KvStore", id, e);
      throw new IOException(e);
    }

    this.createContainer();

    long bytes = 0;
    long cnt = 0;
    try {
      for (GSObject gs : gsService.listObjects(rootContainer)) {
        bytes += gs.getKey().getBytes().length;
        bytes += gs.getContentLength();
        ++cnt;
      }
    } catch (ServiceException e) {
      logger.error("failed to list Google container", e);
    }
    bytesUsed.addAndGet(bytes);
    count.addAndGet(cnt);
  }

  @Override
  public void put(String key, InputStream value, int size) throws IOException {
    try {
      GSObject object = new GSObject(key);
      object.setContentLength(size);
      object.setDataInputStream(value);
      this.gsService.putObject(this.rootContainer, object);
      count.incrementAndGet();
      bytesUsed.addAndGet(key.getBytes().length + size);
    } catch (ServiceException e) {
      throw new IOException(e);
    }
  }

  @Override
  public InputStream get(String key) throws IOException {
    try {
      GSObject objectComplete = this.gsService.getObject(this.rootContainer, key);
      InputStream ins = objectComplete.getDataInputStream();
      return ins;
    } catch (ServiceException e) {

      if (e instanceof ServiceException) {
        ServiceException se = e;
        if (se.getResponseCode() == HttpStatus.SC_NOT_FOUND)
          return null;
      }

      throw new IOException(e);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    try {
      this.gsService.deleteObject(this.rootContainer, key);
      long cnt = count.decrementAndGet();
      if (cnt == 0) {
        bytesUsed.set(0);
      } else {
        bytesUsed.addAndGet(-bytesUsed.get() / cnt);
      }
    } catch (ServiceException e) {

      if (e instanceof ServiceException) {
        ServiceException se = e;
        if (se.getResponseCode() == HttpStatus.SC_NOT_FOUND)
          return;
      }

      throw new IOException(e);
    }
  }

  @Override
  public List<String> list() throws IOException {
    try {
      List<String> keys = new ArrayList<String>();
      GSObject[] objs = this.gsService.listObjects(this.rootContainer);

      for (GSObject obj : objs)
        keys.add(obj.getName());

      return keys;
    } catch (ServiceException e) {
      throw new IOException(e);
    }
  }

  private void createContainer() throws IOException {
    try {
      this.gsService.getOrCreateBucket(this.rootContainer);
    } catch (ServiceException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void shutdown() throws IOException {
    try {
      this.gsService.shutdown();
    } catch (ServiceException e) {
      throw new IOException(e);
    }
  }

  @Override
  public long bytes() throws IOException {
    return bytesUsed.get();
  }
}
