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
package edu.stonybrook.kurma.bench;

import secretsharing.CDCodecJNI;

import com.google.caliper.Benchmark;
import com.google.caliper.Param;

import edu.stonybrook.kurma.cloud.SecretSharingCodec;
import edu.stonybrook.kurma.util.RandomBuffer;

/**
 * Results of running on MacBook 2.6 GHz Intel Core i5:
 * https://microbenchmarks.appspot.com/runs/aad4509f-9518-4cbe-bf36-7c8de6ba6d30 The throughput is
 * about 62 MB/s for both encoding and decoding using AONT_RS.
 *
 * https://microbenchmarks.appspot.com/runs/85836ff1-6a5d-4a47-bbee-1e6d4b6ad0e0 The throughput is
 * about 60 MB/s for both encoding and decoding using CRSSS.
 *
 * https://microbenchmarks.appspot.com/runs/1d07a0e2-b80d-49eb-ae04-8d05dd680411 The throughput is
 * about 63 MB/s for both encoding and decoding using CAONT_RS.
 *
 * @author mchen
 *
 */
public class SecretSharingBenchmark {
  @Param({"4096", "65536", "1048576"})
  int secretSize;

  @Param({"True", "False"})
  boolean align;

  static SecretSharingCodec codec = SecretSharingCodec.getCodec(CDCodecJNI.CAONT_RS, 4, 1, 2);

  @Benchmark
  void timeEncoding(int reps) {
    RandomBuffer rand = new RandomBuffer();
    int secretBufSize = align ? codec.getAlignedSecretSize(secretSize) : secretSize;
    int shareBufSize = codec.getSizeOfAllShares(secretSize);
    byte[] secret = rand.genRandomBytes(secretBufSize);
    byte[] shares = new byte[shareBufSize];
    for (int i = 0; i < reps; ++i) {
      codec.encode(secret, secretSize, shares);
    }
  }

  @Benchmark
  void timeDecoding(int reps) {
    RandomBuffer rand = new RandomBuffer();
    int shareBufSize = codec.getSizeOfAllShares(secretSize);
    byte[] secret = rand.genRandomBytes(secretSize);
    byte[] shares = new byte[shareBufSize];
    int shareSize = codec.encode(secret, secretSize, shares);
    int[] erasures = new int[] {0, 1, 2, 3};
    for (int i = 0; i < reps; ++i) {
      codec.decode(shares, shareSize, erasures, secret, secretSize);
    }
  }

  public static void main(String[] args) {
    String[] myargs = new String[1];
    myargs[0] = SecretSharingBenchmark.class.getName();
    com.google.caliper.runner.CaliperMain.main(myargs);
  }
}
