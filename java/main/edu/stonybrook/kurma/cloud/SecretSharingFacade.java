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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import org.apache.commons.math3.util.Combinations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.util.ByteBufferSource;

public class SecretSharingFacade extends KvsFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(SecretSharingFacade.class);
  private SecretSharingCodec codec = null;
  private List<Kvs> kvsList;
  private int n;
  private int m;
  private int k;
  private KvsSorter kvsSorter;

  public SecretSharingFacade(int type, int n, int m, int r, List<Kvs> kvsList, int sortPeriod) {
    this.n = n;
    this.m = m;
    this.k = n - m;
    this.codec = SecretSharingCodec.getCodec(type, n, m, r);
    this.kvsList = kvsList;
    this.kvsSorter = new KvsSorter(kvsList, k, sortPeriod);
  }

  private int readSecretSize(InputStream[] streams) throws IOException {
    byte[] sizeBuf = new byte[4];
    HashMap<Integer, Integer> sizes = new HashMap<>(); // entries of <size,
                                                       // occurrences>
    for (InputStream stream : streams) {
      if (stream == null || ByteStreams.read(stream, sizeBuf, 0, 4) != 4) {
        continue;
      }
      int size = ByteBuffer.wrap(sizeBuf).getInt();
      sizes.put(size, sizes.getOrDefault(size, 0) + 1);
    }
    for (Entry<Integer, Integer> e : sizes.entrySet()) {
      if (e.getValue() >= k) {
        return e.getKey();
      }
    }
    return -1;
  }

  private byte[] readShares(InputStream[] streams, int shareSize) throws IOException {
    byte[] shares = new byte[shareSize * k];
    int j = 0;
    try {
      for (int i = 0; i < streams.length && j < k; ++i) {
        if (streams[i] == null)
          continue;
        int res = ByteStreams.read(streams[i], shares, j * shareSize, shareSize);
        if (res != shareSize) {
          LOGGER.warn("read failed, expecting {} bytes but returned {}", shareSize, res);
          continue;
        }
        ++j;
      }
    } finally {
      for (int i = 0; i < streams.length; ++i) {
        if (streams[i] != null) {
          streams[i].close();
        }
      }
    }
    if (j != k) {
      LOGGER.error("need {} shares but got only {}", k, j);
      return null;
    }
    return shares;
  }

  @Override
  public boolean put(String key, ByteBuffer value) throws IOException {
    if (!value.hasArray() || value.position() != 0) {
      LOGGER.error("value of SecretSharingFacade.put() must be an array start at 0");
    }

    int secretSize = value.remaining();
    int sizeOfAllShares = codec.getSizeOfAllShares(secretSize);
    byte[] shares = new byte[sizeOfAllShares];
    int shareSize = codec.encode(value.array(), secretSize, shares);
    if (shareSize < 0) {
      LOGGER.error("failed to encode: {}", shareSize);
      System.exit(1);
    }

    ByteSource[] dataSources = new ByteSource[n];
    int[] sizes = new int[n];
    for (int i = 0; i < n; ++i) {
      ByteSource bs = ByteBufferSource.getPrefixedByteSource(secretSize,
          ByteBuffer.wrap(shares, i * shareSize, shareSize));
      dataSources[i] = bs;
      sizes[i] = (int) bs.size();
    }
    return put(kvsList, key, dataSources, sizes) >= k;
  }

  /**
   * Return the key-value pair and retry if necessary.
   * 
   * @param key
   * @param validator
   * @return
   * @throws IOException
   */
  private ByteBuffer pessimisticGet(String key, BiFunction<String, ByteBuffer, Boolean> validator)
      throws IOException {
    LOGGER.debug("pessimisticGet");
    byte[][] values = new byte[n][];
    InputStream[] streams = null;
    try {
      // Fetch from all n providers.
      Entry<Integer, InputStream[]> res = get(kvsList, key, n);
      if (res == null) {
        LOGGER.warn("failed to read key {}", key);
        return null;
      }
      streams = res.getValue();
    } catch (Exception e) {
      LOGGER.error(String.format("unexpected excpetion when reading key '%s'", key), e);
      return null;
    }

    int secretSize = readSecretSize(streams);
    if (secretSize < 0) {
      return null;
    }
    int shareSize = codec.getShareSize(secretSize);

    // Read InputStream into ByteBuffer first because we may read them again
    // and again during the retries.
    ArrayList<Integer> validIndices = new ArrayList<>(n);
    for (int i = 0; i < n; ++i) {
      if (streams[i] == null) {
        values[i] = null;
      } else {
        // ByteBuffer buf = ByteBuffer.wrap(new byte[secretSize]);
        byte[] buf = new byte[shareSize];
        if (ByteStreams.read(streams[i], buf, 0, shareSize) != shareSize) {
          values[i] = null;
        } else {
          values[i] = buf;
          validIndices.add(i);
        }
      }
    }

    for (InputStream ins : streams) {
      if (ins != null) {
        ins.close();
      }
    }

    if (validIndices.size() < k) {
      LOGGER.warn("need {} shares to recover the secret, but got only {}", k, validIndices.size());
      return null;
    }

    Combinations comb = new Combinations(validIndices.size(), k);
    Iterator<int[]> it = comb.iterator();

    byte[] shares = new byte[shareSize * k];
    byte[] secret = new byte[codec.getAlignedSecretSize(secretSize)];
    while (it.hasNext()) {
      int[] erasures = new int[k];
      int[] indices = it.next();
      for (int i = 0; i < k; ++i) {
        int pos = validIndices.get(indices[i]);
        erasures[i] = pos;
        System.arraycopy(values[pos], 0, shares, i * shareSize, shareSize);
      }

      if (codec.decode(shares, shareSize, erasures, secret, secretSize)
          && (validator == null || validator.apply(key, ByteBuffer.wrap(secret, 0, secretSize)))) {
        return ByteBuffer.wrap(secret, 0, secretSize);
      }
    }

    return null;
  }

  /**
   * Return the key-value pair optimistically. Fall back to pessimisticGet() if any thing goes
   * wrong. TODO reused the values (from successful providers) when falling back to pessimisticGet()
   * so that the latter only need to read from the other cloud providers.
   */
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
      values = res.getValue();
      if (res.getKey() != k) { // less than k retrieved
        return pessimisticGet(key, validator);
      }
    } catch (Exception e) {
      LOGGER.error(String.format("unexpected excpetion when reading key '%s'", key), e);
      return null;
    }

    int secretSize = readSecretSize(values);
    if (secretSize < 0) {
      return pessimisticGet(key, validator);
    }

    int shareSize = codec.getShareSize(secretSize);
    if (shareSize < 0) {
      LOGGER.error("failed to parse value size of key {}", key);
      return pessimisticGet(key, validator);
    }

    int[] erasures = kvsAndErasures.getValue();
    byte[] shares = readShares(values, shareSize);
    byte[] secret = new byte[codec.getAlignedSecretSize(secretSize)];

    LOGGER.debug("read secret size: {}", secretSize);

    if (!codec.decode(shares, shareSize, erasures, secret, secretSize)) {
      LOGGER.error("failed to decode value for key {}", key);
      return pessimisticGet(key, validator);
    }

    if (validator != null && !validator.apply(key, ByteBuffer.wrap(secret, 0, secretSize))) {
      return pessimisticGet(key, validator);
    }

    return ByteBuffer.wrap(secret, 0, secretSize);
  }

  @Override
  public void delete(String key) throws IOException {
    delete(kvsList, key);
  }

  @Override
  public List<Kvs> getKvsList() {
    return kvsList;
  }

  @Override
  public long getBytesUsed() throws IOException {
    return KvsFacade.getBytesUsed(kvsList);
  }

  @Override
  public boolean hasInternalEncryption() {
    return true;
  }

  public SecretSharingCodec getSecretSharingCodec() {
    return codec;
  }

  @Override
  public String getKvsType() {
    StringBuilder sb = new StringBuilder("s-");
    sb.append(n);
    sb.append('-');
    sb.append(m);
    sb.append('-');
    sb.append(codec.getR());
    return null;
  }
}
