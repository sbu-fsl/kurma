/**
 * Copyright (C) 2015-2017 Ming Chen <v.mingchen@gmail.com> Copyright (C) 2013 EURECOM
 * (www.eurecom.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package edu.stonybrook.kurma.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.KurmaException.ErasureException;
import edu.stonybrook.kurma.util.ByteBufferOutputStream;
import edu.stonybrook.kurma.util.ByteBufferSource;
import eu.vandertil.jerasure.jni.Jerasure;
import eu.vandertil.jerasure.jni.ReedSolomon;

/**
 * Class in charge of performing erasure coding tasks.
 *
 * @author P. Viotti
 */
public class EcManager {
  private static final Logger logger = LoggerFactory.getLogger(EcManager.class);

  private static String EC_LIB_NAME = "Jerasure.jni";
  private static int PACKET_SIZE = 8; // 256 B minimum encoded block size
  private static int WORD_SIZE = 8;

  static {
    System.loadLibrary(EC_LIB_NAME);
    logger.debug("Correctly loaded libJerasure.jni");
  }

  public EcManager() {}

  private InputStream addPaddingIfNeeded(ByteBuffer data, int blockSize) {
    ByteSource bs = new ByteBufferSource(data);
    if (data.remaining() < blockSize) {
      ByteSource pad = new ByteBufferSource(ByteBuffer.allocate(blockSize - data.remaining()));
      bs = ByteSource.concat(bs, pad);
    }
    InputStream input = null;
    try {
      input = bs.openStream();
    } catch (IOException e) {
      throw new RuntimeException("unexpected IOException in addPaddingIfNeeded()", e);
    }
    return input;
  }

  public static int getPaddedSize(int originalSize, int k) {
    int newSize = originalSize;
    if (originalSize % (k * WORD_SIZE * PACKET_SIZE * 4) != 0)
      while (newSize % (k * WORD_SIZE * PACKET_SIZE * 4) != 0)
        newSize++;
    return newSize;
  }

  public static int getBlockSize(int originalSize, int k) {
    return getPaddedSize(originalSize, k) / k;
  }

  public byte[][] encode(ByteBuffer data, int k, int m) {
    int paddedSize = getPaddedSize(data.remaining(), k);
    InputStream is = addPaddingIfNeeded(data, paddedSize);
    int blockSize = paddedSize / k;

    byte[][] dataBlocks = new byte[k][blockSize];
    try {
      for (int i = 0; i < k; i++) {
        dataBlocks[i] = new byte[blockSize];
        ByteStreams.read(is, dataBlocks[i], 0, blockSize);
      }
    } catch (IOException e) {
      throw new RuntimeException("unexpected IOException in encode()", e);
    }

    byte[][] codingBlocks = new byte[m][blockSize];
    for (int i = 0; i < m; i++)
      codingBlocks[i] = new byte[blockSize];

    int[] matrix = ReedSolomon.reed_sol_vandermonde_coding_matrix(k, m, WORD_SIZE);
    Jerasure.jerasure_matrix_encode(k, m, WORD_SIZE, matrix, dataBlocks, codingBlocks, blockSize);

    byte[][] dataAndCoding = new byte[k + m][blockSize];
    System.arraycopy(dataBlocks, 0, dataAndCoding, 0, k);
    System.arraycopy(codingBlocks, 0, dataAndCoding, k, m);

    return dataAndCoding;
  }

  public ByteBuffer decode(byte[][] dataBlocks, byte[][] codingBlocks, int[] erasures, int k, int m,
      int originalSize) throws ErasureException {
    int paddedSize = getPaddedSize(originalSize, k);
    int blockSize = paddedSize / k;

    int[] matrix = ReedSolomon.reed_sol_vandermonde_coding_matrix(k, m, WORD_SIZE);
    boolean res = Jerasure.jerasure_matrix_decode(k, m, WORD_SIZE, matrix, true, erasures,
        dataBlocks, codingBlocks, blockSize);

    if (!res) {
      logger.error("Error while decoding");
      throw new ErasureException("Error while decoding");
    }

    try (ByteBufferOutputStream bos = new ByteBufferOutputStream(originalSize)) {

      int total = 0;
      for (int i = 0; i < k; i++) {
        bos.write(dataBlocks[i]);
        total += blockSize;
      }
      assert (total == paddedSize);

      return bos.getByteBuffer(0, originalSize);
    } catch (Exception e) {
      logger.error("I/O Error while writing decoded data to byte array");
      throw new ErasureException("I/O Error while writing decoded data to byte array");
    }
  }
}
