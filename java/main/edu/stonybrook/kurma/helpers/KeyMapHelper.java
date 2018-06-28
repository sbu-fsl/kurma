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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.HashMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.KurmaException;
import edu.stonybrook.kurma.KurmaGateway;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;

public class KeyMapHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(KeyMapHelper.class);

  public static KeyMap newKeyMap(SecretKey fileKey, IGatewayConfig config) throws GeneralSecurityException {
    KeyMap keymap = new KeyMap();
    generateKeyMap(keymap, fileKey, config);
    return keymap;
  }

  public static void generateKeyMap(KeyMap keyMap, SecretKey fileKey, IGatewayConfig config)
      throws GeneralSecurityException {
    HashMap<Short, ByteBuffer> km = new HashMap<>();
    byte[] keyData = fileKey.getEncoded();
    for (KurmaGateway kg : config.getGateways()) {
      km.put(kg.getId(), ByteBuffer.wrap(kg.encrypt(keyData)));
      LOGGER.trace("encryptedKey length: {} {}", kg.getId(), km.get(kg.getId()));
    }
    keyMap.setKeymap(km);
  }

  public static SecretKey readKeyMap(KeyMap keyMap, IGatewayConfig config)
      throws GeneralSecurityException, KurmaException {
    ByteBuffer encryptedKey = keyMap.getKeymap().get(config.getGatewayId());
    if (encryptedKey == null) {
      LOGGER.error("could not find file key for current gateway");
      return null;
    }
    LOGGER.trace("encryptedKey length: {}", encryptedKey);
    byte[] decryptedKey = config.getLocalGateway().decrypt(encryptedKey);
    return new SecretKeySpec(decryptedKey, EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
  }
}
