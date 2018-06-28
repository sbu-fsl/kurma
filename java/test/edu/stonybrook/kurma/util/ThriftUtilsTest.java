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
package edu.stonybrook.kurma.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import edu.stonybrook.kurma.helpers.DirectoryHelper;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.meta.BlockMap;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.VolumeInfo;

public class ThriftUtilsTest {

  @Test
  public void testThriftDefaultValues() {
    VolumeInfo vi = new VolumeInfo();
    assertEquals(vi.max_file_size_gb, 1024);
    assertEquals(vi.max_read_size, 1048576);
    assertEquals(vi.max_links, 1024);
    assertEquals(vi.umask, 2);
  }

  @Test
  public void testCorrectness() throws Exception {
    VolumeInfo vi = new VolumeInfo();
    vi.id = "testVolumeId";
    vi.create_time = 2015;
    byte[] data = ThriftUtils.encodeCompact(vi);
    assertNotNull(data);
    VolumeInfo vi2 = new VolumeInfo();
    ThriftUtils.decodeCompact(data, vi2);
    assertTrue(vi.equals(vi2));
  }

  @Test
  public void testNestedContainer() throws Exception {
    Directory dir = new Directory();
    short gwid = 1;
    ObjectID oid = ObjectIdHelper.newDirectoryOid(Int128Helper.newId(123, 456), gwid);
    dir.setOid(oid);

    // container not set will be encoded to null
    byte[] data = ThriftUtils.encode(dir, true);
    Directory recoveredDir = new Directory();
    ThriftUtils.decode(data, recoveredDir, true);
    assertNull(recoveredDir.entries);

    // empty container will be encoded to emtpy container
    List<DirEntry> entries = new ArrayList<DirEntry>();
    dir.setEntries(entries);
    data = ThriftUtils.encode(dir, true);
    ThriftUtils.decode(data, recoveredDir, true);
    assertNotNull(recoveredDir.entries);
    assertTrue(recoveredDir.entries.isEmpty());

    ObjectID child1 = ObjectIdHelper.newDirectoryOid(Int128Helper.newId(124, 456), gwid);
    entries.add(DirectoryHelper.newDirEntry(child1, "1"));
    data = ThriftUtils.encode(dir, true);
    ThriftUtils.decode(data, recoveredDir, true);

    assertNotNull(recoveredDir.entries);
    assertEquals(recoveredDir.entries.size(), 1);
    assertEquals(recoveredDir.entries.get(0).name, "1");
  }

  @Test
  public void testEmbeddedList() throws Exception {
    BlockMap bm = new BlockMap();
    bm.setVersions(new ArrayList<Long>());
    assertEquals(0, bm.versions.size());
    assertTrue(bm.versions.add(0l));
    assertEquals(1, bm.versions.size());
  }

}
