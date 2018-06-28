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

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map.Entry;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import com.google.common.base.Preconditions;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;

/**
 * A simple encryption (AES) then authenticate (SHA1) scheme.
 *
 * Its encrypt/decrypt APIs are thread-safe.
 *
 * @author mchen
 *
 */
public class EncryptThenAuthenticate implements AuthenticatedEncryption {
  public static final String ENCRYPT_ALGORITHM = "AES";
  public static final String TRANSFORMATION = "AES/CTR/NoPadding";
  public static final int MAC_LENGTH = 32; // SHA256

  private final SecretKey key;

  public EncryptThenAuthenticate(SecretKey key)
      throws NoSuchAlgorithmException, NoSuchPaddingException {
    this.key = key;
  }

  @Override
  public byte[] authenticatedEncrypt(byte[] plain, byte[] iv) throws Exception {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
    byte[] out = ByteBuffer.allocate(plain.length + MAC_LENGTH).array();
    int length = cipher.doFinal(plain, 0, plain.length, out, 0);
    assert (length == plain.length);
    Hashing.sha256().hashBytes(out, 0, length).writeBytesTo(out, length, MAC_LENGTH);
    return out;
  }

  @Override
  public byte[] authenticatedDecrypt(byte[] data, byte[] iv) throws Exception {
    int length = data.length - MAC_LENGTH;
    byte[] mac = Hashing.sha256().hashBytes(data, 0, length).asBytes();
    if (!Arrays.equals(mac, Arrays.copyOfRange(data, length, data.length))) {
      throw new BadPaddingException("SHA1 mistmatch");
    }
    byte[] out = ByteBuffer.allocate(length).array();
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
    int n = cipher.doFinal(data, 0, length, out, 0);
    assert (n == length);
    return out;
  }

  @Override
  public boolean authenticatedEncrypt(ByteBuffer plain, ByteBuffer ad, byte[] iv, ByteBuffer out)
      throws Exception {
    // TODO make this method thread-safe
    Preconditions.checkArgument(plain.remaining() > 0);
    Preconditions.checkArgument(out.hasArray());
    Preconditions.checkArgument(out.remaining() >= (plain.remaining() + MAC_LENGTH));

    int outOffset = out.arrayOffset() + out.position();
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));

    int length = plain.remaining();
    int n = cipher.doFinal(plain, out);
    assert (n == length);

    int adLength = (ad == null ? 0 : ad.remaining());
    if (adLength > 0) {
      n = cipher.doFinal(ad, out);
      assert (n == adLength);
    }

    Hasher h = Hashing.sha256().newHasher();
    h.putBytes(key.getEncoded());
    h.putBytes(out.array(), outOffset, outOffset + length + adLength);
    byte[] mac = h.hash().asBytes();
    out.put(mac);
    return true;
  }

  @Override
  public boolean authenticatedDecrypt(ByteBuffer data, byte[] iv, ByteBuffer out, ByteBuffer ad)
      throws Exception {
    Preconditions.checkArgument(data.remaining() > 0);
    Preconditions.checkArgument(data.hasArray());

    int inOffset = data.arrayOffset() + data.position();
    int adLength = (ad == null ? 0 : ad.remaining());
    Preconditions.checkArgument(data.remaining() > (adLength + MAC_LENGTH));
    int length = data.remaining() - adLength - MAC_LENGTH;

    Hasher h = Hashing.sha256().newHasher();
    byte[] keybuf = key.getEncoded();
    h.putBytes(keybuf);
    h.putBytes(data.array(), inOffset, length + adLength);
    byte[] mac1 = h.hash().asBytes();
    int macOffset = inOffset + length + adLength;
    byte[] mac2 = Arrays.copyOfRange(data.array(), macOffset, macOffset + MAC_LENGTH);
    if (!Arrays.equals(mac1, mac2)) {
      throw new BadPaddingException("SHA1 mistmatch");
    }

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
    int decryptLength = Integer.min(length, out.remaining());
    int n = cipher.doFinal(ByteBuffer.wrap(data.array(), inOffset, decryptLength), out);
    assert (n == decryptLength);

    if (adLength > 0) {
      n = cipher.doFinal(ByteBuffer.wrap(data.array(), inOffset + length, adLength), ad);
      assert (n == adLength);
    }

    return true;
  }

  @Override
  public int getMacLength() {
    return MAC_LENGTH;
  }

  @Override
  public int getEncryptOutputLength(int inLength) {
    return inLength + MAC_LENGTH;
  }

  @Override
  public int getDecryptOutputLength(int inLength) {
    return inLength + MAC_LENGTH;
  }

  @Override
  public ByteSource authenticatedEncrypt(ByteSource plain, ByteSource ad, byte[] iv)
      throws Exception {
    int resultSize = (int) (plain.size() + ad.size() + MAC_LENGTH);
    ByteBuffer plainBuf;
    if (plain instanceof ByteBufferSource) {
      plainBuf = ((ByteBufferSource) plain).getBackingBuffer();
    } else {
      plainBuf = ByteBuffer.wrap(plain.read());
    }

    ByteBuffer adBuf;
    if (ad instanceof ByteBufferSource) {
      adBuf = ((ByteBufferSource) ad).getBackingBuffer();
    } else {
      adBuf = ByteBuffer.wrap(ad.read());
    }

    byte[] out = new byte[resultSize];
    boolean res = authenticatedEncrypt(plainBuf, adBuf, iv, ByteBuffer.wrap(out));

    return res ? new ByteBufferSource(ByteBuffer.wrap(out)) : null;
  }

  @Override
  public Entry<ByteSource, ByteSource> authenticatedDecrypt(ByteSource cipher, byte[] iv)
      throws Exception {

    return null;
  }

}
