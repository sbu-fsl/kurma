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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.cloud.Kvs;

/**
 * Faulty key-value store - for testing purposes. It fails once every N operations.
 *
 * @author P. Viotti
 * @author Ming Chen
 */
public class FaultyKvs extends Kvs {
  private static final Logger LOGGER = LoggerFactory.getLogger(FaultyKvs.class);
  private transient final Map<String, byte[]> hashMap;
  private AtomicInteger count;
  private AtomicLong bytesUsed;
  private int failPeriod = 1;

  public FaultyKvs(String id, String accessKey, String secretKey, String container, boolean enabled,
      int cost) {
    super(id, container, enabled, cost);
    this.hashMap = new ConcurrentHashMap<>();
    count = new AtomicInteger(0);
    bytesUsed = new AtomicLong(0);
  }

  public FaultyKvs(String id, boolean enabled, int cost, int failPeriod) {
    this(id, null, null, null, enabled, cost);
    Preconditions.checkArgument(failPeriod > 0);
    this.failPeriod = failPeriod;
  }

  private boolean shouldFail() {
    return count.incrementAndGet() % failPeriod == 0;
  }

  /*
   * These put function fails after FAIL_ON_COUNT put operations, if initialized during object
   * creation. This is used for testing of GC code where it does garbage collection after partial
   * write as this will failed in between.
   */
  @Override
  public void put(String key, InputStream value, int size) throws IOException {
    if (shouldFail()) {
      throw new IOException();
    }
    byte[] data = new byte[size];
    try {
      int bytes = ByteStreams.read(value, data, 0, size);
      if (bytes != size) {
        System.err.printf("read only %d bytes while expecting %d\n", bytes, size);
        System.exit(1);
      }
    } catch (IOException e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
    hashMap.put(key, data);
    bytesUsed.addAndGet(key.getBytes().length + size);
  }

  /**
   * Faulty get API: returns bogus data.
   */
  @Override
  public InputStream get(String key) {
    if (shouldFail()) {
      return null;
    }
    byte[] value = hashMap.getOrDefault(key, null);
    if (value == null) {
      return null;
    }
    return new ByteArrayInputStream(value);
  }

  @Override
  public void delete(String key) {
    byte[] data = hashMap.remove(key);
    bytesUsed.addAndGet(-key.getBytes().length - data.length);
  }

  @Override
  public List<String> list() {
    return new ArrayList<String>(this.hashMap.keySet());
  }

  public Map<String, byte[]> getHashMap() {
    return this.hashMap;
  }

  @Override
  public long bytes() throws IOException {
    return bytesUsed.get();
  }
}
