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

import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import edu.stonybrook.kurma.util.EncryptThenAuthenticate;

/**
 * Adapted from
 * http://stackoverflow.com/questions/25992131/slow-aes-gcm-encryption-and-decryption-with-java-8u20
 * and http://unafbapune.blogspot.com/2012/06/aesgcm-with-associated-data.html
 *
 * Results on zk101@sgdp10: #java -cp ./bcprov-jdk15on-1.51.jar:.
 * edu.stonybrook.kurma.benchmark.BenchmarkGCM
 *
 * Benchmarking javax AES-256 GCM encryption for 10 seconds Time init (ns): 4353 Time update (ns):
 * 19141090 Time do final (ns): 1422662 Java calculated at 3 MB/s
 *
 * Benchmarking javax AES-256 GCM decryption for 10 seconds Time init (ns): 2631747 Time update 1
 * (ns): 11333210 Time update 2 (ns): 13199076 Time do final (ns): 9983808360 Total bytes processed:
 * 33816576 Java calculated at 3 MB/s
 *
 * Benchmarking BouncyCastle AES-256 GCM encryption for 10 seconds Time init (ns): 2829 Time update
 * (ns): 1745900 Time do final (ns): 1413 Java calculated at 34 MB/s
 *
 * Benchmarking BouncyCastle AES-256 GCM decryption for 10 seconds Time init (ns): 16326567 Time
 * update 1 (ns): 9964705214 Time update 2 (ns): 14465446 Time do final (ns): 511412 Total bytes
 * processed: 362348544 Java calculated at 34 MB/s
 *
 * #java -cp ./bcprov-jdk15on-1.51.jar:./guava-18.0.jar:.
 * edu.stonybrook.kurma.benchmark.BenchmarkGCM Benchmarking AES-then-SHA1 for 10 seconds Time init
 * (ns): 782520 Time update (ns): 361091 Time do final (ns): 117 Java calculated at 52 MB/s
 *
 * Benchmarking AES-then-SHA1 decryption for 10 seconds Time init (ns): 2957497250 Time update 1
 * (ns): 7031760071 Time update 2 (ns): 1630886 Time do final (ns): 695446 Total bytes processed:
 * 541261824 Java calculated at 51 MB/s
 *
 *
 * Results on Macbook:
 *
 * Benchmarking javax AES-256 GCM encryption for 10 seconds Time init (ns): 7080 Time update (ns):
 * 16912749 Time do final (ns): 9636 Java calculated at 3 MB/s
 *
 * Benchmarking javax AES-256 GCM decryption for 10 seconds Time init (ns): 4124372 Time update 1
 * (ns): 11972193 Time update 2 (ns): 10009324 Time do final (ns): 9976426305 Total bytes processed:
 * 41943040 Java calculated at 4 MB/s
 *
 * Benchmarking BouncyCastle AES-256 GCM encryption for 10 seconds Time init (ns): 1744 Time update
 * (ns): 1358286 Time do final (ns): 1065 Java calculated at 41 MB/s
 *
 * Benchmarking BouncyCastle AES-256 GCM decryption for 10 seconds Time init (ns): 16288681 Time
 * update 1 (ns): 9971329446 Time update 2 (ns): 8774835 Time do final (ns): 457174 Total bytes
 * processed: 434241536 Java calculated at 41 MB/s
 *
 * Benchmarking AES-then-SHA1 for 10 seconds Time init (ns): 686403 Time update (ns): 275599 Time do
 * final (ns): 100 Java calculated at 113 MB/s
 *
 * Benchmarking AES-256 GCM decryption for 10 seconds Time init (ns): 5483195485 Time update 1 (ns):
 * 4505182381 Time update 2 (ns): 2052512 Time do final (ns): 1244137 Total bytes processed:
 * 1232142336 Java calculated at 117 MB/s
 *
 */
public class BenchmarkGCM {

  public static void testJavax() throws Exception {

    final byte[] data = new byte[64 * 1024];
    final byte[] encrypted = new byte[64 * 1024];
    final byte[] key = new byte[16];
    final byte[] iv = new byte[12];
    final Random random = new Random(1);
    random.nextBytes(data);
    random.nextBytes(key);
    random.nextBytes(iv);

    System.out.println("Benchmarking javax AES-256 GCM encryption for 10 seconds");
    long javaEncryptInputBytes = 0;
    long javaEncryptStartTime = System.currentTimeMillis();
    final Cipher javaAES256 = Cipher.getInstance("AES/GCM/NoPadding");
    byte[] tag = new byte[16];
    long encryptInitTime = 0L;
    long encryptUpdate1Time = 0L;
    long encryptDoFinalTime = 0L;
    while (System.currentTimeMillis() - javaEncryptStartTime < 10000) {
      random.nextBytes(iv);
      long n1 = System.nanoTime();
      javaAES256.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(16 * Byte.SIZE, iv));
      long n2 = System.nanoTime();
      javaAES256.update(data, 0, data.length, encrypted, 0);
      long n3 = System.nanoTime();
      javaAES256.doFinal(tag, 0);
      long n4 = System.nanoTime();
      javaEncryptInputBytes += data.length;

      encryptInitTime = n2 - n1;
      encryptUpdate1Time = n3 - n2;
      encryptDoFinalTime = n4 - n3;
    }
    long javaEncryptEndTime = System.currentTimeMillis();
    System.out.println("Time init (ns): " + encryptInitTime);
    System.out.println("Time update (ns): " + encryptUpdate1Time);
    System.out.println("Time do final (ns): " + encryptDoFinalTime);
    System.out.println("Java calculated at " + (javaEncryptInputBytes / 1024 / 1024
        / ((javaEncryptEndTime - javaEncryptStartTime) / 1000)) + " MB/s");

    System.out.println("Benchmarking javax AES-256 GCM decryption for 10 seconds");
    long javaDecryptInputBytes = 0;
    long javaDecryptStartTime = System.currentTimeMillis();
    final GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(16 * Byte.SIZE, iv);
    final SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
    long decryptInitTime = 0L;
    long decryptUpdate1Time = 0L;
    long decryptUpdate2Time = 0L;
    long decryptDoFinalTime = 0L;
    while (System.currentTimeMillis() - javaDecryptStartTime < 10000) {
      long n1 = System.nanoTime();
      javaAES256.init(Cipher.DECRYPT_MODE, keySpec, gcmParameterSpec);
      long n2 = System.nanoTime();
      int offset = javaAES256.update(encrypted, 0, encrypted.length, data, 0);
      long n3 = System.nanoTime();
      javaAES256.update(tag, 0, tag.length, data, offset);
      long n4 = System.nanoTime();
      javaAES256.doFinal(data, offset);
      long n5 = System.nanoTime();
      javaDecryptInputBytes += data.length;

      decryptInitTime += n2 - n1;
      decryptUpdate1Time += n3 - n2;
      decryptUpdate2Time += n4 - n3;
      decryptDoFinalTime += n5 - n4;
    }
    long javaDecryptEndTime = System.currentTimeMillis();
    System.out.println("Time init (ns): " + decryptInitTime);
    System.out.println("Time update 1 (ns): " + decryptUpdate1Time);
    System.out.println("Time update 2 (ns): " + decryptUpdate2Time);
    System.out.println("Time do final (ns): " + decryptDoFinalTime);
    System.out.println("Total bytes processed: " + javaDecryptInputBytes);
    System.out.println("Java calculated at " + (javaDecryptInputBytes / 1024 / 1024
        / ((javaDecryptEndTime - javaDecryptStartTime) / 1000)) + " MB/s");
  }

  public static void testBouncyCastle() throws Exception {
    final int MAC_SIZE = 128;
    final byte[] data = new byte[64 * 1024];
    byte[] encrypted = null;
    final byte[] key = new byte[32];
    final byte[] iv = new byte[12];
    final byte[] authdata = new byte[16];
    final Random random = new Random(1);
    random.nextBytes(data);
    random.nextBytes(key);
    random.nextBytes(iv);
    random.nextBytes(authdata);

    System.out.println("Benchmarking BouncyCastle AES-256 GCM encryption for 10 seconds");
    long javaEncryptInputBytes = 0;
    long javaEncryptStartTime = System.currentTimeMillis();
    byte[] tag = new byte[16];
    AEADParameters params = new AEADParameters(new KeyParameter(key), MAC_SIZE, iv, tag);
    GCMBlockCipher gcm = new GCMBlockCipher(new AESEngine());
    long encryptInitTime = 0L;
    long encryptUpdate1Time = 0L;
    long encryptDoFinalTime = 0L;
    while (System.currentTimeMillis() - javaEncryptStartTime < 10000) {
      random.nextBytes(iv);
      long n1 = System.nanoTime();
      gcm.init(true, params);
      long n2 = System.nanoTime();
      if (encrypted == null) {
        int outSize = gcm.getOutputSize(data.length);
        encrypted = new byte[outSize];
      }
      int offOut = gcm.processBytes(data, 0, data.length, encrypted, 0);
      long n3 = System.nanoTime();
      gcm.doFinal(encrypted, offOut);
      long n4 = System.nanoTime();
      javaEncryptInputBytes += data.length;

      encryptInitTime = n2 - n1;
      encryptUpdate1Time = n3 - n2;
      encryptDoFinalTime = n4 - n3;
    }
    long javaEncryptEndTime = System.currentTimeMillis();
    System.out.println("Time init (ns): " + encryptInitTime);
    System.out.println("Time update (ns): " + encryptUpdate1Time);
    System.out.println("Time do final (ns): " + encryptDoFinalTime);
    System.out.println("Java calculated at " + (javaEncryptInputBytes / 1024 / 1024
        / ((javaEncryptEndTime - javaEncryptStartTime) / 1000)) + " MB/s");

    System.out.println("Benchmarking BouncyCastle AES-256 GCM decryption for 10 seconds");
    long javaDecryptInputBytes = 0;
    long javaDecryptStartTime = System.currentTimeMillis();
    long decryptInitTime = 0L;
    long decryptUpdate1Time = 0L;
    long decryptUpdate2Time = 0L;
    long decryptDoFinalTime = 0L;
    while (System.currentTimeMillis() - javaDecryptStartTime < 10000) {
      long n1 = System.nanoTime();
      gcm.init(false, params);
      long n2 = System.nanoTime();
      int offOut = gcm.processBytes(encrypted, 0, encrypted.length, data, 0);
      long n3 = System.nanoTime();
      gcm.doFinal(data, offOut);
      long n4 = System.nanoTime();
      long n5 = System.nanoTime();
      javaDecryptInputBytes += data.length;

      decryptInitTime += n2 - n1;
      decryptUpdate1Time += n3 - n2;
      decryptUpdate2Time += n4 - n3;
      decryptDoFinalTime += n5 - n4;
    }
    long javaDecryptEndTime = System.currentTimeMillis();
    System.out.println("Time init (ns): " + decryptInitTime);
    System.out.println("Time update 1 (ns): " + decryptUpdate1Time);
    System.out.println("Time update 2 (ns): " + decryptUpdate2Time);
    System.out.println("Time do final (ns): " + decryptDoFinalTime);
    System.out.println("Total bytes processed: " + javaDecryptInputBytes);
    System.out.println("Java calculated at " + (javaDecryptInputBytes / 1024 / 1024
        / ((javaDecryptEndTime - javaDecryptStartTime) / 1000)) + " MB/s");
  }

  public static void testEncryptThenAuth() throws Exception {
    final byte[] data = new byte[64 * 1024];
    byte[] encrypted = null;
    final byte[] iv = new byte[16];
    final Random random = new Random(1);
    random.nextBytes(data);
    random.nextBytes(iv);
    SecretKey k = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM).generateKey();
    EncryptThenAuthenticate eta = new EncryptThenAuthenticate(k);

    System.out.println("Benchmarking AES-then-SHA256 for 10 seconds");
    long javaEncryptInputBytes = 0;
    long javaEncryptStartTime = System.currentTimeMillis();
    long encryptInitTime = 0L;
    long encryptUpdate1Time = 0L;
    long encryptDoFinalTime = 0L;
    while (System.currentTimeMillis() - javaEncryptStartTime < 10000) {
      random.nextBytes(iv);
      long n1 = System.nanoTime();
      encrypted = eta.authenticatedEncrypt(data, iv);
      long n2 = System.nanoTime();
      long n3 = System.nanoTime();
      long n4 = System.nanoTime();
      javaEncryptInputBytes += data.length;

      encryptInitTime = n2 - n1;
      encryptUpdate1Time = n3 - n2;
      encryptDoFinalTime = n4 - n3;
    }
    long javaEncryptEndTime = System.currentTimeMillis();
    System.out.println("Time init (ns): " + encryptInitTime);
    System.out.println("Time update (ns): " + encryptUpdate1Time);
    System.out.println("Time do final (ns): " + encryptDoFinalTime);
    System.out.println("Java calculated at " + (javaEncryptInputBytes / 1024 / 1024
        / ((javaEncryptEndTime - javaEncryptStartTime) / 1000)) + " MB/s");

    System.out.println("Benchmarking AES-then-SHA256 decryption for 10 seconds");
    long javaDecryptInputBytes = 0;
    long javaDecryptStartTime = System.currentTimeMillis();
    long decryptInitTime = 0L;
    long decryptUpdate1Time = 0L;
    long decryptUpdate2Time = 0L;
    long decryptDoFinalTime = 0L;
    while (System.currentTimeMillis() - javaDecryptStartTime < 10000) {
      long n1 = System.nanoTime();
      eta.authenticatedDecrypt(encrypted, iv);
      long n2 = System.nanoTime();
      long n3 = System.nanoTime();
      long n4 = System.nanoTime();
      long n5 = System.nanoTime();
      javaDecryptInputBytes += data.length;

      decryptInitTime += n2 - n1;
      decryptUpdate1Time += n3 - n2;
      decryptUpdate2Time += n4 - n3;
      decryptDoFinalTime += n5 - n4;
    }
    long javaDecryptEndTime = System.currentTimeMillis();
    System.out.println("Time init (ns): " + decryptInitTime);
    System.out.println("Time update 1 (ns): " + decryptUpdate1Time);
    System.out.println("Time update 2 (ns): " + decryptUpdate2Time);
    System.out.println("Time do final (ns): " + decryptDoFinalTime);
    System.out.println("Total bytes processed: " + javaDecryptInputBytes);
    System.out.println("Java calculated at " + (javaDecryptInputBytes / 1024 / 1024
        / ((javaDecryptEndTime - javaDecryptStartTime) / 1000)) + " MB/s");
  }

  public static void main(String[] args) throws Exception {
    // testJavax();
    // testBouncyCastle();
    testEncryptThenAuth();
  }
}
