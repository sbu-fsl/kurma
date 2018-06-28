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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import secretsharing.CDCodecJNI;

/**
 * TODO: make this a singleton. It is not safe to have create more than one instance because each
 * instance uses the static member of the secretsharing.jni library.
 *
 * @author mchen
 *
 */
public class SecretSharingCodec {
  public static final int MAX_CODECS_ALL = 4096;
  private static final int CODEC_WORKERS = 64;
  private static final int MAX_SECRET_SIZE = 8 << 20;
  private static final Logger LOGGER = LoggerFactory.getLogger(SecretSharingCodec.class);
  private static ConcurrentHashMap<String, SecretSharingCodec> codecs = new ConcurrentHashMap<>();
  private static AtomicInteger cid = new AtomicInteger(0);

  CDCodecJNI codecJni;

  private LinkedBlockingQueue<Integer> availableCodecs;

  /**
   * total number of shares generated from a secret
   */
  private int n;
  /**
   * reliability degree (i.e. maximum number of lost shares that can be tolerated)
   */
  private int m;
  /**
   * confidentiality degree (i.e. maximum number of shares from which nothing can be derived)
   */
  private int r;

  private int nworkers;

  public static SecretSharingCodec getCodec(int type, int n, int m, int r) {
    String k = String.format("%d-%d-%d-%d", type, n, m, r);
    return codecs.computeIfAbsent(k, key -> {
      return new SecretSharingCodec(type, n, m, r, CODEC_WORKERS);
    });
  }

  private SecretSharingCodec(int type, int n, int m, int r, int nworkers) {
    if (cid.addAndGet(nworkers) > MAX_CODECS_ALL) {
      LOGGER.error("there can be at most {} codecs overall", MAX_CODECS_ALL);
      System.exit(1);
    }
    codecJni = new CDCodecJNI();
    availableCodecs = new LinkedBlockingQueue<Integer>();
    for (int i = 0; i < nworkers; ++i) {
      int cid = codecJni.create(type, n, m, r);
      availableCodecs.add(cid);
    }
    this.nworkers = nworkers;
    this.n = n;
    this.m = m;
    this.r = r;
  }

  public void destroy() {
    LinkedBlockingQueue<Integer> codecs = availableCodecs;
    availableCodecs = null; // posion it
    while (nworkers > 0) {
      try {
        int cid = codecs.take();
        codecJni.destroy(cid);
        --nworkers;
      } catch (InterruptedException e) {
        LOGGER.debug("Destroy operation is interrupted. Retrying.");
      }
    }
  }

  public int getN() {
    return n;
  }

  public int getM() {
    return m;
  }

  public int getR() {
    return r;
  }

  private int takeCodecId() {
    int cid;
    while (true) {
      try {
        cid = availableCodecs.take();
        break;
      } catch (InterruptedException e) {
        LOGGER.warn("waiting interrupted", e);
      }
    }
    return cid;
  }

  private void putCodecId(int cid) {
    try {
      availableCodecs.put(cid);
    } catch (InterruptedException e) {
      LOGGER.error("putting to availableCodecs failed, and this should never happen", e);
      System.exit(1);
    }
  }

  public int getShareSize(int secretSize) {
    return codecJni.getShareSize(0, secretSize);
  }

  public int getAlignedSecretSize(int secretSize) {
    return codecJni.getAlignedSecretSize(0, secretSize);
  }

  public int getSizeOfAllShares(int secretSize) {
    return getShareSize(secretSize) * n;
  }

  /**
   * Thread-safe encoding routine.
   *
   * @param secret
   * @param secretSize
   * @param[out] shares Return the "n + m" shares after the encoding
   * @return The size of each share.
   */
  public int encode(byte[] secret, int secretSize, byte[] shares) {
    if (secretSize > MAX_SECRET_SIZE) {
      LOGGER.error("secret size {} larger than maximum secret size {}", secretSize,
          MAX_SECRET_SIZE);
      return -1;
    }
    int cid = takeCodecId();
    try {
      return codecJni.encode(cid, secret, secretSize, shares);
    } finally {
      putCodecId(cid);
    }
  }

  /**
   * Thread-safe encoding routine
   * 
   * @param shares A flat array of "n" shares
   * @param shareSize Size of each share
   * @param erasures
   * @param secret[out] The recovered secret message.
   * @param secretSize
   * @return Whether the decoding is successful.
   */
  public boolean decode(byte[] shares, int shareSize, int[] erasures, byte[] secret,
      int secretSize) {
    int cid = takeCodecId();
    try {
      return codecJni.decode(cid, shares, shareSize, erasures, secret, secretSize);
    } finally {
      putCodecId(cid);
    }
  }
}
