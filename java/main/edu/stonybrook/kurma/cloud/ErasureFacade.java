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
import java.util.AbstractMap;
import java.util.BitSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.KurmaException.ErasureException;
import edu.stonybrook.kurma.util.ByteBufferSource;

/**
 *
 * There are two types of erasures array one using '0' to indicate valid block, and one contains the
 * indices of invalid blocks. Both types have a terminating value of "-1". See below link for
 * Jerasure documentation. https://web.eecs.utk.edu/~plank/plank/papers/CS-08-627.pdf (Section 7 for
 * all parameters info)
 *
 * @author mchen
 *
 */
public class ErasureFacade extends KvsFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(ErasureFacade.class);
  /* erasure coding */
  private EcManager ec;
  private int n;
  private int k;
  private int m;
  private List<Kvs> kvsList;
  private KvsSorter kvsSorter;

  public ErasureFacade(int k, int m, List<Kvs> kvsList, int sortPeriod) {
    ec = new EcManager();
    this.k = k;
    this.m = m;
    this.n = m + k;
    this.kvsList = kvsList;
    if (k <= 0) {
      LOGGER.error("Wrong value for k (<=0), disabling erasure coding.");
    }
    this.kvsSorter = new KvsSorter(kvsList, k, sortPeriod);
  }

  @Override
  public List<Kvs> getKvsList() {
    return kvsList;
  }

  @Override
  public boolean put(String key, ByteBuffer value) throws IOException {
    assert (value.hasArray());

    int valueSize = value.remaining();
    byte[][] dataBlocks = ec.encode(value, k, m);
    assert (n == dataBlocks.length);
    ByteSource[] dataSources = new ByteSource[n];
    int[] sizes = new int[n];
    for (int i = 0; i < n; ++i) {
      ByteSource bs =
          ByteBufferSource.getPrefixedByteSource(valueSize, ByteBuffer.wrap(dataBlocks[i]));
      dataSources[i] = bs;
      sizes[i] = (int) bs.size();
    }
    return put(kvsList, key, dataSources, sizes) >= k;
  }

  private Entry<Integer, byte[]> getBlock(InputStream stream) throws IOException {
    byte[] sizeBuf = new byte[4];
    int ret = ByteStreams.read(stream, sizeBuf, 0, 4);
    assert (ret == 4);
    int size = ByteBuffer.wrap(sizeBuf).getInt();
    int chunkLen = EcManager.getBlockSize(size, k);
    ByteBuffer data = KvsFacade.readValue(stream, chunkLen);
    assert (data.hasArray());
    assert (chunkLen == data.array().length);
    return new AbstractMap.SimpleEntry<Integer, byte[]>(size, data.array());
  }

  private ByteBuffer pessimisticGet(String key, BiFunction<String, ByteBuffer, Boolean> validator)
      throws IOException {
    InputStream[] values = null;
    try {
      Entry<Integer, InputStream[]> res = get(kvsList, key, n);
      if (res == null || res.getKey() < k) {
        LOGGER.warn("failed to read key {}", key);
        return null;
      }
      values = res.getValue();
    } catch (Exception e) {
      LOGGER.error(String.format("unexpected excpetion when reading key '%s'", key), e);
      return null;
    }

    byte[][] dataBlocks = new byte[k][];
    byte[][] codingBlocks = new byte[m][];
    int originalSize = 0;

    int[] erased = new int[m + 1];
    int idxEr = 0;

    for (int j = 0; j < k + m; j++) {
      if (values[j] == null) {
        erased[idxEr++] = j; // invalid
        continue;
      }
      Entry<Integer, byte[]> entry = getBlock(values[j]);
      if (originalSize == 0) {
        originalSize = entry.getKey();
      } else {
        assert (originalSize == entry.getKey());
      }
      if (j < k) {
        dataBlocks[j] = entry.getValue();
      } else {
        codingBlocks[j - k] = entry.getValue();
      }
    }

    // Last index should have '-1' as its value to denote end of erasure
    erased[idxEr] = -1;
    int chunkLen = EcManager.getBlockSize(originalSize, k);
    for (int i = 0; i < idxEr; ++i) {
      int j = erased[i];
      if (j < k) {
        dataBlocks[j] = new byte[chunkLen];
      } else {
        codingBlocks[j - k] = new byte[chunkLen];
      }
    }

    try {
      ByteBuffer buf = ec.decode(dataBlocks, codingBlocks, erased, k, m, originalSize);
      if (buf != null && validator != null) {
        buf.mark();
        if (validator.apply(key, buf)) {
          buf.reset();
          return buf;
        }
      }
      return buf;
    } catch (ErasureException e) {
      e.printStackTrace();
      throw new RuntimeException("unknown HybrisException", e);
    } finally {
      for (InputStream is : values) {
        if (is != null) {
          is.close();
        }
      }
    }
  }

  @Override
  public ByteBuffer get(String key, BiFunction<String, ByteBuffer, Boolean> validator)
      throws IOException {
    Entry<List<Kvs>, int[]> kvsAndErasures = kvsSorter.getKvsListAndErasures();
    InputStream[] values = null;
    try {
      Entry<Integer, InputStream[]> res = get(kvsAndErasures.getKey(), key, k);
      if (res == null) {
        LOGGER.warn("failed to read key {}", key);
        return null;
      }
      if (res.getKey() < k) {
        return pessimisticGet(key, validator);
      }
      values = res.getValue();
    } catch (Exception e) {
      LOGGER.error(String.format("unexpected excpetion when reading key '%s'", key), e);
      return null;
    }

    int[] blockIndices = kvsAndErasures.getValue();
    byte[][] dataBlocks = new byte[k][];
    byte[][] codingBlocks = new byte[m][];
    int originalSize = 0;

    BitSet blocks = new BitSet(k + m);
    assert (k == blockIndices.length);
    assert (k == values.length);
    for (int i = 0; i < values.length; ++i) {
      Entry<Integer, byte[]> entry = getBlock(values[i]);
      if (originalSize == 0) {
        originalSize = entry.getKey();
      } else {
        assert (originalSize == entry.getKey());
      }

      int bi = blockIndices[i];
      if (bi < k) {
        dataBlocks[bi] = entry.getValue();
      } else {
        codingBlocks[bi - k] = entry.getValue();
      }

      if (!blocks.get(bi)) {
        blocks.set(bi);
      } else {
        assert (!blocks.get(bi));
      }
    }

    int[] erased = new int[m + 1];
    int j = 0;
    int chunkLen = EcManager.getBlockSize(originalSize, k);
    for (int i = 0; i < k + m; ++i) {
      if (!blocks.get(i)) {
        erased[j++] = i;
        if (i < k) {
          dataBlocks[i] = new byte[chunkLen];
        } else {
          codingBlocks[i - k] = new byte[chunkLen];
        }
      }
    }
    assert (j == m);
    erased[m] = -1;

    try {
      ByteBuffer buf = ec.decode(dataBlocks, codingBlocks, erased, k, m, originalSize);
      if (buf != null && validator != null) {
        buf.mark();
        if (validator.apply(key, buf)) {
          buf.reset();
          return buf;
        }
      }
      return buf;
    } catch (ErasureException e) {
      e.printStackTrace();
      throw new RuntimeException("unknown HybrisException", e);
    } finally {
      for (InputStream is : values)
        is.close();
    }
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
    StringBuilder sb = new StringBuilder("e-");
    sb.append(k);
    sb.append('-');
    sb.append(m);
    return sb.toString();
  }
}
