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
package edu.stonybrook.kurma.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.Repeat;
import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.FileHandler.SnapshotInfo;


public class FileHandlerTest extends TestBase {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileHandlerTest.class);
  public static long inputSize = 0;
  public static long outputSize = 0;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  private FileHandler createFileWith64KBlockSize(String name) {
    FileHandler fh = createFileUnderRoot(name);
    fh.setBlockShift(16);
    assertEquals(16, fh.getBlockShift());
    return fh;
  }

  @Test
  public void testCreateFileWithLargeOid() throws Exception {
    ObjectID oid = vh.newFileOid();
    oid.getId().setId1(oid.getId().getId1() + 0x100000);
    FileHandler fh = rootDh.createChildFile(oid, "testCreateFileWithLargeOid",
        AttributesHelper.newFileAttributes(), config.getDefaultKvsFacade(), null, null, true);
    zkClient.flush();
    assertNotNull(fh);
  }

  @Test
  public void testFileCreation() throws Exception {
    FileHandler fh = createFileUnderRoot("testFileCreation");

    assertTrue(zkClient.checkExists(fh.getZpath()));
    assertEquals(fh.getFileSize(), 0);
    assertFalse(fh.isBlockSet());
    assertEquals(1, fh.getNlinks());

    zkClient.flush();
    assertNotNull(client.checkExists().forPath(fh.getZpath()));

    // block size is not set until it is written for the first time
    fh.truncate(64 * 1024);
    assertEquals(fh.getBlockShift(), FileHandler.MIN_BLOCK_SHIFT);

    fh.truncate(1024 * 1024);
    assertEquals(fh.getBlockShift(), FileHandler.MIN_BLOCK_SHIFT);
  }

  @Test
  public void benchFileCreation() throws Exception {
    long start = System.nanoTime();
    int  nfiles = 10000;
    inputSize = 0;
    outputSize = 0;
    for (int i = 0; i < nfiles; ++i) {
      createFileUnderRoot("benchFileCreation-" + i);
    }
    long end = System.nanoTime();
    System.out.printf("Number of files created: %d\n", nfiles);
    System.out.printf("average time: %.2f ms\n", (end - start) / nfiles / 1000000.0);
    System.out.printf("Input metadata size: %d bytes\n", inputSize);
    System.out.printf("Output metadata size: %d bytes\n", outputSize);
    System.out.printf("Compression Ratio: %.2f\n", (inputSize * 1.0) / outputSize);
  }

  @Test
  public void benchConcurrentFileCreation() throws Exception {
    long start = System.nanoTime();
    final int N = 16;
    ObjectID[] dirIds = new ObjectID[N];
    for (int nth = 0; nth < N; ++nth) {
      DirectoryHandler dh =
          rootDh.createChildDirectory("dir-" + nth, AttributesHelper.newDirAttributes());
      dirIds[nth] = dh.get().getOid();
    }
    doParallel(N, nth -> {
      for (int i = 0; i < 1000; ++i) {
        FileHandler fh = new FileHandler(vh.newFileOid(), vh);
        fh.create(dirIds[nth], "benchConcurrentFileCreation-" + nth + "-" + i,
            AttributesHelper.newDirAttributes());
      }
    });
    long end = System.nanoTime();
    System.out.printf("throughput: %.2f files/s\n", N * 100.0 / ((end - start) / 1000000000.0));
  }

  @Test
  public void testGetBlocks() throws Exception {
    FileHandler fh = createFileUnderRoot("testGetBlocks");
    final int BS = 16;
    fh.setBlockShift(BS);

    // test the case when the file size is block-aligned
    fh.truncate(3 << BS);
    ByteBuffer buf = ByteBuffer.allocate(2 << BS);
    buf.mark();

    // get read blocks
    List<FileBlock> blocks = fh._breakBufferIntoBlocks(0, 2 << BS, 2 << BS, buf, false);
    assertEquals(2, blocks.size());
    assertEquals(0, blocks.get(0).getVersion());
    assertEquals(0, blocks.get(1).getVersion());

    // the buffers should be backed by the same array
    assertTrue(blocks.get(0).getValue().array() == buf.array());
    assertTrue(blocks.get(1).getValue().array() == buf.array());

    // the generated key should match
    for (FileBlock fb : blocks) {
      assertTrue(Arrays.equals(fb.getKey(),
          fh.getBlockKeyGenerator().getBlockKey(fb.getOffset(), fb.getVersion(), fb.getGateway())));
    }

    // get write blocks
    buf.reset();
    blocks = fh._breakBufferIntoBlocks(1 << BS, 2 << BS, 3 << BS, buf, true);
    assertEquals(2, blocks.size());

    // the buffers should be backed by the same array
    assertTrue(blocks.get(0).getValue().array() == buf.array());
    assertTrue(blocks.get(1).getValue().array() == buf.array());

    // write increment the version by 1
    assertEquals(1, blocks.get(0).getVersion());
    assertEquals(1, blocks.get(1).getVersion());
  }

  @Test
  public void testGetBlocksAtEnd() throws Exception {
    FileHandler fh = createFileUnderRoot("testGetBlocksAtEnd");
    final int BS = 16;
    fh.setBlockShift(BS);

    // set to a non-aligned size
    int length = (3 << BS) + 2;
    fh.truncate(length);
    ByteBuffer buf = ByteBuffer.allocate(length);
    buf.mark();

    List<FileBlock> blocks = fh._breakBufferIntoBlocks(0, length, length, buf, false);
    assertEquals(4, blocks.size());
    assertEquals(0, blocks.get(0).getVersion());
    assertEquals(0, blocks.get(3).getVersion());
    assertEquals(2, blocks.get(3).getLength());

    // un-aligned operation that is not at the end will fail
    exception.expect(IllegalArgumentException.class);
    blocks = fh._breakBufferIntoBlocks(0, length - 1, length, buf, false);
  }

  @Test
  public void testBasicRW() throws Exception {
    FileHandler fh = createFileUnderRoot("testBasicRW");

    int length = 64 * 1024;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(length));
    assertFalse(fh.isBlockSet());
    assertTrue(fh.truncate(length));
    assertTrue(fh.write(0, data.duplicate()));
    // block size is set upon the first write
    assertTrue(fh.isBlockSet());
    assertEquals(fh.getBlockSize(), length);
    assertEquals(1, fh.getBlockVersion(0).getKey().intValue());
    assertEquals(config.getGatewayId(), fh.getBlockVersion(0).getValue().shortValue());

    fh.write(0, data);
    assertEquals(2, fh.getBlockVersion(0).getKey().intValue());

    ByteBuffer readData = fh.read(0, length).getKey();
    assertNotNull(readData);
    assertTrue(readData.hasArray());
    assertEquals(data, readData);
  }

  @Test
  public void testReadUntilEof() throws Exception {
    FileHandler fh = createFileUnderRoot("testReadUntilEof");
    final int S = 64 * 1024;
    assertTrue(fh.write(0, ByteBuffer.wrap(genRandomBytes(S))));
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(2 * S));
    assertTrue(fh.write(0, data.duplicate()));

    AbstractMap.SimpleEntry<ByteBuffer, ObjectAttributes> res = fh.read(0, S);
    boolean eof = (0 + S) >= res.getValue().getFilesize();
    assertFalse(eof);

    res = fh.read(S, S);
    eof = S + S >= res.getValue().getFilesize();
    assertTrue(eof);

    res = fh.read(0, 2 * S);
    eof = (0 + 2 * S) >= res.getValue().getFilesize();
    assertTrue(eof);
    assertEquals(data, res.getKey());
  }

  @Test
  @Repeat(times = 100)
  public void testRWMultipleBlocks() throws Exception {
    FileHandler fh = createFileUnderRoot("testRWMultipleBlocks");
    final int BS = 16;
    fh.setBlockShift(BS);
    assertEquals(fh.getBlockSize(), (1 << BS));

    // write 2 blocks
    int length = 2 << BS;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(length));
    data.mark();

    assertEquals(0, fh.get().attrs.nblocks);
    assertTrue(fh.truncate(length));
    assertEquals(0, fh.get().attrs.nblocks);
    assertTrue(fh.write(0, data));
    assertEquals(2, fh.get().attrs.nblocks);

    // read 2 blocks
    ByteBuffer readData = fh.read(0, length).getKey();
    assertNotNull(readData);
    assertTrue(Arrays.equals(data.array(), readData.array()));

    // test overwrite
    data.reset();
    assertTrue(fh.write(0, data));
    assertEquals(2, fh.getBlockVersion(0).getKey().intValue());
    assertEquals(2, fh.getBlockVersion(1).getKey().intValue());
  }

  @Test
  public void testUnalignedRW() throws Exception {
    FileHandler fh = createFileUnderRoot("testUnalignedRW");
    final int BS = 16;
    fh.setBlockShift(BS);

    int length = (2 << BS) + 1;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(length));

    assertTrue(fh.truncate(length));
    assertTrue(fh.write(0, data));
    assertEquals(length, fh.getDataSize());

    ByteBuffer read1 = fh.read(0, (1 << BS)).getKey();
    ByteBuffer read2 = fh.read((1 << BS), length - (1 << BS)).getKey();
    assertNotNull(read1);
    assertNotNull(read2);
    assertEquals(1 << BS, read1.remaining());
    assertEquals(length - (1 << BS), read2.remaining());

    ByteBuffer readData = ByteBuffer.allocate(length).put(read1).put(read2);
    assertTrue(Arrays.equals(data.array(), readData.array()));
  }

  @Test
  public void testShortenFile() throws Exception {
    FileHandler fh = createFileUnderRoot("testShortenFile");
    final int BS = 16;
    fh.setBlockShift(BS);

    int length = (2 << BS) + 1;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(length));
    assertTrue(fh.truncate(length));
    assertTrue(fh.write(0, data));
    assertEquals(fh.getAttrsCopy().getNblocks(), 3);

    for (int nblocks = 2; nblocks >= 0; --nblocks) {
      length = nblocks << BS;
      assertTrue(fh.truncate(length));
      ObjectAttributes attrs = fh.getAttrsCopy();
      assertEquals(length, attrs.getFilesize());
      assertEquals(nblocks, attrs.getNblocks());
    }
  }

  @Test
  public void testSetBlockSize() throws Exception {
    FileHandler fh = createFileUnderRoot("testSetBlockSize1");
    final int min = 1 << FileHandler.MIN_BLOCK_SHIFT;
    final int max = 1 << FileHandler.MAX_BLOCK_SHIFT;
    // round up
    fh.setBlockSizeIfNeeded(min - 1);
    assertEquals(min, fh.getBlockSize());
    // Should not change the block size if it is already set.
    fh.setBlockSizeIfNeeded(min + 1);
    assertEquals(min, fh.getBlockSize());

    fh = createFileUnderRoot("testSetBlockSize2");
    fh.setBlockSizeIfNeeded(max + 1);
    assertEquals(max, fh.getBlockSize());
    // Should not change the block size if it is already set.
    fh.setBlockSizeIfNeeded(max + 1);
    assertEquals(max, fh.getBlockSize());

    fh = createFileUnderRoot("testSetBlockSize3");
    fh.setBlockSizeIfNeeded(min * 2);
    assertEquals(min * 2, fh.getBlockSize());
  }

  @Test
  public void testConfiguredBlockSize() throws Exception {
    IGatewayConfig myconfig = Mockito.spy(vh.getConfig());
    final int myBlockShift = 16;
    Mockito.doReturn(myBlockShift).when(myconfig).getBlockShift();
    VolumeHandler vh2 = new VolumeHandler(vh.getVolumeInfo(), vh.getZpath(),
        vh.getZkClient().getCuratorClient(),
        vh.getGarbageCollector(), myconfig, new BlockExecutor());
    FileHandler fh = new FileHandler(vh.newFileOid(), vh2);
    fh.create(rootDh.getOid(), "testConfiguredBlockSize", AttributesHelper.newFileAttributes());
    assertEquals(myBlockShift, fh.getBlockShift());
    assertEquals(myBlockShift, fh.getAttrsCopy().getBlock_shift());
    assertEquals((1 << myBlockShift), fh.getBlockSize());

    fh.write(0, ByteBuffer.wrap(genRandomBytes(2 << myBlockShift)));
    assertEquals(myBlockShift, fh.getBlockShift());
    assertEquals((1 << myBlockShift), fh.getBlockSize());
  }

  @Test
  public void testWriteCanAppendFile() throws Exception {
    FileHandler fh = createFileUnderRoot("testWriteCanAppendFile1");
    final int LEN = 64 * 1024;

    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(LEN));
    data.mark();
    assertTrue(fh.write(0, data));
    assertEquals(LEN, fh.getFileSize());

    data.reset();
    assertTrue(fh.write(LEN, data));
    assertEquals(LEN * 2, fh.getFileSize());
  }

  @Test
  public void testOutOfOrderWrites() throws Exception {
    FileHandler fh = createFileUnderRoot("testOutOfOrderWrites");
    final int BS = 64 * 1024;
    byte[] data = genRandomBytes(2 * BS);
    assertTrue(fh.write(BS, ByteBuffer.wrap(data, BS, BS)));
    assertTrue(fh.write(0, ByteBuffer.wrap(data, 0, BS)));
    assertEquals(2 * BS, fh.getFileSize());
    ByteBuffer res = fh.read(0, 2 * BS).getKey();
    assertEquals(res, ByteBuffer.wrap(data));
  }

  @Test
  public void testWritingFileConcurrently() throws Exception {
    FileHandler fh = createFileUnderRoot("testLoadTheSameBlockMapConcurrently");
    doParallel(10, i -> fh.write(i * 64 * 1024, ByteBuffer.wrap(genRandomBytes(64 * 1024))));
    for (int i = 0; i < 10; ++i) {
      assertEquals(Long.valueOf(1), fh.getBlockVersion(i * 64 * 1024).getKey());
    }
  }

  // ====== testing of aligned truncates ======

  @Test
  public void testShrinkAndReadBeyond() throws Exception {
    FileHandler fh = createFileUnderRoot("testShrinkAndReadBeyond");
    // set 64K block size
    final int BS = 64 * 1024;
    assertTrue(fh.write(0, ByteBuffer.wrap(genRandomBytes(BS))));
    // Enlarge to 11 blocks
    byte[] data = genRandomBytes(10 * BS);
    assertTrue(fh.write(BS, ByteBuffer.wrap(data)));
    ByteBuffer buf1 = fh.read(10 * BS, BS).getKey();
    assertTrue(buf1.equals(ByteBuffer.wrap(data, 9 * BS, BS)));

    // truncate to 10 blocks
    assertTrue(fh.truncate(10 * BS));
    ByteBuffer buf2 = fh.read(10 * BS, BS).getKey();
    assertEquals(0, buf2.remaining());

    // read from the 9-th block
    ByteBuffer buf3 = fh.read(9 * BS, BS).getKey();
    assertEquals(buf3.remaining(), BS);
    assertTrue(buf3.equals(ByteBuffer.wrap(data, 8 * BS, BS)));
  }

  public static boolean areZeros(ByteBuffer buf) {
    while (buf.hasRemaining()) {
      if (buf.get() != 0) {
        return false;
      }
    }
    return true;
  }

  @Test
  public void testShrinkAndThenEnlarge() throws Exception {
    FileHandler fh = createFileUnderRoot("testShrinkAndThenEnlarge");
    final int BS = 64 * 1024;
    // set 64K block size
    assertTrue(fh.write(0, ByteBuffer.wrap(genRandomBytes(BS))));

    // write 2 more blocks
    byte[] data = genRandomBytes(2 * BS);
    assertTrue(fh.write(BS, ByteBuffer.wrap(data)));

    // shrink and then enlarge
    assertTrue(fh.truncate(2 * BS));
    assertEquals(2 * BS, fh.getDataSize());
    assertTrue(fh.truncate(3 * BS));
    assertEquals(2 * BS, fh.getDataSize());
    assertEquals(3 * BS, fh.getFileSize());

    ByteBuffer buf = fh.read(BS, 2 * BS).getKey();
    assertEquals(2 * BS, buf.remaining());
    byte[] data2 = buf.array();
    assertTrue(ByteBuffer.wrap(data, 0, BS).equals(ByteBuffer.wrap(data2, 0, BS)));
    assertFalse(ByteBuffer.wrap(data, BS, BS).equals(ByteBuffer.wrap(data2, BS, BS)));

    assertTrue(areZeros(ByteBuffer.wrap(data2, BS, BS)));
  }

  @Test
  public void testEnlargeAndThenShrink() throws Exception {
    FileHandler fh = createFileUnderRoot("testEnlargeAndThenShrink");
    final int BS = 64 * 1024;
    // set 64K block size
    byte[] data = genRandomBytes(BS);
    assertTrue(fh.write(0, ByteBuffer.wrap(data)));

    assertTrue(fh.truncate(3 * BS));
    assertEquals(3 * BS, fh.getFileSize());
    assertEquals(BS, fh.getDataSize());

    assertTrue(fh.truncate(BS));
    assertEquals(BS, fh.getFileSize());
    assertEquals(BS, fh.getDataSize());
    ByteBuffer buf = fh.read(0, BS).getKey();

    assertTrue(Arrays.equals(buf.array(), data));
  }

  @Test
  public void testTruncateUpdateTimestamps() throws Exception {
    FileHandler fh = createFileUnderRoot("testTruncateUpdateTimestamps");
    fh.write(0, genRandomBuffer(65536));
    ObjectAttributes oldAttrs = fh.getAttrsCopy();

    Thread.sleep(10);
    assertFalse(fh.truncate(oldAttrs.getFilesize()));
    ObjectAttributes newAttrs = fh.getAttrsCopy();

    assertEquals(oldAttrs.getFilesize(), newAttrs.getFilesize());
    assertEquals(newAttrs.getChange_time(), newAttrs.getModify_time());
    assertTrue(oldAttrs.getModify_time() < newAttrs.getModify_time());
    assertTrue(oldAttrs.getChange_time() < newAttrs.getChange_time());
  }

  @Test
  public void testTruncateAndWriteConcurrently() throws Exception {
    // set 64K block size
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testTruncateAndWriteConcurrently");
    byte[] data = genRandomBytes(10 * BS);
    assertTrue(fh.write(0, ByteBuffer.wrap(data)));
    doParallel(10, i -> {
      if (i == 10) {
        for (int n = 11; n < 30; ++n) {
          assertTrue(fh.truncate(n * BS));
        }
      } else {
        fh.write(i * BS, ByteBuffer.wrap(data, i * BS, BS));
      }
    });
    ByteBuffer read = fh.read(0, 10 * BS).getKey();
    assertTrue(Arrays.equals(data, read.array()));
    ByteBuffer zeros = fh.read(10 * BS, BS).getKey();
    assertTrue(areZeros(zeros));
  }

  @Test
  public void testReadBeyondFileSize() throws Exception {
    // read beyond file size should return an empty buffer
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testReadBeyondFileSize");
    ByteBuffer buf = fh.read(0, BS).getKey();
    assertFalse(buf.hasRemaining());

    assertTrue(fh.truncate(BS));
    buf = fh.read(2 * BS, BS).getKey();
    assertFalse(buf.hasRemaining());
  }

  @Test
  public void testReadBlocksCreatedByTruncateShouldReturnZeros() throws Exception {
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testReadHoleCreatedByTruncateShouldReturnZeros");
    byte[] data = genRandomBytes(BS);
    assertTrue(fh.write(0, ByteBuffer.wrap(data)));

    // enlarge to 2 block and thus create a new block which is a hole
    assertTrue(fh.truncate(2 * BS));

    ByteBuffer buf = fh.read(BS, BS).getKey();
    assertTrue(areZeros(buf));

    if (config.isFileHoleSupported()) {
      // Now we fill the 2nd block and then first shrink the file size to
      // one
      // block and second truncate the file size back to two blocks. The
      // 2nd
      // block should still be a hole and return all zeros.
      assertTrue(fh.write(BS, ByteBuffer.wrap(data)));
      assertTrue(fh.truncate(BS));
      assertTrue(fh.truncate(2 * BS));
      buf = fh.read(BS, BS).getKey();
      assertTrue(areZeros(buf));

      // Now we append a third block and then shrink the file to one block and
      // truncate the file size back to three blocks. We overwrite the 3rd block
      // and read the 2nd block, which should still be a hole and contains all
      // zeros.
      assertTrue(fh.write(2 * BS, ByteBuffer.wrap(data)));
      assertEquals(3 * BS, fh.getFileSize());
      assertTrue(fh.truncate(BS));
      assertTrue(fh.truncate(3 * BS));
      assertTrue(fh.write(2 * BS, ByteBuffer.wrap(data)));
      buf = fh.read(BS, BS).getKey();
      assertTrue(areZeros(buf));
    }
  }

  /**
   * When writing beyond a unaligned file hole, we might need to fill zeros into the block of the
   * unaligned hole. This tests ensures we hold proper locks to protect the hole.
   *
   * @throws Exception
   */
  @Test
  public void testConcurrentHoleFillingAndReading() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testConcurrentHoleFillingAndReading");
    assertTrue(fh.write(0, genRandomBuffer(BS + 1000)));
    assertTrue(fh.truncate(3 * BS)); // now the 2nd block belongs to a
                                     // unaligned hole
    doParallel(2, i -> {
      if (i == 0) {
        // If this thread get scheduled before the other thread, this
        // operation
        // requires us to fill the partial block of the unaligned hole.
        fh.write(2 * BS, genRandomBuffer(BS));
      } else {
        fh.write(BS, genRandomBuffer(BS));
      }
    });
    Entry<ByteBuffer, ObjectAttributes> res = fh.read(BS, BS);
    assertNotNull(res);
    assertNotNull(res.getKey());
  }

  @Test
  public void testReadFileHoles() throws Exception {
    FileHandler fh = createFileWith64KBlockSize("testReadFileHoles");
    assertTrue(fh.truncate(65536));
    Entry<ByteBuffer, ObjectAttributes> entries = fh.read(0, 65536);
    assertNotNull(entries);
    assertNotNull(entries.getKey());
    assertEquals(65536, entries.getKey().remaining());

    assertTrue(fh.write(65536, genRandomBuffer(65536)));
    entries = fh.read(0, 65536);
    assertNotNull(entries);
    assertNotNull(entries.getKey());
    assertEquals(65536, entries.getKey().remaining());
  }

  @Test
  public void testFileHolesAreAllZeros() throws Exception {
    FileHandler fh = createFileWith64KBlockSize("testFileHolesAreAllZeros");
    final int N = 100;
    assertTrue(fh.truncate(65536 * N));
    byte[] data = new byte[65536];
    for (int i = 0; i < N; ++i) {
      Entry<ByteBuffer, ObjectAttributes> entries = fh.read(65536 * i, 65536);
      assertNotNull(entries);
      assertEquals(entries.getKey(), ByteBuffer.wrap(data));
    }
  }

  @Test
  public void testShrinkEnlargeThenReadHole() throws Exception {
    FileHandler fh = createFileWith64KBlockSize("testShrinkEnlargeThenReadHole");
    assertTrue(fh.truncate(65536));
    assertTrue(fh.truncate(0));
    assertTrue(fh.truncate(65536 * 2));
    assertTrue(fh.write(65536, genRandomBuffer(65536 * 2)));
    assertEquals(65536 * 3, fh.getFileSize());
    Entry<ByteBuffer, ObjectAttributes> entries = fh.read(0, 65536);
    assertNotNull(entries);
    assertNotNull(entries.getKey());
    assertEquals(65536, entries.getKey().remaining());
  }

  @Test
  public void testTruncateUnaligedTail() throws Exception {
    FileHandler fh = createFileUnderRoot("testTruncateUnaligedTail");
    byte[] buf1 = genRandomBytes(2000);
    assertTrue(fh.write(0, ByteBuffer.wrap(buf1)));
    assertEquals(65536, fh.getBlockSize());

    assertTrue(fh.truncate(1000));
    assertTrue(fh.truncate(2000));

    assertTrue(fh.truncate(65536 * 2));
    byte[] buf2 = genRandomBytes(65536);
    assertTrue(fh.write(65536, ByteBuffer.wrap(buf2)));

    ByteBuffer res = fh.read(0, 65536).getKey();
    assertEquals(ByteBuffer.wrap(res.array(), 0, 1000), ByteBuffer.wrap(buf1, 0, 1000));
    assertFalse(ByteBuffer.wrap(res.array(), 0, 2000).equals(ByteBuffer.wrap(buf1)));
    // The rest of "res" should be all zeros.
    assertEquals(ByteBuffer.wrap(res.array(), 1000, res.remaining() - 1000),
        ByteBuffer.wrap(new byte[res.remaining() - 1000]));
  }

  @Test
  public void testTruncatesRW() throws Exception {
    FileHandler fh = createFileUnderRoot("testTruncatesRW");
    assertFalse(fh.truncate(0));

    // range [0, 131072) are all zeros caused by hole
    assertTrue(fh.truncate(182014));
    assertEquals(182014, fh.read(0, 196608).getKey().remaining());
    assertEquals(65536, fh.read(0, 65536).getKey().remaining());
    assertEquals(65536, fh.read(65536, 65536).getKey().remaining());
    // range [0, 131072) are bytes from "buf1"
    byte[] buf1 = genRandomBytes(131072);
    assertTrue(fh.write(0, ByteBuffer.wrap(buf1)));

    assertTrue(fh.truncate(246505));
    assertEquals(246505, fh.read(0, 262144).getKey().remaining());
    assertEquals(49897, fh.read(196608, 65536).getKey().remaining());
    byte[] buf2 = genRandomBytes(49897);
    assertTrue(fh.write(196608, ByteBuffer.wrap(buf2)));

    assertTrue(fh.truncate(250706));
    assertEquals(250706, fh.read(0, 262144).getKey().remaining());
    assertEquals(54098, fh.read(196608, 65536).getKey().remaining());
    byte[] buf3 = genRandomBytes(54098);
    assertTrue(fh.write(196608, ByteBuffer.wrap(buf3)));

    // range [0, 131072) contains bytes (i.e., the [0, 39908) range) from
    // "buf1" and zeros
    assertTrue(fh.truncate(39908));
    assertEquals(39908, fh.read(0, 65536).getKey().remaining());

    assertTrue(fh.truncate(196746));
    assertEquals(196746, fh.read(0, 262144).getKey().remaining());
    assertEquals(65536, fh.read(131072, 65536).getKey().remaining());
    assertEquals(138, fh.read(196608, 65536).getKey().remaining());
    byte[] buf4 = genRandomBytes(65674);
    assertTrue(fh.write(131072, ByteBuffer.wrap(buf4)));

    assertTrue(fh.truncate(170348));
    ByteBuffer res = fh.read(0, 131072).getKey();
    assertEquals(131072, res.remaining());
    byte[] zeros = new byte[131072 - 39908];
    assertEquals(ByteBuffer.wrap(buf1, 0, 39908), ByteBuffer.wrap(res.array(), 0, 39908));
    ByteBuffer truncatedTail = ByteBuffer.wrap(res.array(), 39908, 131072 - 39908);
    assertFalse(ByteBuffer.wrap(buf1, 39908, truncatedTail.remaining()).equals(truncatedTail));
    assertEquals(ByteBuffer.wrap(zeros), truncatedTail);
  }

  @Test
  public void testTruncateAndThenAppend() throws Exception {
    FileHandler fh = createFileUnderRoot("testTruncateAndThenAppend");
    final int BS = 1024 * 1024;
    assertTrue(fh.write(0, genRandomBuffer(BS)));
    assertEquals(BS, fh.getBlockSize());
    assertTrue(fh.truncate(0));
    assertEquals(0L, fh.getFileSize());
    List<Long> block_versions = new ArrayList<>();
    byte[] data = genRandomBytes(BS);
    assertTrue(fh.write(0, ByteBuffer.wrap(data), Optional.of(block_versions)));
    long new_version = block_versions.get(0);
    // The block version should be incremented from 1 to 2, instead of incrementing from 0 again.
    assertEquals(2L, new_version);
    ByteBuffer dataRead = fh.read(0, BS).getKey();
    assertEquals(dataRead, ByteBuffer.wrap(data));
  }

  @Test
  public void testXfstest075() throws Exception {
    FileHandler fh = createFileUnderRoot("testXfstest075");
    assertFalse(fh.truncate(0));
    assertTrue(fh.truncate(100000));
    assertTrue(fh.truncate(0));
    assertTrue(fh.truncate(248738));
    Entry<ByteBuffer, ObjectAttributes> entries = fh.read(0, 248738);
    assertEquals(248738, entries.getKey().remaining());

    assertEquals(65536, fh.read(131072, 65536).getKey().remaining());
    // assertEquals(65536, fh.read(196608, 65536).getKey().remaining());
    assertTrue(fh.write(131072, genRandomBuffer(117666)));

    List<Long> versions = new ArrayList<>();
    assertTrue(fh.write(0, genRandomBuffer(65536), Optional.of(versions)));
    assertEquals(1, versions.size());
    /**
     * Since the first block is a whole, the new version of this initial write to should result a
     * version number of 1.
     */
    assertEquals(1, versions.get(0).intValue());
    assertEquals(65536, fh.read(0, 65536).getKey().remaining());
  }

  // ====== testing of unaligned truncates ======

  @Test
  public void testBasicUnalignedTruncates() throws Exception {
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testUnalignedEnlarge");

    // enlarge the file size to "BS + 20"
    assertTrue(fh.truncate(BS + 20));
    ByteBuffer read = fh.read(0, 2 * BS).getKey();
    assertEquals(BS + 20, read.remaining());
    assertTrue(areZeros(read));

    byte[] data = genRandomBytes(BS + 20);
    assertTrue(fh.write(0, ByteBuffer.wrap(data)));

    // enlarge the file size to "BS + 30"
    assertTrue(fh.truncate(BS + 30));
    assertEquals(BS + 30, fh.getFileSize());
    ByteBuffer buf = fh.read(0, 2 * BS).getKey();
    assertEquals(BS + 30, buf.remaining());
    assertTrue(ByteBuffer.wrap(buf.array(), 0, BS + 20).equals(ByteBuffer.wrap(data)));
    assertTrue(areZeros(ByteBuffer.wrap(buf.array(), BS + 20, 10)));

    // shrink the file size to "BS + 10"
    assertTrue(fh.truncate(BS + 10));
    assertEquals(BS + 10, fh.getFileSize());
    buf = fh.read(0, 2 * BS).getKey();
    assertEquals(BS + 10, buf.remaining());
    assertEquals(buf, ByteBuffer.wrap(data, 0, BS + 10));
  }

  @Test
  public void testUnalignedWriteToFileEnlargedByTruncate() throws Exception {
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testUnalignedWriteToFileEnlargedByTruncate");
    ByteBuffer data = genRandomBuffer(BS - 10);
    data.mark();
    assertTrue(fh.write(0, data));
    assertEquals(BS - 10, fh.getFileSize());

    // enlarge file to 2 blocks and thus create a new block which is a hole
    assertTrue(fh.truncate(2 * BS));

    ByteBuffer buf = fh.read(0, BS).getKey();
    data.reset();
    assertEquals(buf.remaining(), BS);
    assertEquals(data, ByteBuffer.wrap(buf.array(), 0, BS - 10));
    assertTrue(areZeros(ByteBuffer.wrap(buf.array(), buf.arrayOffset() + BS - 10, 10)));

    // test filling an enlarged file with a gap (hole at [BS - 10, BS))
    ByteBuffer secondBlock = genRandomBuffer(BS);
    secondBlock.mark();
    assertTrue(fh.write(BS, secondBlock));
    secondBlock.reset();

    // Write to the newly created block should override the hole at [BS -
    // 10, BS).
    ByteBuffer firstBlock = genRandomBuffer(BS);
    firstBlock.mark();
    assertTrue(fh.write(0, firstBlock));
    firstBlock.reset();

    buf = fh.read(0, 2 * BS).getKey();
    assertEquals(firstBlock, ByteBuffer.wrap(buf.array(), buf.arrayOffset(), BS));
    assertEquals(secondBlock, ByteBuffer.wrap(buf.array(), buf.arrayOffset() + BS, BS));
  }

  /**
   * The unaligned tail of a file might be a hole if the tail was created by "truncate". When
   * writing beyond the hole-tail, we should not fill zeros into the tail or increase its version
   * number.
   *
   * @throws Exception
   */
  @Test
  public void testFillBeyondUnalignedTailThatWasHole() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testFillBeyondUnalignedTailThatWasHole");
    // create a hole at block-1
    assertTrue(fh.write(0, genRandomBuffer(BS)));
    assertTrue(fh.truncate(3 * BS));
    assertTrue(fh.write(2 * BS, genRandomBuffer(BS)));

    // create a file tail that is a hole (i.e., hole-tail)
    assertTrue(fh.truncate(BS + 1000));

    assertTrue(fh.truncate(3 * BS));
    assertTrue(fh.write(2 * BS, genRandomBuffer(BS)));
    Entry<ByteBuffer, ObjectAttributes> p = fh.read(BS, BS);
    assertNotNull(p);
    assertEquals(ByteBuffer.wrap(new byte[BS]), p.getKey()); // should be
                                                             // all zeros
  }

  @Test
  public void testShrinkFileEnlargedByTruncate() throws Exception {
    // test enlarge a file size, and then shrink the file size immediately,
    // check non-filled block maps are not GCed.
    final int BS = 64 * 1024;
    FileHandler fh = createFileWith64KBlockSize("testShrinkFileEnlargedByTruncate");
    assertTrue(fh.write(0, genRandomBuffer(BS)));

    assertTrue(fh.truncate(2 * BS));
    assertTrue(fh.truncate(0));

    FileBlock fb1 = new FileBlock(fh, 0, BS, 1, config.getGatewayId());
    assertTrue(dummyGarbageCollector.getBlockCollector().hasCollected(fb1));

    FileBlock fb2 = new FileBlock(fh, BS, BS, 0, config.getGatewayId());
    assertFalse(dummyGarbageCollector.getBlockCollector().hasCollected(fb2));
  }

  @Test
  public void testTruncateAndRead() throws Exception {
    FileHandler fh = createFileWith64KBlockSize("testTruncateAndRead");
    for (int i = 0; i < 10; ++i) {
      fh.truncate(65536 * i);
      Entry<ByteBuffer, ObjectAttributes> p = fh.read(0, 65536 * i);
      assertNotNull(p);
      assertEquals(p.getKey().remaining(), 65536 * i);
    }
  }

  @Test
  public void testConcurrentTruncateRead() throws Exception {
    FileHandler fh = createFileWith64KBlockSize("testConcurrentTruncateRead");
    assertParallel(10, i -> {
      boolean res = true;
      try {
        if (i < 5) {
          fh.truncate(i * 65536);
        } else {
          Entry<ByteBuffer, ObjectAttributes> p = fh.read(0, (i - 5) * 65536);
          if (p == null) {
            LOGGER.error("Thread-{} failed to read", i);
            res = false;
            return false;
          }
        }
      } catch (Throwable t) {
        LOGGER.error("failed to truncate and read file at the same time", t);
        res = false;
      }
      return res;
    });
  }

  @Test
  public void testConcurrentTruncateAndRW() throws Exception {
    FileHandler fh = createFileWith64KBlockSize("testConcurrentTruncateAndRW");
    assertParallel(20, i -> {
      boolean res = true;
      try {
        if (i < 10) {
          fh.truncate(i * 65536);
        } else {
          res = fh.write(0, genRandomBuffer((i - 5) * 65536));
          if (!res) {
            LOGGER.error("Thread-{} failed to write", i);
          } else {
            LOGGER.info("Thread-{} write succeeded", i);
          }
          Entry<ByteBuffer, ObjectAttributes> p = fh.read(0, (i - 5) * 65536);
          if (p == null) {
            LOGGER.error("Thread-{} failed to read", i);
            res = false;
          } else {
            LOGGER.info("Thread-{} read succeeded", i);
          }
        }
      } catch (Throwable t) {
        LOGGER.error("failed to truncate and read/write file at the same time", t);
        res = false;
      }
      return res;
    });
  }

  @Test
  public void testGarbageCollectTheRightBlock() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    dummyGarbageCollector.getBlockCollector().reset();
    FileHandler fh = createFileWith64KBlockSize("testGarbageCollectTheRightBlock");
    byte[] buf = genRandomBytes(65536 * 3);
    assertTrue(fh.write(0, ByteBuffer.wrap(buf)));
    assertTrue(fh.write(65536, ByteBuffer.wrap(buf, 65536, 65536)));
    Thread.sleep(100);
    Entry<ByteBuffer, ObjectAttributes> p = fh.read(0, 65536 * 3);
    assertEquals(ByteBuffer.wrap(buf), p.getKey());
    Set<FileBlock> garbage = dummyGarbageCollector.getBlockCollector().getCollectedBlocks();
    assertEquals(1, garbage.size());
    FileBlock gcBlock = garbage.iterator().next();
    assertEquals(1, gcBlock.getVersion());
    assertEquals(65536L, gcBlock.getOffset());
    assertEquals(65536L, gcBlock.getLength());
  }

  // -------- Snapshot Tests ---------
  @Test
  public void testBasicSnapshoting() throws Exception {
    FileHandler fh = createFileUnderRoot("testBasicSnapshoting");

    int length = 64 * 1024;
    ByteBuffer data1 = ByteBuffer.wrap(genRandomBytes(length));
    fh.write(0, data1);
    assertEquals(fh.getBlockSize(), length);
    assertEquals(1, fh.getBlockVersion(0).getKey().intValue());
    assertEquals(config.getGatewayId(), fh.getBlockVersion(0).getValue().shortValue());
    assertFalse(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    long timestamp = fh.takeSnapshot("After1stWrite", "After1stWrite").createTime;
    assertTrue(timestamp != 0);
    assertTrue(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    SnapshotInfo info = fh.lookupSnapshotByName("After1stWrite");
    assertTrue(info.id != 0);
    assertNotNull(info.attrs);
    assertEquals(info.attrs.getFilesize(), length);

    ByteBuffer data2 = ByteBuffer.wrap(genRandomBytes(length));
    fh.write(0, data2.duplicate());
    assertEquals(2, fh.getBlockVersion(0).getKey().intValue());
    fh.write(length, data2);
    assertEquals(2 * length, fh.getFileSize());

    SnapshotInfo restoredSnapshot = fh.restoreSnapshot("After1stWrite");
    assertNotNull(restoredSnapshot);
    assertTrue(timestamp == restoredSnapshot.createTime);
    assertEquals(1, fh.getBlockVersion(0).getKey().intValue());
    assertEquals(length, fh.getFileSize());

    ByteBuffer readData = fh.read(0, length).getKey();
    assertNotNull(readData);
    assertTrue(readData.hasArray());
    assertEquals(data1, readData);
  }

  @Test
  public void testLookupSnapshotById() throws Exception {
    FileHandler fh = createFileUnderRoot("testLookupSnapshotById");
    int len = 64 * 1024;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(len));
    assertTrue(fh.write(0, data.duplicate()));
    assertEquals(fh.getFileSize(), len);
    assertFalse(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    SnapshotInfo info = fh.takeSnapshot("1stSnapshot", "1stSnapshot");
    long ss1 = info.createTime;
    assertTrue(info.id == 2);
    assertTrue(ss1 != 0);
    assertTrue(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    assertTrue("1stSnapshot".equals(fh.lookupSnapshotById(info.id).name));
  }

  @Test
  public void testDeleteSnapshot() throws Exception {
    FileHandler fh = createFileUnderRoot("testDeleteSnapshot");
    int len = 64 * 1024;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(len));
    assertTrue(fh.write(0, data.duplicate()));
    assertEquals(fh.getFileSize(), len);
    assertFalse(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    SnapshotInfo info1 = fh.takeSnapshot("1stSnapshot", "1stSnapshot");
    assertTrue(info1.id == 2);
    assertTrue(info1.createTime != 0);
    assertTrue(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    List<DirEntry> ssEntries = fh.listSnapshots();
    assertEquals(1, ssEntries.size());
    assertEquals("1stSnapshot", ssEntries.get(0).getName());

    SnapshotInfo info2 = fh.takeSnapshot("2ndSnapshot", "2ndSnapshot");
    assertTrue(info2.id == 3);
    assertTrue(info2.createTime != 0);
    assertTrue(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    HashSet<String> expectedSnapshots = new HashSet<String>();
    expectedSnapshots.add("1stSnapshot");
    expectedSnapshots.add("2ndSnapshot");

    ssEntries = fh.listSnapshots();
    assertEquals(2, ssEntries.size());
    assertTrue(expectedSnapshots.contains(ssEntries.get(0).getName()));
    assertTrue(expectedSnapshots.contains(ssEntries.get(1).getName()));
    assertTrue(!ssEntries.get(0).getName().equals(ssEntries.get(1).getName()));

    fh.write(len, data.duplicate());
    assertEquals(fh.getFileSize(), 2 * len);

    assertEquals(info1.createTime, fh.deleteSnapshot("1stSnapshot").createTime);
    ssEntries = fh.listSnapshots();
    assertEquals(1, ssEntries.size());
    assertEquals("2ndSnapshot", ssEntries.get(0).getName());
    assertTrue(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));

    fh.restoreSnapshot("2ndSnapshot");
    assertEquals(fh.getFileSize(), len);

    assertEquals(info2.createTime, fh.deleteSnapshot("2ndSnapshot").createTime);
    ssEntries = fh.listSnapshots();
    assertTrue(ssEntries.isEmpty());
    assertFalse(AttributesHelper.hasSnapshot(fh.getAttrsCopy()));
  }

  @Test
  public void testDeleteFileWithSnapshots() throws Exception {
    FileHandler fh = createFileUnderRoot("testDeleteFileWithSnapshots");
    int len = 64 * 1024;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(len));
    assertTrue(fh.write(0, data.duplicate()));

    long ss = fh.takeSnapshot("Snapshot-1", null).createTime;
    List<DirEntry> ssEntries = fh.listSnapshots();
    assertEquals(1, ssEntries.size());
    assertEquals("Snapshot-1", ssEntries.get(0).getName());
    assertEquals(ss, ssEntries.get(0).getTimestamp());
    vh.getTransactionManager().flush();
    String zpath = fh.getSnapshotZpath("Snapshot-1");
    Stat st = client.checkExists().forPath(zpath);
    assertNotNull(st);

    assertTrue(fh.delete());
    vh.getTransactionManager().flush();
    st = client.checkExists().forPath(zpath);
    assertTrue(st == null);
  }

  @Test
  public void testRecreateSnapshotAfterDeletion() throws Exception {
    FileHandler fh = createFileUnderRoot("testRecreateSnapshotAfterDeletion");
    int len = 64 * 1024;
    ByteBuffer data = ByteBuffer.wrap(genRandomBytes(len));
    assertTrue(fh.write(0, data.duplicate()));

    long ss = fh.takeSnapshot("Snapshot-1", null).createTime;
    List<DirEntry> ssEntries = fh.listSnapshots();
    assertEquals(1, ssEntries.size());
    assertEquals("Snapshot-1", ssEntries.get(0).getName());
    assertEquals(ss, ssEntries.get(0).getTimestamp());

    String zpath = fh.getSnapshotZpath("Snapshot-1");
    assertTrue(zkClient.checkExists(zpath));

    assertNotNull(fh.deleteSnapshot("Snapshot-1"));
    assertFalse(zkClient.checkExists(zpath));

    SnapshotInfo info = fh.takeSnapshot("Snapshot-1", null);
    assertNotNull(info);
    assertTrue(zkClient.checkExists(zpath));

    SnapshotInfo info2 = fh.takeSnapshot("Snapshot-2", null);
    assertNotNull(info2);
  }

  @Test
  public void testSnapshotCanRestoreTruncatedFile() throws Exception {
    FileHandler fh = createFileUnderRoot("testSnapshotCanRestoreTruncatedFile");
    int len = 64 * 1024;
    byte[] data = genRandomBytes(2 * len);
    ByteBuffer data1 = ByteBuffer.wrap(data, 0, len);
    ByteBuffer data2 = ByteBuffer.wrap(data, len, len);

    assertTrue(fh.write(0, data1.duplicate()));
    assertTrue(fh.write(len, data2.duplicate()));
    assertEquals(2 * len, fh.getFileSize());

    long ss = fh.takeSnapshot("LongSnapshot", null).createTime;
    assertTrue(ss != 0);

    assertTrue(fh.truncate(len));
    assertEquals(len, fh.getFileSize());

    assertEquals(ss, fh.restoreSnapshot("LongSnapshot").createTime);
    assertEquals(2 * len, fh.getFileSize());

    ByteBuffer res = fh.read(0, 2 * len).getKey();
    assertEquals(res, ByteBuffer.wrap(data));
  }

  // TODO test update snapshots
}
