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
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.cloud.Kvs;

/**
 * Transient in-memory key-value store - for testing purposes.
 *
 * @author P. Viotti
 */
public class TransientKvs extends Kvs {

  private transient final Map<String, byte[]> hashMap;
  private AtomicLong bytesUsed;

  public TransientKvs(String id) {
    super(id, id, true, 1);
    hashMap = new ConcurrentHashMap<>();
    bytesUsed = new AtomicLong(0);
  }

  @Override
  public void put(String key, InputStream value, int size) throws IOException {
    byte[] data = new byte[size];
    ByteStreams.read(value, data, 0, size);
    hashMap.put(key, data);
    bytesUsed.addAndGet(key.getBytes().length + data.length);
  }

  @Override
  public InputStream get(String key) {
    byte[] value = hashMap.get(key);
    return value == null ? null : new ByteArrayInputStream(hashMap.get(key));
  }

  @Override
  public void delete(String key) {
    byte[] data = this.hashMap.remove(key);
    bytesUsed.addAndGet(-key.getBytes().length - data.length);
  }

  @Override
  public List<String> list() {
    return new ArrayList<String>(this.hashMap.keySet());
  }

  @Override
  public long bytes() throws IOException {
    return bytesUsed.get();
  }
}
