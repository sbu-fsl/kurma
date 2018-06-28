/**
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
package edu.stonybrook.kurma.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;

import edu.stonybrook.kurma.util.ByteBufferSource;

public class ReplicationFacade extends KvsFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationFacade.class);
  private List<Kvs> kvsList;
  private KvsSorter kvsSorter;

  public ReplicationFacade(List<Kvs> list, int sortPeriod) {
    kvsList = list;
    kvsSorter = new KvsSorter(kvsList, list.size(), sortPeriod);
  }

  @Override
  public List<Kvs> getKvsList() {
    return kvsList;
  }

  @Override
  public boolean put(String key, ByteBuffer value) throws IOException {
    LOGGER.debug("saving key {} {}", Hashing.murmur3_32().hashBytes(key.getBytes()),
        key.hashCode());
    int n = kvsList.size();
    ByteSource[] values = new ByteSource[n];
    int[] sizes = new int[n];
    for (int i = 0; i < n; ++i) {
      values[i] = ByteBufferSource.getPrefixedByteSource(value.remaining(), value.slice());
      sizes[i] = (int) values[i].size();
    }
    int result = put(kvsList, key, values, sizes);
    return result > 0;
  }

  @Override
  public ByteBuffer get(String key, BiFunction<String, ByteBuffer, Boolean> validator)
      throws IOException {
    LOGGER.debug("retriving key {} {}", Hashing.murmur3_32().hashBytes(key.getBytes()),
        key.hashCode());
    ByteBuffer data = null;
    List<Kvs> sortedKvsList = kvsSorter.getKvsListAndErasures().getKey();
    Entry<Integer, InputStream[]> res = null;
    for (int i = 0; i < sortedKvsList.size(); ++i) {
      res = get(Arrays.asList(sortedKvsList.get(i)), key, 1);
      if (res == null || res.getValue() == null || res.getValue()[0] == null) {
        continue;  // retry
      }
      data = ByteBufferSource.readPrefixedBuffer(res.getValue()[0]);
      if (data == null) {
        continue;
      }
      for (InputStream ins : res.getValue()) {
        ins.close();
      }
      data.mark();
      if (validator == null || validator.apply(key, data)) {
        data.reset();
        break;
      } else {
        LOGGER.debug("failed to verify block {} from cloud {}", key, sortedKvsList.get(i));
        res = null;
      }
    }
    return data;
  }

  @Override
  public void delete(String key) throws IOException {
    delete(kvsList, key);
  }

  @Override
  public long getBytesUsed() throws IOException {
    return KvsFacade.getBytesUsed(kvsList);
  }

  @Override
  public boolean hasInternalEncryption() {
    return false;
  }

  @Override
  public String getKvsType() {
    return "r-" + kvsList.size();
  }

}
