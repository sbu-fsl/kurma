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

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import edu.stonybrook.kurma.meta.ObjectID;

/**
 * A helper class that generate the keys of the cloud block <key, value> pairs.
 *
 * @author mchen
 *
 */
public class BlockKeyGenerator {
  final private ObjectID oid;
  final private byte[] key;

  public BlockKeyGenerator(ObjectID oid, byte[] key) {
    this.oid = oid;
    this.key = key;
  }

  /**
   * Generate a block key based on file key, offset, version, and gateway.
   *
   * @param offset
   * @param version
   * @param gateway
   * @return
   */
  public byte[] getBlockKey(long offset, long version, short gateway) {
    Hasher h = Hashing.sha256().newHasher();
    h.putBytes(key);
    h.putLong(oid.getId().getId1());
    h.putLong(oid.getId().getId2());
    h.putShort(oid.getCreator());
    h.putLong(offset);
    h.putLong(version);
    h.putShort(gateway);
    return h.hash().asBytes();
  }
}
