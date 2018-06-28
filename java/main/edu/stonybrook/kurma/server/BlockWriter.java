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

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.cloud.SecretSharingFacade;
import edu.stonybrook.kurma.util.AuthenticatedEncryption;
import edu.stonybrook.kurma.util.LoggingUtils;

public class BlockWriter implements Callable<Entry<Boolean, FileBlock>> {
  private static Logger LOGGER = LoggerFactory.getLogger(BlockWriter.class);

  private FileBlock block;
  private AuthenticatedEncryption aeCipher;
  private KvsFacade kvs;

  public BlockWriter(FileBlock block, AuthenticatedEncryption aeCipher, KvsFacade kvs) {
    this.block = block;
    this.aeCipher = aeCipher;
    this.kvs = kvs;
  }

  @Override
  public Entry<Boolean, FileBlock> call() throws Exception {
    int encryptedSize =
        aeCipher.getEncryptOutputLength(block.getLength() + FileBlock.ADDITIONAL_DATA_LENGTH);
    byte[] cipherBuffer = null;
    if (kvs instanceof SecretSharingFacade) {
      SecretSharingFacade ssf = (SecretSharingFacade) kvs;
      cipherBuffer = new byte[ssf.getSecretSharingCodec().getAlignedSecretSize(encryptedSize)];
    } else {
      cipherBuffer = new byte[encryptedSize];
    }
    ByteBuffer encrypted = ByteBuffer.wrap(cipherBuffer, 0, encryptedSize);
    ByteBuffer ad = ByteBuffer.wrap(block.getAdditionalData());
    try {
      aeCipher.authenticatedEncrypt(block.getValue(), ad, block.getIv(), encrypted);
      String key = new String(BaseEncoding.base64Url().encode(block.getKey()));
      encrypted.rewind();
      kvs.put(key, encrypted);
      LOGGER.debug("finish writing {}: {}", block, LoggingUtils.hash(cipherBuffer));
    } catch (Exception e) {
      LOGGER.error("BlockWriter failed", e);
      return new AbstractMap.SimpleEntry<Boolean, FileBlock>(false, block);
    }
    return new AbstractMap.SimpleEntry<Boolean, FileBlock>(true, block);
  }
}
