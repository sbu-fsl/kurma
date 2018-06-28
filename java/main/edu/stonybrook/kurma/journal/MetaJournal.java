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
package edu.stonybrook.kurma.journal;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import journal.io.api.Journal;
import journal.io.api.Journal.ReadType;
import journal.io.api.Journal.WriteType;
import journal.io.api.JournalBuilder;
import journal.io.api.Location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.records.MetaUpdateJournalRecord;
import edu.stonybrook.kurma.records.ZKOperationType;
import edu.stonybrook.kurma.server.VolumeHandler;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.ResultProcessor;
import edu.stonybrook.kurma.util.ThriftUtils;

public class MetaJournal {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetaJournal.class);
  Journal journal;
  JournalCleaner jc;
  HashMap<Long, KurmaTransaction> txns = new HashMap<>();

  public MetaJournal(File journal_dir, int clean_frequency) {
    LOGGER.info("using directory '{}' for metadata journal", journal_dir);
    try {
      this.journal = JournalBuilder.of(journal_dir).open();
      jc = new JournalCleaner(journal, clean_frequency);
      jc.startCleaner();
    } catch (Exception e) {
      LOGGER.error("could not open journal", e);
    }
  }

  public int redoOldRecords(VolumeHandler vh) {
    int old_records = 0;
    LOGGER.info("Redo old journal records");
    try {
      journal.sync();
      for (Location location : journal.redo()) {
        if (location.isDeletedRecord())
          continue;

        MetaUpdateJournalRecord jr = read(location);
        KurmaTransaction txn = txns.computeIfAbsent(jr.getTransactionID(), tid -> {
          return new KurmaTransaction(this, tid);
        });

        if (jr.getType().equals(ZKOperationType.CREATE)) {
          ResultProcessor rp = ResultProcessorObjectHelper.noop("REDO Creation");
          LOGGER.debug("CREATE: {}", jr.toString());
          txn.addOperation(ZKOperationType.CREATE, jr.getZpath(), jr.getData(), rp, location);
        } else if (jr.getType().equals(ZKOperationType.REMOVE)) {
          ResultProcessor rp = ResultProcessorObjectHelper.noop("REDO Deletion");
          LOGGER.debug("DELETE: {}", jr.toString());
          txn.addOperation(ZKOperationType.REMOVE, jr.getZpath(), null, rp, location);
        } else if (jr.getType().equals(ZKOperationType.UPDATE)) {
          ResultProcessor rp = ResultProcessorObjectHelper.noop("REDO Update");
          LOGGER.debug("UPDATE: {}", jr.toString());
          txn.addOperation(ZKOperationType.UPDATE, jr.getZpath(), jr.getData(), rp, location);
        } else if (jr.getType().equals(ZKOperationType.COMMIT)) {
          txn.addOperation(ZKOperationType.COMMIT, jr.getZpath(), null, null, location);
          vh.getTransactionManager().addTransaction(txn);
          txns.remove(txn.getTransactionID());
        }
        old_records++;
      }
      // TODO: should we do a flush here?
      cleanJournal();
      journal.compact();
    } catch (Exception e) {
      LOGGER.error("could not redo old record", e);
      return -1;
    }
    LOGGER.info("found and redo {} journal records", old_records);
    return old_records;
  }

  public Location record(long transactionID, ZKOperationType op, String zpath, byte[] data) {
    MetaUpdateJournalRecord jr = new MetaUpdateJournalRecord(transactionID, op);
    if (zpath != null) {
      jr.setZpath(zpath);
    }
    if (data != null) {
      jr.setData(data);
    }
    Location location = write(jr, op.equals(ZKOperationType.COMMIT));
    return location;
  }

  public MetaUpdateJournalRecord read(Location location) throws Exception {
    MetaUpdateJournalRecord jr = null;
    try {
      byte[] record = journal.read(location, ReadType.ASYNC);
      jr = new MetaUpdateJournalRecord();
      ThriftUtils.decodeBinary(record, jr);
    } catch (Exception e) {
      LOGGER.error("failed to read MetaUpdateJournalRecord", e);
      throw e;
    }
    return jr;
  }

  public Location write(MetaUpdateJournalRecord jObj, boolean sync) {
    Location location = null;
    try {
      byte[] record = ThriftUtils.encodeBinary(jObj);
      location = journal.write(record, sync ? WriteType.SYNC : WriteType.ASYNC);
    } catch (Exception e) {
      LOGGER.error("could not write into journal", e);
    }
    return location;
  }

  public void delete(Location location) {
    if (location != null)
      try {
        journal.delete(location);
      } catch (IOException e) {
        LOGGER.error("could not delete from journal", e);
      }
  }

  public void cleanJournal() {
    LOGGER.info("Cleaning Journal");
    try {
      journal.sync();
      for (Location location : journal.redo()) {
        if (!location.isDeletedRecord())
          journal.delete(location);
      }
      journal.compact();
    } catch (Exception e) {
      LOGGER.error("could not clean journal", e);
    }
  }

  public void close() {
    try {
      jc.stopCleaner();
      journal.close();
    } catch (IOException e) {
      LOGGER.error("could not close journal", e);
    }
  }
}
