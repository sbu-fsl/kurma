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

import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;

import org.apache.commons.io.FileUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;

public class CryptoUtilsTest extends TestBase {
  @BeforeClass
  public static void setUp() {
    cleanTestDirectory(CryptoUtilsTest.class.getSimpleName());
  }

  @Test
  public void testGenKeyPairs() throws Exception {
    Path pubPath = Paths.get(TEST_DIRECTORY, "pub.der");
    Path priPath = Paths.get(TEST_DIRECTORY, "pri.der");
    CryptoUtils.generateKeyPair("kurma".toCharArray(), pubPath.toString(), priPath.toString());
    assertTrue(FileUtils.sizeOf(pubPath.toFile()) > 0);
    assertTrue(FileUtils.sizeOf(priPath.toFile()) > 0);

    RSAPrivateKey priKey = CryptoUtils.readPrivateKey(priPath.toString());
    RSAPublicKey pubKey = CryptoUtils.readPublicKey(pubPath.toString());
    byte[] data = genRandomBytes(64);

    Cipher cipher = Cipher.getInstance("RSA");
    cipher.init(Cipher.ENCRYPT_MODE, pubKey);
    byte[] encrypted = cipher.doFinal(data);

    cipher.init(Cipher.DECRYPT_MODE, priKey);
    byte[] decrypted = cipher.doFinal(encrypted);

    assertTrue(Arrays.equals(data, decrypted));
  }

}
