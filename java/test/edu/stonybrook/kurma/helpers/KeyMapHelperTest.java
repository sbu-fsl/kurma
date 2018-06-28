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
package edu.stonybrook.kurma.helpers;

import static org.junit.Assert.assertEquals;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.Test;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.config.TestGatewayConfig;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;

public class KeyMapHelperTest {

  @Test
  public void testGenerateKeyAndRead() throws Exception {
    KeyGenerator keyGenerator = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    SecretKey key = keyGenerator.generateKey();
    KeyMap keyMap = new KeyMap();
    IGatewayConfig config = new TestGatewayConfig();
    KeyMapHelper.generateKeyMap(keyMap, key, config);
    SecretKey recoveredKey = KeyMapHelper.readKeyMap(keyMap, config);
    assertEquals(key, recoveredKey);
  }

  // TODO add unittest when the KeyMap's key (ByteBuffer) is in the middle of an array
  @Test
  public void benchGenerateKeyMap() throws Exception {
    long start = System.currentTimeMillis();
    final int N = 100;
    KeyGenerator keyGenerator = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    IGatewayConfig config = new TestGatewayConfig();
    for (int i = 0; i < N; ++i) {
      SecretKey key = keyGenerator.generateKey();
      KeyMap keyMap = new KeyMap();
      KeyMapHelper.generateKeyMap(keyMap, key, config);
    }
    long end = System.currentTimeMillis();
    System.out.printf("key map speed: %.2f ms", (end - start + 0.0) / N);
  }


}
