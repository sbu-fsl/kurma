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
package edu.stonybrook.kurma.helpers;

import java.util.AbstractMap;
import java.util.Map.Entry;

import edu.stonybrook.kurma.meta.BlockMap;

public class BlockMapHelper {

  /**
   * Increment version number of block and change modifier gateway
   * 
   * @param blockMap the BlockMap to be updated
   * @param i index of the interesting entry
   * @param gwid ID of the modifying gateway
   * @return the old entry
   */
  public static Entry<Long, Short> incrementVersion(BlockMap blockMap, int i, short gwid) {
    Entry<Long, Short> blockEntry = getBlockEntry(blockMap, i);
    long v = blockEntry.getKey();
    if (v < 0) {
      v ^= (1L << 63);
    }
    final long nv = v + 1;
    blockMap.versions.set(i, nv);
    blockMap.last_modifier.set(i, gwid);
    return new AbstractMap.SimpleEntry<Long, Short>(v, blockEntry.getValue());
  }

  /**
   * Update an entry of BlockMap to particular version
   * 
   * @param blockMap the BlockMap to be updated
   * @param i index of the interesting entry
   * @param gwid ID of the modifying gateway
   * @param newVersion new version of block
   * @return the old entry
   */
  public static Entry<Long, Short> updateBlock(BlockMap blockMap, int i, short gwid,
      Long newVersion) {
    Entry<Long, Short> blockEntry = getBlockEntry(blockMap, i);
    blockMap.versions.set(i, newVersion);
    blockMap.last_modifier.set(i, gwid);
    return blockEntry;
  }

  /**
   * Get an entry of BlockMap.
   * 
   * @param blockMap the BlockMap to search for
   * @param i index of the interesting entry
   * @return block map entry
   */
  public static Entry<Long, Short> getBlockEntry(BlockMap blockMap, int i) {
    Long blockVersion = blockMap.versions.get(i);
    Short blockModifier = blockMap.last_modifier.get(i);
    return new AbstractMap.SimpleEntry<Long, Short>(blockVersion, blockModifier);
  }
}
