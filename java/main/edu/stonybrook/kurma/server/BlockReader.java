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
import edu.stonybrook.kurma.util.AuthenticatedEncryption;
import edu.stonybrook.kurma.util.LoggingUtils;

public class BlockReader implements Callable<Entry<Boolean, FileBlock>> {
  private static Logger LOGGER = LoggerFactory.getLogger(BlockReader.class);

  private FileBlock block;
  private AuthenticatedEncryption aeCipher;
  private KvsFacade kvs;

  public BlockReader(FileBlock block, AuthenticatedEncryption aeCipher, KvsFacade kvs) {
    this.block = block;
    this.aeCipher = aeCipher;
    this.kvs = kvs;
  }

  @Override
  public Entry<Boolean, FileBlock> call() throws Exception {
    boolean succeed = false;
    if (!block.isHole()) {
      try {
        String key = new String(BaseEncoding.base64Url().encode(block.getKey()));
        ByteBuffer value = kvs.get(key, (k, v) -> {
          ByteBuffer ad = ByteBuffer.allocate(FileBlock.ADDITIONAL_DATA_LENGTH);
          ad.mark();
          try {
            block.resetValueBuffer();
            aeCipher.authenticatedDecrypt(v, block.getIv(), block.getValue(), ad);
          } catch (Exception e) {
            String errmsg = String.format("authenticated decryption of block {} failed", block);
            LOGGER.error(errmsg, block);
            return false;
          }
          ad.reset();
          return block.verifyAdditionalData(ad);
        });
        succeed = (value != null);
        if (succeed) {
          block.setLoaded(true);
        }
        LOGGER.debug("finish reading none-hole {}: {}", block,
            (value == null ? "null" : LoggingUtils.hash(value)));
      } catch (Exception e) {
        LOGGER.error("BlockReader failed", e);
        return new AbstractMap.SimpleEntry<Boolean, FileBlock>(false, block);
      }
    } else {
      LOGGER.debug("finish reading hole {}", block);
      succeed = true;
      block.setLoaded(true);
    }
    return new AbstractMap.SimpleEntry<Boolean, FileBlock>(succeed, block);
  }

}
