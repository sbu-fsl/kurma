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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map.Entry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;
import edu.stonybrook.kurma.fs.KurmaError;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.ObjectID;

public class DirectoryHandlerTest extends TestBase {
  private static DirectoryHandler rootDh;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
    rootDh = vh.getRootDirectory();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  public static DirectoryHandler addChildDirectory(VolumeHandler vh, DirectoryHandler parent,
      String name) {
    return parent.createChildDirectory(name, AttributesHelper.newDirAttributes());
  }

  public static FileHandler addChildFile(VolumeHandler vh, DirectoryHandler parent, String name) {
    return parent.createChildFile(name, AttributesHelper.newFileAttributes());
  }

  @Test
  public void testLookupInRootDirectory() throws Exception {
    String dirName = "testLookupInRootDirectory";
    DirectoryHandler dh = addChildDirectory(vh, rootDh, dirName);

    assertEquals(rootDh.lookup(dirName).name, dirName);
    assertTrue(ObjectIdHelper.equals(rootDh.getOid(), dh.lookup("..").getOid()));
    assertTrue(ObjectIdHelper.equals(dh.getOid(), dh.lookup(".").getOid()));

    List<DirEntry> entries = rootDh.list();
    assertTrue(entries.size() >= 1);
  }

  @Test
  public void testRenameInSameDirectory() throws Exception {
    String oldName = "testRenameInSameDirectory1";
    addChildDirectory(vh, rootDh, oldName);

    assertEquals(rootDh.lookup(oldName).name, oldName);

    String newName = "testRenameInSameDirectory2";
    Entry<ObjectID, Integer> res = rootDh.renameChild(oldName, rootDh, newName, true);
    assertEquals(0, res.getValue().intValue());
    assertEquals(rootDh.lookup(newName).name, newName);
    assertNull(rootDh.lookup(oldName));
  }

  @Test
  public void testRenameInTwoDirectories() throws Exception {
    final String fname = "testRenameInTwoDirectories1";
    DirectoryHandler dh1 = rootDh;
    DirectoryHandler dh2 = addChildDirectory(vh, rootDh, fname);

    assertEquals(dh1.lookup(fname).name, fname);
    assertNull(dh2.lookup(fname));

    Entry<ObjectID, Integer> res = dh1.renameChild(fname, dh2, fname, true);
    assertEquals(0, res.getValue().intValue());

    assertNull(dh1.lookup(fname));
    assertEquals(dh2.lookup(fname).name, fname);
  }

  @Test
  public void testRenameDirectories() throws Exception {
    DirectoryHandler dh = addChildDirectory(vh, rootDh, "testRenameOverwrite");
    DirectoryHandler dh1 = addChildDirectory(vh, dh, "dir1");
    FileHandler fh = addChildFile(vh, dh1, "foo");

    Entry<ObjectID, Integer> res = dh.renameChild("dir1", dh, "dir2", true);
    assertEquals(0, res.getValue().intValue());
    DirectoryHandler dh2 = vh.getDirectoryHandler(dh1.getOid());
    assertEquals("dir2", dh2.getName());
    assertEquals(fh.getOid(), dh2.lookup("foo").getOid());
  }

  @Test
  public void testRenameOverwriteExistingFile() throws Exception {
    DirectoryHandler dh1 = addChildDirectory(vh, rootDh, "testRenameOverwrite1");
    DirectoryHandler dh2 = addChildDirectory(vh, rootDh, "testRenameOverwrite2");
    FileHandler fh1 = addChildFile(vh, dh1, "file1");
    FileHandler fh2 = addChildFile(vh, dh2, "file2");
    ObjectID oid1 = fh1.getOid();
    ObjectID oid2 = fh2.getOid();

    // rename to an existing file should remove that file
    Entry<ObjectID, Integer> res = dh1.renameChild("file1", dh2, "file2", true);
    assertEquals(0, res.getValue().intValue());
    assertNull(dh1.lookup("file1"));
    assertEquals(oid1, dh2.lookup("file2").getOid());
    assertNotEquals(oid2, dh2.lookup("file2").getOid());
  }

  /**
   * We should not be able to rename a file to a directory, or vice versa. Renaming of mismatched FS
   * object should fail.
   */
  @Test
  public void testRenameMismatch() throws Exception {
    DirectoryHandler dh1 = addChildDirectory(vh, rootDh, "testRenameMismatch1");
    DirectoryHandler dh2 = addChildDirectory(vh, rootDh, "testRenameMismatch2");
    FileHandler fh = addChildFile(vh, dh1, "file");

    // rename a file to a directory (fh -> dh2) should fail
    Entry<ObjectID, Integer> res = dh1.renameChild("file", rootDh, "testRenameMismatch2", true);
    assertEquals(KurmaError.NOT_DIRECTORY.getValue(), res.getValue().intValue());

    // a rename failure should correctly roll states back
    assertEquals(fh.getOid(), dh1.lookup("file").getOid());
    assertEquals(dh2.getOid(), rootDh.lookup("testRenameMismatch2").getOid());
  }

  @Test
  public void testAddChild() throws Exception {
    // insert a
    // then insert a again
  }

  @Test
  public void testRemoveChildFromDirectory() throws Exception {
    DirectoryHandler dh1 = addChildDirectory(vh, rootDh, "testRemoveChildFromDirectory1");
    DirectoryHandler dh2 = addChildDirectory(vh, rootDh, "testRemoveChildFromDirectory2");
    DirectoryHandler dh3 = addChildDirectory(vh, rootDh, "testRemoveChildFromDirectory3");

    assertEquals(rootDh.lookup(dh1.getName()).oid, dh1.getOid());
    assertEquals(rootDh.lookup(dh2.getName()).oid, dh2.getOid());
    assertEquals(rootDh.lookup(dh3.getName()).oid, dh3.getOid());

    assertEquals(rootDh.removeChild(dh1.getName()).oid, dh1.getOid());
    assertNull(rootDh.lookup(dh1.getName()));
    assertEquals(rootDh.lookup(dh2.getName()).oid, dh2.getOid());
    assertEquals(rootDh.lookup(dh3.getName()).oid, dh3.getOid());

    assertEquals(rootDh.removeChild(dh2.getName()).oid, dh2.getOid());
    assertNull(rootDh.lookup(dh1.getName()));
    assertNull(rootDh.lookup(dh2.getName()));
    assertEquals(rootDh.lookup(dh3.getName()).oid, dh3.getOid());
  }

  @Test
  public void benchFileDeletion() throws Exception {
    DirectoryHandler dh = addChildDirectory(vh, rootDh, "benchFileDeletion");
    final int N = 1000;
    for (int i = 0; i < N; ++i) {
      addChildDirectory(vh, dh, "file-" + i);
    }
    long start = System.nanoTime();
    for (int i = 0; i < N; ++i) {
      dh.removeChild("file-" + i);
    }
    long end = System.nanoTime();
    System.out.printf("average time: %.2f ms\n", (end - start) / 1000000.0 / N);
    System.out.printf("zk time1: %.2f ms\n", DirectoryHandler.zkTime1.get() / 1000000.0 / N);
    System.out.printf("zk time2: %.2f ms\n", DirectoryHandler.zkTime2.get() / 1000000.0 / N);
  }

  @Test
  public void testDeleteDirectories() throws Exception {
    DirectoryHandler parent = addChildDirectory(vh, rootDh, "parent");
    DirectoryHandler dh1 = addChildDirectory(vh, parent, "testDeleteDirectories1");
    DirectoryHandler dh2 = addChildDirectory(vh, parent, "testDeleteDirectories2");

    assertEquals(parent.lookup(dh1.getName()).oid, dh1.getOid());
    assertEquals(parent.lookup(dh2.getName()).oid, dh2.getOid());

    assertNotNull(parent.removeChild(dh1.getName()));
    System.out.println(dh1.getZpath());
    // assertTrue(dh1.delete());
    assertNull(parent.lookup(dh1.getName()));
    assertEquals(parent.lookup(dh2.getName()).oid, dh2.getOid());
    // We won't be able to delete the parent directory as it is not empty.
    assertFalse(parent.delete());

    assertNotNull(parent.removeChild(dh2.getName()));
    // assertTrue(dh2.delete());
    assertNull(parent.lookup(dh1.getName()));
    assertNull(parent.lookup(dh2.getName()));

    assertNotNull(rootDh.removeChild(parent.getName()));
    // assertTrue(parent.delete());
    assertNull(rootDh.lookup(parent.getName()));
  }

  @Test
  public void testDeleteFiles() throws Exception {
    DirectoryHandler parent = addChildDirectory(vh, rootDh, "testDeleteFiles");
    FileHandler fh1 = parent.createChildFile("file1", AttributesHelper.newFileAttributes());
    FileHandler fh2 = parent.createChildFile("file2", AttributesHelper.newFileAttributes());
    assertNotNull(fh1);
    assertNotNull(fh2);
    assertFalse(parent.delete());
    assertNotNull(parent.removeChild("file1"));
    assertNotNull(parent.removeChild("file2"));
    assertTrue(parent.delete());
    // TODO add statistics into GC, and test the statistics
  }

  @Test
  public void testRenameNonEmptyDirectory() throws Exception {
    final String dname1 = "testRenameNonEmptyDirectory1";
    final String dname2 = "testRenameNonEmptyDirectory2";
    DirectoryHandler dh2 = addChildDirectory(vh, rootDh, dname1);
    DirectoryHandler dh3 = addChildDirectory(vh, rootDh, dname2);
    addChildFile(vh, dh2, "file1");
    addChildFile(vh, dh3, "file2");
    addChildFile(vh, dh3, "file3");
    Entry<ObjectID, Integer> res = rootDh.renameChild(dname1, rootDh, dname2, true);
    assertEquals(KurmaError.DIRECTORY_NOT_EMPTY.getValue(), res.getValue().intValue());
  }

  @Test
  public void testRemoveChildAndListChild() throws Exception {
    DirectoryHandler dh1 = addChildDirectory(vh, rootDh, "testRemoveChildAndListChild");
    DirectoryHandler dh2 = addChildDirectory(vh, dh1, "testChildDirectory");
    FileHandler fh = addChildFile(vh, dh2, "file");

    assertEquals(rootDh.lookup(dh1.getName()).oid, dh1.getOid());
    assertEquals(dh1.lookup(dh2.getName()).oid, dh2.getOid());
    assertEquals(dh2.lookup(fh.getName()).oid, fh.getOid());
    assertEquals(dh1.list().size(), 1);
    assertEquals(dh2.list().size(), 1);

    assertNull(dh1.removeChild(dh2.getName()));
    assertEquals(rootDh.lookup(dh1.getName()).oid, dh1.getOid());
    assertEquals(dh1.lookup(dh2.getName()).oid, dh2.getOid());
    assertEquals(dh2.lookup(fh.getName()).oid, fh.getOid());
    assertEquals(dh1.list().size(), 1);
  }
}
