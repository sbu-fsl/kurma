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
package edu.stonybrook.kurma;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;

import com.google.common.base.Preconditions;

import edu.stonybrook.kurma.util.CryptoUtils;

/**
 * KurmaGateway represents a gateway and contains gateway-specific information such as name, id,
 * master key, etc. Both name (two-letter string) and id can uniquely identify a gateway. The master
 * gateway key generates and recovers per-file keys.
 *
 * @author mchen
 *
 */
public class KurmaGateway {
  private static String ENCRYPT_ALGORITHM = "RSA";

  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;
  private final String name;
  private short id;
  private boolean remote;

  public KurmaGateway(String name, String publicKeyFile) throws Exception {
    this(name, publicKeyFile, null);
    remote = true;
  }

  public KurmaGateway(String name, String publicKeyFile, String privateKeyFile) throws Exception {
    this.name = name;
    publicKey = CryptoUtils.readPublicKey(publicKeyFile);
    privateKey = privateKeyFile == null ? null : CryptoUtils.readPrivateKey(privateKeyFile);
    id = KurmaGateway.nameToId(name);
    remote = false;
  }

  public byte[] encrypt(byte[] clearText) throws GeneralSecurityException {
    Cipher encryptor = Cipher.getInstance(ENCRYPT_ALGORITHM);
    encryptor.init(Cipher.ENCRYPT_MODE, publicKey);
    return encryptor.doFinal(clearText);
  }

  public byte[] decrypt(byte[] cipherText) throws GeneralSecurityException, KurmaException {
    return decrypt(ByteBuffer.wrap(cipherText));
  }

  public byte[] decrypt(ByteBuffer cipherText) throws GeneralSecurityException, KurmaException {
    if (isRemote()) {
      throw new KurmaException.RemoteException(
          String.format("decrypt is unavailable for remote gateway %s", name));
    }
    Cipher decryptor = Cipher.getInstance(ENCRYPT_ALGORITHM);
    decryptor.init(Cipher.DECRYPT_MODE, privateKey);
    return decryptor.doFinal(cipherText.array(), cipherText.position(), cipherText.remaining());
  }

  public static short nameToId(String name) throws UnsupportedEncodingException {
    Preconditions.checkArgument(name.length() == 2, "gateway name should be a 2-char string");
    byte[] values = name.getBytes("US-ASCII");
    return ByteBuffer.wrap(values).getShort();
  }

  public static String idToName(short id) throws UnsupportedEncodingException {
    return new String(ByteBuffer.allocate(2).putShort(id).array(), "US-ASCII");
  }

  @Override
  public String toString() {
    return name;
  }

  public short getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public boolean isRemote() {
    return remote;
  }

}
