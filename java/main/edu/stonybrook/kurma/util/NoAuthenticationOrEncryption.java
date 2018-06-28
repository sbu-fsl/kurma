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
import java.util.Map.Entry;

import com.google.common.io.ByteSource;

/**
 * A null implementation of the AuthenticatedEncryption interface that does NOT perform any
 * authentication or encryption. It is useful to be used with SecretSharingFacade that perform
 * authentication and encryption internally. It is NOT safe to use it with other KvsFacade.
 *
 * @author mchen
 *
 */
public class NoAuthenticationOrEncryption implements AuthenticatedEncryption {

  @Override
  public int getMacLength() {
    return 0;
  }

  @Override
  public int getEncryptOutputLength(int inLength) {
    return inLength;
  }

  @Override
  public int getDecryptOutputLength(int inLength) {
    return inLength;
  }

  @Override
  public byte[] authenticatedEncrypt(byte[] plain, byte[] iv) throws Exception {
    return plain;
  }

  @Override
  public boolean authenticatedEncrypt(ByteBuffer plain, ByteBuffer ad, byte[] iv, ByteBuffer out)
      throws Exception {
    out.put(plain).put(ad);
    return false;
  }

  @Override
  public byte[] authenticatedDecrypt(byte[] cipher, byte[] iv) throws Exception {
    return cipher;
  }

  @Override
  public boolean authenticatedDecrypt(ByteBuffer cipher, byte[] iv, ByteBuffer out, ByteBuffer ad)
      throws Exception {
    byte[] cipherBytes = cipher.array();
    int cipherOffset = cipher.position();
    System.arraycopy(cipherBytes, cipherOffset, out.array(), out.position(), out.remaining());
    if (ad != null) {
      System.arraycopy(cipherBytes, cipherOffset + out.remaining(), ad.array(), ad.position(),
          ad.remaining());
    }
    return true;
  }

  @Override
  public ByteSource authenticatedEncrypt(ByteSource plain, ByteSource ad, byte[] iv)
      throws Exception {
    return ByteSource.concat(plain, ad);
  }

  @Override
  public Entry<ByteSource, ByteSource> authenticatedDecrypt(ByteSource cipher, byte[] iv)
      throws Exception {
    // TODO Auto-generated method stub
    return null;
  }

}
