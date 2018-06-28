/**
 * Copyright (C) 2015 Ming Chen <v.mingchen@gmail.com>
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import secretsharing.CDCodecJNI;

import com.amazonaws.util.IOUtils;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.util.ByteBufferBackedInputStream;
import edu.stonybrook.kurma.util.ByteBufferOutputStream;

public abstract class KvsFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(KvsFacade.class);
  protected static ExecutorService executor = Executors.newCachedThreadPool();

  public static final int DEFAULT_KVS_WORKER_THREADS = 16;

  public static final int RETRIES = 3;

  /**
   * Put a key-value pair into the KvsFacade
   *
   * @param key
   * @param value
   * @return whether the put is successful
   * @throws IOException
   */
  public abstract boolean put(String key, ByteBuffer value) throws IOException;

  public static void shutdownWorkers() {
    executor.shutdown();
  }

  /**
   * Get the value of the given key.
   *
   * @param key Key of the block data
   * @param validator A function that validates whether the key-value block is valid.
   * @return The value associated with the key.
   * @throws IOException
   */
  public abstract ByteBuffer get(String key, BiFunction<String, ByteBuffer, Boolean> validator)
      throws IOException;

  public abstract void delete(String key) throws IOException;

  public abstract List<Kvs> getKvsList();

  public abstract long getBytesUsed() throws IOException;

  public abstract String getKvsType();

  public String getKvsIds() {
    return KvsManager.kvsListToString(getKvsList());
  }

  public boolean match(String kvsType, String kvsIds) {
    if (!getKvsType().equals(kvsType)) {
      return false;
    }
    if (!getKvsIds().equals(kvsIds)) {
      return false;
    }
    return true;
  }

  public static String buildFacadeKey(String kvsType, String kvsIds) {
    return kvsType + "+" + kvsIds;
  }

  public String getFacadeKey() {
    return buildFacadeKey(getKvsType(), getKvsIds());
  }

  /**
   * Whether the facade implementation encrypt data before writing key-value pairs. If yes, then the
   * caller of this facade does not need to encrypt data.
   *
   * @return True if the facade will perform internal encryption.
   */
  public abstract boolean hasInternalEncryption();

  public static KvsFacade newFacade(String kvsType, List<Kvs> kvsList, int sortPeriod) {
    KvsFacade facade = null;
    String[] parts = kvsType.split("-");
    if ("r".equals(parts[0])) {
      if (parts.length != 2) {
        LOGGER.error("unknown kvsType {}, expected format: 'r-n'", kvsType);
        return null;
      }
      int n = Integer.parseInt(parts[1]);
      if (kvsList.size() != n) {
        LOGGER.error("kvs count mismatch: need {} but provided {}", n, kvsList.size());
        return null;
      }
      facade = new ReplicationFacade(kvsList, sortPeriod);
    } else if ("e".equals(parts[0])) {
      if (parts.length != 3) {
        LOGGER.error("unknown kvsType {}, expected format: 'e-k-m'", kvsType);
        return null;
      }
      int k = Integer.parseInt(parts[1]);
      int m = Integer.parseInt(parts[2]);
      if (k + m != kvsList.size()) {
        LOGGER.error("kvs count mismatch: need {} but provided {}", k + m, kvsList.size());
        return null;
      }
      facade = new ErasureFacade(k, m, kvsList, sortPeriod);
    } else if ("s".equals(parts[0])) {
      if (parts.length != 4) {
        LOGGER.error("unknown kvsType {}, expected format: 's-n-m-r'", kvsType);
        return null;
      }
      int n = Integer.parseInt(parts[1]);
      int m = Integer.parseInt(parts[2]);
      int r = Integer.parseInt(parts[3]);
      if (n != kvsList.size()) {
        LOGGER.error("kvs count mismatch: need {} but provided {}", n, kvsList.size());
        return null;
      }
      facade = new SecretSharingFacade(CDCodecJNI.CAONT_RS, n, m, r, kvsList, sortPeriod);
    } else {
      LOGGER.error("invalid kvs type string {}", kvsType);
    }
    return facade;
  }

  public static void putValue(Kvs kvs, String key, ByteBuffer data) throws IOException {
    kvs.put(key, new ByteBufferBackedInputStream(data), data.remaining());
  }

  public static ByteBuffer getValue(Kvs kvs, String key) throws IOException {
    InputStream value = kvs.get(key);
    try {
      byte[] bytes = IOUtils.toByteArray(value);
      return bytes == null ? null : ByteBuffer.wrap(bytes);
    } finally {
      value.close();
    }
  }

  public static ByteBuffer readValue(InputStream value, int sizeHint) throws IOException {
    ByteBufferOutputStream bbos = new ByteBufferOutputStream(sizeHint);
    try {
      ByteStreams.copy(value, bbos);
    } finally {
      value.close();
      bbos.close();
    }
    return bbos.getByteBuffer();
  }

  public static ByteBuffer getValue(Kvs kvs, String key, int sizeHint) throws IOException {
    InputStream value = kvs.get(key);
    if (value == null) {
      return null;
    }
    try {
      return readValue(value, sizeHint);
    } finally {
      value.close();
    }
  }

  public static long getBytesUsed(List<Kvs> kvs) throws IOException {
    long bytes = 0;
    for (Kvs kv : kvs) {
      bytes += kv.bytes();
    }
    return bytes;
  }

  /**
   * Write the given values to a list of cloud providers.
   *
   * Note: the caller should be responsible for cleaning up or overwriting failed KV pairs.
   *
   * @param kvsList
   * @param key
   * @param values
   * @return The number of key-value stores that are successfully written.
   */
  protected int put(final List<Kvs> kvsList, final String key, ByteSource[] values, int[] sizes)
      throws IOException {
    final int len = kvsList.size();
    Preconditions.checkArgument(len == values.length);
    BitSet statues = new BitSet(len);

    for (int t = 0; t < RETRIES && statues.cardinality() < len; ++t) {
      CompletionService<Kvs> compServ = new ExecutorCompletionService<Kvs>(executor);
      List<Future<Kvs>> futures = new ArrayList<>();
      int scheduled = 0;
      for (int i = 0; i < len; ++i) {
        Kvs kvs = kvsList.get(i);
        if (kvs.getId().startsWith(KvsManager.FAIL_PREFIX)) {
          continue;
        }
        if (statues.get(i)) {
          continue;
        }
        futures.add(compServ.submit(new KvsPutWorker(kvs, key, values[i], sizes[i])));
        ++scheduled;
      }

      try {
        for (; scheduled > 0; --scheduled) {
          Kvs kvs = null;
          Future<Kvs> future = compServ.poll(Kvs.WriteTimeOutSeconds, TimeUnit.SECONDS);
          if (future == null) {
            LOGGER.warn("timed out when storing {}; retrying.", key);
            continue;
          }
          kvs = future.get();
          if (kvs != null) {
            int kvsIndex = kvsList.indexOf(kvs);
            statues.set(kvsIndex);
          }
        }
      } catch (ExecutionException | InterruptedException e) {
        LOGGER.warn(String.format("trial-%d; failed to store key-value pair to clouds", t), e);
      } finally {
        for (Future<Kvs> f : futures) {
          f.cancel(true);
        }
      }
    }

    return statues.cardinality();
  }

  /**
   *
   * @param kvsList
   * @param key
   * @param kvsLen
   * @return A pair of <SuccessCount, ValueStreams>
   */
  protected Entry<Integer, InputStream[]> get(final List<Kvs> kvsList, final String key,
      int kvsLen) {
    Preconditions.checkArgument(kvsLen <= kvsList.size());
    InputStream[] values = new InputStream[kvsLen];

    BitSet statues = new BitSet(kvsLen);
    for (int t = 0; t < RETRIES && statues.cardinality() < kvsLen; ++t) {
      CompletionService<Entry<Kvs, InputStream>> compServ =
          new ExecutorCompletionService<Entry<Kvs, InputStream>>(executor);
      List<Future<Entry<Kvs, InputStream>>> futures = new ArrayList<>();
      int scheduled = 0;
      for (int i = 0; i < kvsLen; ++i) {
        Kvs kvs = kvsList.get(i);
        if (kvs == null || kvs.getId().startsWith(KvsManager.FAIL_PREFIX)) {
          continue;
        }
        if (!statues.get(i)) {
          futures.add(compServ.submit(new KvsGetWorker(kvs, key)));
          ++scheduled;
        }
      }

      try {
        for (int i = 0; i < scheduled; i++) {
          Future<Entry<Kvs, InputStream>> future =
              compServ.poll(Kvs.ReadTimeOutSeconds, TimeUnit.SECONDS);
          if (future == null) { // timed out, ignore it
            LOGGER.warn("retriving of key {} timed out", key);
            continue;
          }
          Entry<Kvs, InputStream> chunk = future.get();
          if (chunk != null) {
            int index = kvsList.indexOf(chunk.getKey());
            assert (index >= 0 && index < kvsLen);
            values[index] = chunk.getValue();
            statues.set(index);
          }
        }

      } catch (InterruptedException | ExecutionException e) {
        LOGGER.error(String.format("failed to get key-value pair from all clouds for key %s", key),
            e);
      } finally {
        for (Future<Entry<Kvs, InputStream>> f : futures) {
          f.cancel(true);
        }
      }
    }

    return new AbstractMap.SimpleEntry<>(statues.cardinality(), values);
  }

  protected void delete(List<Kvs> kvsList, String key) throws IOException {
    for (Kvs kv : kvsList) {
      kv.delete(key);
    }
  }
}
