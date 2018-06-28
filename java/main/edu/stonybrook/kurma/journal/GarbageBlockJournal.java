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
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

import journal.io.api.Journal;
import journal.io.api.JournalBuilder;
import journal.io.api.Location;
import journal.io.api.Journal.ReadType;
import journal.io.api.Journal.WriteType;
import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsManager;
import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.records.GarbageBlockJournalRecord;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.VolumeHandler;
import edu.stonybrook.kurma.util.ThriftUtils;

public class GarbageBlockJournal {
  private static final Logger LOGGER = LoggerFactory.getLogger(GarbageBlockJournal.class);
  Journal journal;
  JournalCleaner jc;

  public GarbageBlockJournal(File journal_dir, int clean_frequency) {
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
    LOGGER.info("Redo old blocks");
    try {
      journal.sync();
      for (Location location : journal.redo()) {
        if (location.isDeletedRecord())
          continue;
        byte[] record = journal.read(location, ReadType.ASYNC);
        GarbageBlockJournalRecord jObj = new GarbageBlockJournalRecord();
        ThriftUtils.decodeBinary(record, jObj);
        LOGGER.info("Found in journal: {}", jObj.toString());
        KvsManager kvm = vh.getConfig().getKvsManager();
        List<String> list =
            Arrays.asList(jObj.getKvs_ids().split(GatewayConfig.KURMA_KVS_SEPARATOR));
        List<Kvs> old_kvs = kvm.getKvsListByIds(list);
        for (Kvs kv : old_kvs) {
          LOGGER.info("Deleting: {}", kv.toString());
          kv.delete(jObj.getBlock_key());
        }
        old_records++;
        journal.delete(location);
      }
      journal.compact();
    } catch (Exception e) {
      LOGGER.error("could not redo old record", e);
      return -1;
    }
    return old_records;
  }

  public Location write(FileBlock block) {
    Location location = null;
    try {

      String key = new String(BaseEncoding.base64Url().encode(block.getKey()));
      GarbageBlockJournalRecord jObj = new GarbageBlockJournalRecord(block.getGateway(),
          block.getFile().getOid(), block.getOffset(), block.getVersion(), block.getLength(),
          block.getFile().getKvs_ids(), key, block.getTimestamp());
      LOGGER.info("Writing in journal: {}", jObj.toString());
      byte[] record = ThriftUtils.encodeBinary(jObj);
      location = journal.write(record, WriteType.ASYNC);
    } catch (Exception e) {
      LOGGER.error("could not write into journal", e);
    }
    return location;
  }

  public void delete(Location location) {
    if (location != null) {
      try {
        journal.delete(location);
      } catch (Exception e) {
        LOGGER.error("could not delete from journal", e);
      }
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
