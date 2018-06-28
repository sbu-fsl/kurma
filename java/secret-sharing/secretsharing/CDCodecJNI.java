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
package secretsharing;

import java.util.Arrays;
import java.util.Random;

public class CDCodecJNI {
  public static final int CRSSS = 0;
  public static final int AONT_RS = 1;
  public static final int OLD_CAONT_RS = 2;
  public static final int CAONT_RS = 3;
  static {
    System.loadLibrary("secretsharing.jni");
  }

  /**
   * Return a ID of the created codec. Because codec is not thread-safe, each of them can be used by
   * at most one thread. However, parallel processing can still be achieved by using multiple
   * Codecs, each of which has a unique ID.
   */
  public native int create(int type, int n, int m, int r);

  public native int getShareSize(int codec_id, int secretSize);

  public native int getAlignedSecretSize(int codec_id, int secretSize);

  /**
   * Note that more than "secretSize" bytes in "secret" may be used.
   * 
   * @param codec_id
   * @param secret
   * @param secretSize
   * @param shares
   * @return
   */
  public native int encode(int codec_id, byte[] secret, int secretSize, byte[] shares);

  public native boolean decode(int codec_id, byte[] shares, int shareSize, int[] erasures,
      byte[] secret, int secretSize);

  public native boolean destroy(int codec_id);

  public static void main(String[] args) {
    CDCodecJNI jni = new CDCodecJNI();
    final int n = 4;
    final int m = 1;
    final int r = 2;

    int cid = jni.create(AONT_RS, n, m, r);

    final int secretSize = 1024;

    byte[] secret = new byte[secretSize];
    Random rand = new Random(8887);
    rand.nextBytes(secret);

    byte[] shares = new byte[secretSize * 2];
    int shareSize = jni.encode(cid, secret, secretSize, shares);
    if (shareSize < 0) {
      System.err.println("encode failed");
      System.exit(1);
    }
    System.out.printf("share size: %d\n", shareSize);

    int[] erasures = new int[n];
    for (int i = 0; i < n; ++i) {
      erasures[i] = i;
    }

    byte[] recovered = new byte[secretSize];
    if (!jni.decode(cid, shares, shareSize, erasures, recovered, secretSize)) {
      System.err.println("decode failed");
      System.exit(1);
    }

    System.out.println(Arrays.equals(secret, recovered));

    jni.destroy(cid);
  }
}
