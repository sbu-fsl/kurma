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
 * unused:: package edu.stonybrook.kurma.blockmanager;
 * 
 * import java.io.File; import java.util.BitSet; import java.util.HashMap; import java.util.List;
 * 
 * import org.slf4j.Logger; import org.slf4j.LoggerFactory;
 * 
 * import edu.stonybrook.kurma.meta.GarbageBlockJournalRecord; import
 * edu.stonybrook.kurma.util.ThriftUtils;
 * 
 * import journal.io.api.Journal; import journal.io.api.Journal.ReadType; import
 * journal.io.api.Journal.WriteType; import journal.io.api.JournalBuilder; import
 * journal.io.api.Location;
 * 
 * //TODO: merge this code to BM. // Journal module for Block Manager. // Initial stubs and TODO(s)
 * public class BlockManagerJournal { private static final Logger LOGGER =
 * LoggerFactory.getLogger(BlockManagerJournal.class); private File journalDir; private List<Short>
 * gatewayIds; private Journal journal;
 * 
 * public BlockManagerJournal (List <Short> gwids, File journalDir, boolean jDirExists) throws
 * Exception { this.gatewayIds = gwids; this.journalDir = journalDir;
 * System.out.println(journalDir); if (!jDirExists) { journalDir.mkdirs(); } this.journal =
 * JournalBuilder.of(this.journalDir).open(); journal.setMaxFileLength(8192); }
 * 
 * // file block, op-type executed on the file block. public Location write(byte[] record) { try {
 * // lets try with async return journal.write(record, WriteType.ASYNC); } catch (Exception e) {
 * LOGGER.error("Write to Journal Failed {}", e); return null; } }
 * 
 * 
 * public int replay(HashMap<String, BitSet> hashMap, List<String> keys) { try { int replayed = 0;
 * for(Location location: journal.redo()) { byte[] record = journal.read(location, ReadType.SYNC);
 * BitSet response; GarbageBlockJournalRecord recObj = new GarbageBlockJournalRecord();
 * ThriftUtils.decodeBinary(record, recObj); String repKey = recObj.getBlock_key(); short rmtGwid =
 * recObj.getRemotegwid(); response = hashMap.get(repKey);
 * 
 * if (response == null) { response = new BitSet(); response.set(gatewayIds.indexOf(rmtGwid));
 * hashMap.put(repKey, response); } else { response.set(gatewayIds.indexOf(rmtGwid));
 * hashMap.put(repKey, response); }
 * 
 * response = hashMap.get(repKey); if (response == null) { throw new
 * Exception("Block Key not found"); }
 * 
 * boolean deleteOk = true;
 * 
 * for (short gwid : gatewayIds) { deleteOk = deleteOk & response.get(gatewayIds.indexOf(gwid)); }
 * 
 * if (deleteOk) keys.add(repKey); replayed++; } return replayed; } catch (Exception e) {
 * LOGGER.error("Journal Replay Failed {}", e); return -1; } }
 * 
 * public boolean sync() { try { journal.sync(); return true; } catch (Exception e) {
 * LOGGER.error("Journal Sync failed {}", e); return false; } }
 * 
 * public boolean close() { try { journal.close(); return true; } catch (Exception e) {
 * LOGGER.error("Journal close failed {}", e); return false; } }
 * 
 * public void compactJournal() { // Probably use later try { this.journal.compact(); } catch
 * (Exception e) { LOGGER.error("Journal Compaction Failed {}", e); } }
 * 
 * // move to testcase public boolean cleanup() { try { assert(journalDir.exists() == true); for
 * (Location location:journal.redo()) { journal.delete(location); } journal.close();
 * journalDir.delete(); return true; } catch (Exception e) {
 * LOGGER.error("Journal Cleanup failed {}", e); return false; } }
 * 
 * public Journal getBmJournal() { return journal; } }
 */
