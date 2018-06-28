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
package edu.stonybrook.kurma.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import edu.stonybrook.kurma.cloud.drivers.FaultyKvs;
import edu.stonybrook.kurma.util.RandomBuffer;

@RunWith(Parameterized.class)
public class KvsFacadeFailureTest {
  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[] {"r-4"}, new Object[] {"e-3-1"}, new Object[] {"s-4-1-2"});
  }

  @Parameter
  public String kvsType;

  @Test
  public void testRetryPut() {
    final int N = 4;
    List<Kvs> kvsList = new ArrayList<>(N);
    for (int i = 0; i < N; ++i) {
      kvsList.add(new FaultyKvs(String.format("faulty-%d", i), true, 0, i + 2));
    }

    KvsFacade facade = KvsFacade.newFacade(kvsType, kvsList, Integer.MAX_VALUE);
    RandomBuffer rand = new RandomBuffer(8887);
    final int bytes = 4096;
    for (int i = 0; i < 10; ++i) {
      String key = String.format("key-%d", i);
      byte[] value = rand.genRandomBytes(bytes);
      byte[] value2 = new byte[bytes];
      try {
        assertTrue(facade.put(key, ByteBuffer.wrap(value)));
        ByteBuffer v = facade.get(key, null);
        assertEquals(bytes, v.remaining());
        v.get(value2);
        assertTrue(Arrays.equals(value, value2));
      } catch (IOException e) {
        fail(String.format("put failed with Facade %s", kvsType));
        e.printStackTrace(System.err);
      }
    }
  }

}
