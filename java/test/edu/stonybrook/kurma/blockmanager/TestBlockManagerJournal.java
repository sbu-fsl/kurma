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
/*
 * unused :: package edu.stonybrook.kurma.blockmanager;
 * 
 * import static org.junit.Assert.*;
 * 
 * import java.io.File; import java.util.ArrayList; import java.util.List; import
 * java.util.concurrent.atomic.AtomicLong;
 * 
 * import org.junit.AfterClass; import org.junit.BeforeClass; import org.junit.Test;
 * 
 * import com.google.common.io.BaseEncoding;
 * 
 * import edu.stonybrook.kurma.TestBase; import edu.stonybrook.kurma.helpers.AttributesHelper;
 * import edu.stonybrook.kurma.meta.GarbageBlockJournalRecord; import
 * edu.stonybrook.kurma.meta.ObjectAttributes; import edu.stonybrook.kurma.meta.ObjectID; import
 * edu.stonybrook.kurma.server.FileBlock; import edu.stonybrook.kurma.server.FileHandler; import
 * edu.stonybrook.kurma.util.ThriftUtils; import journal.io.api.Journal; import
 * journal.io.api.Location; import journal.io.api.Journal.ReadType;
 * 
 * /*public class TestBlockManagerJournal extends TestBase { static List<Short> gwids = new
 * ArrayList<>(); // dummy gatewayIDs static File jDir; static BlockManagerJournal bmJournal;
 * 
 * @BeforeClass public static void setUpBeforeClass() throws Exception {
 * startTestServer(DUMMY_GARBAGE_COLLECTOR); gwids.add((short) 1); gwids.add((short) 2); jDir = new
 * File(vh.getConfig().getJournalDirectory() + "/TestBmJournal/"); bmJournal = new
 * BlockManagerJournal(gwids, jDir, jDir.exists()); }
 * 
 * @AfterClass public static void tearDownAfterClass() throws Exception { client.close();
 * closeTestServer();
 * 
 * } public static AtomicLong newTime = new AtomicLong(); public static AtomicLong addTime = new
 * AtomicLong();
 * 
 * private FileHandler createFile(String name) { long start = System.nanoTime(); ObjectID oid =
 * vh.newFileOid(); FileHandler fh = new FileHandler(oid, vh); ObjectAttributes attrs =
 * AttributesHelper.newFileAttributes();
 * 
 * fh.create(rootDh.getOid(), name, attrs); newTime.addAndGet(System.nanoTime() - start);
 * 
 * start = System.nanoTime(); rootDh.addChild(name, oid); addTime.addAndGet(System.nanoTime() -
 * start); return fh; }
 * 
 * @Test public void testBlockManagerJournal() { final int size = 64 * 1024; FileHandler fh =
 * createFile("testBlockManagerJournal"); FileBlock fb = new FileBlock(fh, 0, size, 1,
 * config.getGatewayId()); String key = new String(BaseEncoding.base64Url().encode(fb.getKey()));
 * Journal journal;
 * 
 * GarbageBlockJournalRecord recObj = new GarbageBlockJournalRecord(fb.getGateway(),
 * fb.getFile().getOid(), fb.getOffset(), fb.getVersion(), fb.getLength(),
 * fb.getFile().getKvs_ids(), key, fb.getTimestamp()); recObj.setRemotegwid((short)0);
 * recObj.setLast_response_time(System.currentTimeMillis());
 * 
 * byte [] record = ThriftUtils.encodeBinary(recObj);
 * 
 * if (bmJournal.write(record) != null) { System.out.println("Record is written to journal"); } else
 * { assertFalse(false); }
 * 
 * assertTrue(bmJournal.sync() == true); journal = bmJournal.getBmJournal(); try { for(Location
 * location: journal.redo()) { byte[] read = journal.read(location, ReadType.SYNC);
 * GarbageBlockJournalRecord recRead = new GarbageBlockJournalRecord();
 * ThriftUtils.decodeBinary(read, recRead); assertTrue(recRead.getOid() == recRead.getOid());
 * journal.delete(location); } journal.sync(); } catch (Exception e) { assertFalse(false); }
 * 
 * assertTrue(bmJournal.cleanup() == true); fh.delete();
 * System.out.println("Journaling testcase succeeded"); }
 * 
 * }
 */
