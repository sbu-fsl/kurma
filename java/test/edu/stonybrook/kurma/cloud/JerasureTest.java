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

import static edu.stonybrook.kurma.util.AlgorithmUtils.summarizeBuffer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;

import edu.stonybrook.kurma.KurmaException.ErasureException;
import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.util.ByteBufferSource;

/**
 * Jerasure unit tests.
 *
 * See https://github.com/tsuraan/Jerasure/blob/master/Examples/reed_sol_01.c for examples.
 *
 * @author mchen
 *
 */
public class JerasureTest extends TestBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(JerasureTest.class);
  private EcManager ec;
  private InputStream[] input;
  private int k, m, bufferSize;
  private int[] completeErasures;

  @Before
  public void setUp() throws Exception {
    // Specify value of k (Data blocks) and m (Coding block) and bufferSize
    // here
    k = 7;
    m = 3;
    bufferSize = 65536;
    completeErasures = new int[k + m + 1];
    completeErasures[k + m] = -1;
    ec = new EcManager();
    input = new InputStream[k + m];
  }

  /**
   * Return an array of the indices of the missed blocks.
   * 
   * @param n
   * @return
   */
  public int[] getErasuresWithNMissing(int n) {
    Random rand = new Random(8887);
    BitSet bs = new BitSet(n);
    int[] erasures = new int[n + 1];
    for (int i = 0; i < n;) {
      int v = rand.nextInt(k + m);
      if (!bs.get(v)) {
        LOGGER.info("Erasure-{} is missing", v);
        bs.set(v);
        erasures[i] = v;
        ++i;
      }
    }
    erasures[n] = -1;
    return erasures;
  }

  public int countErased(int[] erasures) {
    int count = 0;
    for (int e : erasures) {
      if (e == -1)
        break;
      else
        ++count;
    }
    return count;
  }

  @Test
  public void testJerasureLibrary() throws Exception {
    ByteBuffer dataSent = ByteBuffer.wrap(genRandomBytes(bufferSize));
    byte[][] dataBlocksSent = ec.encode(dataSent, k, m);
    for (int i = 0; i < k + m; i++) {
      ByteBufferSource dataSource = new ByteBufferSource(ByteBuffer.wrap(dataBlocksSent[i]));
      ByteSource bs = ByteSource.concat(dataSource);
      input[i] = bs.openStream();
    }

    byte[][] dataBlocks = new byte[k][];
    byte[][] codingBlocks = new byte[m][];

    int chunkLen = EcManager.getBlockSize(bufferSize, k);
    for (int i = 0; i < k; i++) {
      dataBlocks[i] = new byte[chunkLen];
      ByteBuffer data = KvsFacade.readValue(input[i], chunkLen);
      dataBlocks[i] = data.array();
    }
    for (int i = k; i < k + m; i++) {
      codingBlocks[i - k] = new byte[chunkLen];
      ByteBuffer data = KvsFacade.readValue(input[i], chunkLen);
      codingBlocks[i - k] = data.array();
    }
    try {
      ByteBuffer buf = ec.decode(dataBlocks, codingBlocks, completeErasures, k, m, bufferSize);
      assertEquals(dataSent, buf);
    } catch (ErasureException e) {
      e.printStackTrace();
      throw new RuntimeException("unknown HybrisException", e);
    }
  }

  /**
   * Test that the original data can be recovered if missing N erasure when N < m, but not when N >
   * m.
   *
   * @param erasures An array of the indices of missed blocks ending with a -1 value.
   * @throws IOException
   */
  public boolean runWithNMissingErasure(int[] erasures) throws IOException {
    ByteBuffer dataSent = ByteBuffer.wrap(genRandomBytes(bufferSize));
    dataSent.mark();
    System.err.println(summarizeBuffer("encode-input", dataSent));
    byte[][] dataBlocksSent = ec.encode(dataSent, k, m);
    for (int i = 0; i < k + m; i++) {
      ByteBufferSource dataSource = new ByteBufferSource(ByteBuffer.wrap(dataBlocksSent[i]));
      ByteSource bs = ByteSource.concat(dataSource);
      input[i] = bs.openStream();
      System.err.println(summarizeBuffer(String.format("encode-output-%02d", i),
          ByteBuffer.wrap(dataBlocksSent[i])));
    }

    byte[][] dataBlocks = new byte[k][];
    byte[][] codingBlocks = new byte[m][];

    int chunkLen = EcManager.getBlockSize(bufferSize, k);
    for (int i = 0; i < k; i++) {
      ByteBuffer data = KvsFacade.readValue(input[i], chunkLen);
      dataBlocks[i] = data.array();
    }
    for (int i = k; i < k + m; i++) {
      ByteBuffer data = KvsFacade.readValue(input[i], chunkLen);
      codingBlocks[i - k] = data.array();
    }

    for (int i = 0; i < erasures.length; ++i) {
      if (erasures[i] == -1) {
        break;
      }
      if (erasures[i] >= k) {
        Arrays.fill(codingBlocks[erasures[i] - k], (byte) 0);
      } else {
        Arrays.fill(dataBlocks[erasures[i]], (byte) 0);
      }
    }

    for (int i = 0; i < k + m; ++i) {
      ByteBuffer buf = ByteBuffer.wrap(i < k ? dataBlocks[i] : codingBlocks[i - k]);
      System.err.println(summarizeBuffer(String.format("decode-input-%02d", i), buf));
    }

    boolean res = false;
    try {
      ByteBuffer buf = ec.decode(dataBlocks, codingBlocks, erasures, k, m, bufferSize);
      System.err.println(summarizeBuffer("decode-output", buf));
      dataSent.reset();
      assertEquals(bufferSize, buf.remaining());
      res = dataSent.equals(buf);
    } catch (ErasureException e) {
      res = false;
    }
    return res;
  }

  @Test
  public void testNoMissingErasure() {
    try {
      int[] e = getErasuresWithNMissing(0);
      assertEquals(0, countErased(e));
      assertTrue(runWithNMissingErasure(e));
    } catch (Throwable t) {
      LOGGER.error("unexpected exception", t);
      fail(String.format("exception: %s", t.getMessage()));
    }
  }

  @Test
  public void testMissingErasure() throws IOException {
    for (int i = 1; i <= m; ++i) {
      int[] e = getErasuresWithNMissing(i);
      // assertEquals(i, countInvalidErasures(e));
      assertTrue(runWithNMissingErasure(e));
    }
  }

  @Test
  public void testOneMissingErasure() throws IOException {
    for (int i = 0; i < 10; ++i) {
      int[] e = getErasuresWithNMissing(1);
      assertEquals(1, countErased(e));
      assertTrue(runWithNMissingErasure(e));
    }
  }

  @Test
  public void testTwoMissingErasure() throws IOException {
    for (int i = 0; i < 10; ++i) {
      int[] e = getErasuresWithNMissing(2);
      assertEquals(2, countErased(e));
      assertTrue(runWithNMissingErasure(e));
    }
  }

  @Test
  public void testThreeMissingErasure() throws IOException {
    for (int i = 0; i < 10; ++i) {
      int[] e = getErasuresWithNMissing(3);
      assertEquals(3, countErased(e));
      assertTrue(runWithNMissingErasure(e));
    }
  }

  @Test
  public void testFourMissingErasure() throws IOException {
    for (int i = 0; i < 10; ++i) {
      int[] e = getErasuresWithNMissing(4);
      assertEquals(4, countErased(e));
      assertFalse(runWithNMissingErasure(e));
    }
  }
}
