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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.hash.Hashing;

import edu.stonybrook.kurma.helpers.GatewayHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.meta.File;

public class FileBlock {
  private static Logger LOGGER = LoggerFactory.getLogger(FileBlock.class);
  public static final int ADDITIONAL_DATA_LENGTH = 20;
  private FileHandler file;
  private long offset;
  private int length;
  private long version;
  private long timestamp;
  private short gateway;
  private short flags = 0;
  private ByteBuffer value; // block data, or buffer to hold block data
  private boolean loaded; // if there is data in value
  private boolean isOperationSucceeded; // used for garbage collection

  public FileBlock(FileHandler file, long offset, int length, long version, short gateway) {
    this.file = file;
    this.offset = offset;
    this.length = length;
    this.version = version;
    this.gateway = gateway;
    this.value = null;
    this.loaded = false;
    this.timestamp = 0;
    this.isOperationSucceeded = false;
  }

  public FileBlock(FileHandler file, long offset, int length, long version, short gateway,
      ByteBuffer buf, boolean loaded) {
    this.file = file;
    this.offset = offset;
    this.length = length;
    this.version = version;
    this.gateway = gateway;
    this.value = buf;
    this.value.mark();
    this.loaded = loaded;
    this.timestamp = System.currentTimeMillis();
    this.isOperationSucceeded = false;
  }

  public byte[] getIv() {
    return ByteBuffer.allocate(16).putLong(offset).putLong(version).array();
  }

  public byte[] getAdditionalData() {
    return ByteBuffer.allocate(ADDITIONAL_DATA_LENGTH).putLong(timestamp).putLong(version)
        .putShort(gateway).putShort(flags).array();
  }

  public boolean verifyAdditionalData(ByteBuffer ad) {
    Preconditions.checkArgument(ad.remaining() >= 16);
    long ts = ad.getLong();
    long v = ad.getLong();
    short gw = ad.getShort();
    short f = ad.getShort();
    if (v != version || gw != gateway || f != flags) {
      LOGGER.info(
          "verification of block {} at offset {} length {} failed, expected and in-cloud version, "
              + "gateway, and flags: <{}, {}>, <{}, {}>, <{}, {}>",
          file.getOid(), offset, length, version, v, gateway, gw, flags, f);
      return false;
    }
    timestamp = ts;
    return true;
  }

  public long getOffset() {
    return offset;
  }

  public void setOffset(long offset) {
    this.offset = offset;
  }

  public int getLength() {
    return length;
  }

  public void setLength(int length) {
    this.length = length;
  }

  public short getGateway() {
    return gateway;
  }

  public void setGateway(short gateway) {
    this.gateway = gateway;
  }

  public byte[] getKey() {
    return file.getBlockKey(offset, version, gateway);
  }

  public void resetValueBuffer() {
    value.reset();
  }

  public ByteBuffer getValue() {
    return value;
  }

  public void setValue(ByteBuffer value) {
    this.value = value;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public File getFile() {
    return file.get();
  }

  public boolean isLoaded() {
    return loaded;
  }

  public void setLoaded(boolean loaded) {
    this.loaded = loaded;
  }

  public boolean isHole() {
    return this.version <= 0;
  }

  public FileHandler getFileHandler() {
    return file;
  }

  public void setOperationResult(boolean isOperationSucceeded) {
    this.isOperationSucceeded = isOperationSucceeded;
  }

  public boolean getOperationResult() {
    return isOperationSucceeded;
  }

  @Override
  public String toString() {
    return String.format("block %d version %d gatway %s of file %s (%s)",
        offset >> getFile().attrs.block_shift, version, GatewayHelper.nameOf(gateway),
        ObjectIdHelper.getShortId(getFile().oid),
        Hashing.murmur3_32().hashBytes(getKey()).toString());
  }

  @Override
  public int hashCode() {
    int hash = file.getOid().hashCode();
    hash += 13 * offset;
    hash += 17 * length;
    hash += 19 * version;
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof FileBlock)) {
      return false;
    }
    FileBlock fb = (FileBlock) obj;
    return file.getOid().equals(fb.getFile().getOid()) && offset == fb.getOffset()
        && length == fb.getLength() && version == fb.getVersion();
  }

}
