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

import java.util.Arrays;
import java.util.Map.Entry;

import javax.crypto.SecretKey;

import org.junit.Test;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.config.TestGatewayConfig;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.meta.KeyMap;

public class KurmaKeyGeneratorTest {

  @Test
  public void testBasics() throws Exception {
    IGatewayConfig config = new TestGatewayConfig();
    KurmaKeyGenerator keyGenerator = new KurmaKeyGenerator(config);
    long start = System.currentTimeMillis();
    for (int i = 0; i < 2 * KurmaKeyGenerator.KEY_POOL_SIZE; ++i) {
      Entry<SecretKey, KeyMap> km = keyGenerator.generateKey();
      SecretKey k = KeyMapHelper.readKeyMap(km.getValue(), config);
      assertTrue(Arrays.equals(km.getKey().getEncoded(), k.getEncoded()));
    }
    long end = System.currentTimeMillis();
    System.out.printf("Key generation time: %.2f",
        (end - start + 0.0) / (2 * KurmaKeyGenerator.KEY_POOL_SIZE));
  }

}
