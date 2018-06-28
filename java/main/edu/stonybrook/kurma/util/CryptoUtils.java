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

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CryptoUtils {
  private static final Logger LOGGER = LoggerFactory.getLogger(CryptoUtils.class);
  public static RSAPublicKey readPublicKey(String filePath) throws Exception {
    byte[] buf = Files.readAllBytes(Paths.get(filePath));
    return decodePublicKey(buf);
  }

  public static RSAPrivateKey readPrivateKey(String filePath) throws Exception {
    byte[] buf = Files.readAllBytes(Paths.get(filePath));
    return decodePrivateKey(buf);
  }

  public static RSAPrivateKey decodePrivateKey(byte[] keyBuf) throws Exception {
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBuf);
    KeyFactory rsaFact = KeyFactory.getInstance("RSA");
    RSAPrivateKey key = (RSAPrivateKey) rsaFact.generatePrivate(spec);
    return key;
  }

  public static RSAPublicKey decodePublicKey(byte[] keyBuf) throws Exception {
    X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBuf);
    KeyFactory rsaFact = KeyFactory.getInstance("RSA");
    RSAPublicKey key = (RSAPublicKey) rsaFact.generatePublic(spec);
    return key;
  }

  public static void generateKeyPair(char[] password, String pubKeyFile, String priKeyFile)
      throws Exception {
    try {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
      SecureRandom random = SecureRandom.getInstance("NativePRNG");
      keyGen.initialize(1024, random);

      KeyPair pair = keyGen.generateKeyPair();
      PrivateKey pri = pair.getPrivate();
      PublicKey pub = pair.getPublic();

      // save public key
      try (FileOutputStream fos = new FileOutputStream(pubKeyFile)) {
        fos.write(pub.getEncoded());
      }

      try (FileOutputStream fos = new FileOutputStream(priKeyFile)) {
        fos.write(pri.getEncoded());
      }

      // save private key
      // JceOpenSSLPKCS8EncryptorBuilder builder =
      // new
      // JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.PBE_SHA1_3DES);
      // builder.setRandom(random);
      // builder.setPasssword(password);
      //
      // OutputEncryptor oe = builder.build();
      // JcaPKCS8Generator gen = new JcaPKCS8Generator(pri, oe);
      //
      // try (PemWriter writer = new PemWriter(new
      // FileWriter(priKeyFile))) {
      // writer.writeObject(gen);
      // }

    } catch (Exception e) {
      LOGGER.error("failed to generate key pair", e);
    }
  }

  public static SecretKey generateAesKey() throws Exception {
    KeyGenerator keyGen = KeyGenerator.getInstance("AES");
    SecureRandom random = new SecureRandom();
    keyGen.init(random);
    return keyGen.generateKey();
  }
}
