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

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.meta.KeyMap;

public class KurmaKeyGenerator {
  private KeyGenerator keyGenerator;

  public static final int KEY_POOL_SIZE = 128;

  ArrayBlockingQueue<Entry<SecretKey, KeyMap>> keys = new ArrayBlockingQueue<>(KEY_POOL_SIZE);

  class KeyGeneratorThread extends Thread {
    private IGatewayConfig config;
    public KeyGeneratorThread(IGatewayConfig config) {
      this.config = config;
    }

    @Override
    public void run() {
      SecretKey key = null;
      KeyMap keymap = null;
      Entry<SecretKey, KeyMap> entry = null;
      while (true) {
        if (entry == null) {
          key = keyGenerator.generateKey();
          keymap = new KeyMap();
          try {
            KeyMapHelper.generateKeyMap(keymap, key, config);
          } catch (GeneralSecurityException e) {
            e.printStackTrace();
            break;
          }
          entry = new AbstractMap.SimpleEntry<>(key, keymap);
        }
        try {
          keys.put(entry);
          entry = null;
        } catch (InterruptedException e) {
          continue;
        }
      }
    }
  }

  KeyGeneratorThread worker;

  public KurmaKeyGenerator(IGatewayConfig config) throws NoSuchAlgorithmException {
    keyGenerator = KeyGenerator.getInstance(EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
    keyGenerator.init(128);
    worker = new KeyGeneratorThread(config);
    worker.start();
  }

  public Entry<SecretKey, KeyMap> generateKey() {
    Entry<SecretKey, KeyMap> k = null;
    while (k == null) {
      try {
        k = keys.take();
      } catch (InterruptedException e) {
        e.printStackTrace();
        continue;
      }
    }
    return k;
  }
}
