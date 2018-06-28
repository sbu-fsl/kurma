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
package edu.stonybrook.kurma.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

import edu.stonybrook.kurma.TestBase;

public class EncryptThenAuthenticateTest extends TestBase {
  private EncryptThenAuthenticate eta;

  public EncryptThenAuthenticateTest() throws Exception {
    KeyGenerator kg = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    kg.init(128);
    eta = new EncryptThenAuthenticate(kg.generateKey());
  }

  @Test
  public void testBasics() {
    // the data does not have to be block aligned
    for (int i = 0; i <= 4096; ++i) {
      byte[] data = genRandomBytes(64 + i);
      byte[] iv = genRandomBytes(16);
      try {
        byte[] encrypted = eta.authenticatedEncrypt(data, iv);
        assertEquals(data.length + EncryptThenAuthenticate.MAC_LENGTH, encrypted.length);
        byte[] recovered = eta.authenticatedDecrypt(encrypted, iv);
        assertEquals(data.length, recovered.length);
        assertTrue(Arrays.equals(data, recovered));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  @Test
  public void testTamperDetection() throws Exception {
    byte[] data = genRandomBytes(64);
    byte[] iv = genRandomBytes(16);
    byte[] encrypted = eta.authenticatedEncrypt(data, iv);
    assertEquals(data.length + EncryptThenAuthenticate.MAC_LENGTH, encrypted.length);

    // tamper data
    encrypted[encrypted.length - 1] += 1;

    exception.expect(BadPaddingException.class);
    eta.authenticatedDecrypt(encrypted, iv);
  }

  @Test
  public void testByteBufferVersion() throws Exception {
    ByteBuffer in = genRandomBuffer(64);
    byte[] iv = genRandomBytes(16);
    ByteBuffer out = ByteBuffer.allocate(64 + EncryptThenAuthenticate.MAC_LENGTH);
    out.mark();
    assertTrue(eta.authenticatedEncrypt(in, null, iv, out));

    out.reset();
    ByteBuffer recover = ByteBuffer.allocate(64);
    assertTrue(eta.authenticatedDecrypt(out, iv, recover, null));
    assertTrue(Arrays.equals(in.array(), recover.array()));
  }

  @Test
  public void testByteBufferTamperDetection() throws Exception {
    ByteBuffer in = genRandomBuffer(64);
    byte[] iv = genRandomBytes(16);
    ByteBuffer out = ByteBuffer.allocate(64 + EncryptThenAuthenticate.MAC_LENGTH);
    out.mark();
    assertTrue(eta.authenticatedEncrypt(in, null, iv, out));

    // tamper data
    byte[] arr = out.array();
    arr[arr.length - 1] += 1;

    out.reset();
    ByteBuffer recover = ByteBuffer.allocate(64);
    exception.expect(BadPaddingException.class);
    assertTrue(eta.authenticatedDecrypt(out, iv, recover, null));
  }

  @Test
  public void testKeyRecover() throws Exception {
    KeyGenerator keyGenerator = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    SecretKey originKey = keyGenerator.generateKey();
    byte[] iv = genRandomBytes(16);
    AuthenticatedEncryption originAe = new EncryptThenAuthenticate(originKey);
    byte[] data = genRandomBytes(1024 * 1024);
    byte[] ciphertext = originAe.authenticatedEncrypt(data, iv);

    byte[] rawkey = originKey.getEncoded();
    SecretKey fileKey = new SecretKeySpec(rawkey, EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    AuthenticatedEncryption ae = new EncryptThenAuthenticate(fileKey);
    byte[] recover = ae.authenticatedDecrypt(ciphertext, iv);
    assertTrue(Arrays.equals(data, recover));
  }

  @Test
  public void testEncryptSharedArray() throws Exception {
    byte[] data = genRandomBytes(64 + 64);
    byte[] iv1 = genRandomBytes(16);
    byte[] iv2 = genRandomBytes(16);

    ByteBuffer buf1 = ByteBuffer.wrap(data, 0, 64);
    ByteBuffer buf2 = ByteBuffer.wrap(data, 64, 64);

    ByteBuffer enc1 = ByteBuffer.allocate(64 + EncryptThenAuthenticate.MAC_LENGTH);
    ByteBuffer enc2 = ByteBuffer.allocate(64 + EncryptThenAuthenticate.MAC_LENGTH);
    enc1.mark();
    enc2.mark();

    assertTrue(eta.authenticatedEncrypt(buf1, null, iv1, enc1));
    assertTrue(eta.authenticatedEncrypt(buf2, null, iv2, enc2));

    byte[] recovered = new byte[64 + 64];
    ByteBuffer rec1 = ByteBuffer.wrap(recovered, 0, 64);
    ByteBuffer rec2 = ByteBuffer.wrap(recovered, 64, 64);

    enc1.reset();
    enc2.reset();
    assertTrue(eta.authenticatedDecrypt(enc1, iv1, rec1, null));
    assertTrue(eta.authenticatedDecrypt(enc2, iv2, rec2, null));

    assertTrue(Arrays.equals(data, recovered));
  }

  @Test
  public void testAdditionalData() throws Exception {
    for (int i = 0; i < 100; ++i) {
      ByteBuffer in = genRandomBuffer(64);
      ByteBuffer ad = genRandomBuffer(16);
      byte[] iv = genRandomBytes(16);
      ByteBuffer out = ByteBuffer.allocate(64 + 16 + EncryptThenAuthenticate.MAC_LENGTH);
      out.mark();
      assertTrue(eta.authenticatedEncrypt(in, ad, iv, out));

      out.reset();
      ByteBuffer recover = ByteBuffer.allocate(64);
      ByteBuffer ad2 = ByteBuffer.allocate(16);
      assertTrue(eta.authenticatedDecrypt(out, iv, recover, ad2));
      assertTrue(Arrays.equals(in.array(), recover.array()));
      assertTrue(Arrays.equals(ad.array(), ad2.array()));
    }
  }

  @Test
  public void testUnalignedOperations() throws Exception {
    ByteBuffer in = genRandomBuffer(63);
    byte[] iv = genRandomBytes(16);
    ByteBuffer encrypted = ByteBuffer.allocate(63 + EncryptThenAuthenticate.MAC_LENGTH);
    assertTrue(eta.authenticatedEncrypt(in, null, iv, encrypted));
    ByteBuffer recover = ByteBuffer.allocate(63);
    encrypted.rewind();
    eta.authenticatedDecrypt(encrypted, iv, recover, null);
    assertEquals(0, recover.remaining());
    recover.rewind();
    in.rewind();
    assertEquals(in, recover);
  }

  @Test
  public void testDecryptLessThanEncrypted() throws Exception {
    byte[] data = genRandomBytes(64);
    ByteBuffer in = ByteBuffer.wrap(data);
    byte[] iv = genRandomBytes(16);
    ByteBuffer encrypted = ByteBuffer.allocate(64 + EncryptThenAuthenticate.MAC_LENGTH);
    assertTrue(eta.authenticatedEncrypt(in, null, iv, encrypted));
    encrypted.rewind();
    ByteBuffer recover = ByteBuffer.allocate(32);
    eta.authenticatedDecrypt(encrypted, iv, recover, null);
    recover.rewind();
    assertEquals(ByteBuffer.wrap(data, 0, 32), recover);
  }
}
