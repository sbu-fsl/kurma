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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.bouncycastle.util.Arrays;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.util.CryptoUtils;

public class KurmaGatewayTest extends TestBase {

  @BeforeClass
  public static void setUp() {
    cleanTestDirectory(KurmaGatewayTest.class.getSimpleName());
  }

  @Test
  public void testNameIdConversion() throws Exception {
    assertEquals(2, "ny".getBytes("US-ASCII").length);
    assertEquals("ny", KurmaGateway.idToName(KurmaGateway.nameToId("ny")));
  }

  @Test
  public void testBasics() throws Exception {
    Path dir = getTestDirectory(KurmaGatewayTest.class.getSimpleName());
    Path pubPath = dir.resolve("pub.der");
    Path priPath = dir.resolve("pri.der");
    CryptoUtils.generateKeyPair("kurma".toCharArray(), pubPath.toString(), priPath.toString());

    // local gateway
    KurmaGateway local = new KurmaGateway("ny", pubPath.toString(), priPath.toString());
    assertFalse(local.isRemote());
    // test encrypt and then decrypt
    for (int i = 0; i < 16; ++i) {
      byte[] data = genRandomBytes(64 + i);
      byte[] encrypted = local.encrypt(data);
      byte[] recovered = local.decrypt(encrypted);
      assertEquals(data.length, recovered.length);
      assertTrue(Arrays.areEqual(data, recovered));
    }

    // remote gateway
    KurmaGateway remote = new KurmaGateway("ny", pubPath.toString());
    assertTrue(remote.isRemote());
    // remote gateways' private keys are not available, so they could not
    // decrypt
    exception.expect(KurmaException.class);
    remote.decrypt(genRandomBytes(8));
  }

}
