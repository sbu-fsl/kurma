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
package edu.stonybrook.kurma.blockmanager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

import edu.stonybrook.kurma.records.GarbageBlockJournalRecord;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.VolumeHandler;
import edu.stonybrook.kurma.util.ThriftUtils;

import journal.io.api.Journal;
import journal.io.api.Journal.ReadType;
import journal.io.api.Journal.WriteType;
import journal.io.api.JournalBuilder;
import journal.io.api.Location;


public class BlockManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(BlockManager.class);
  private ConcurrentHashMap<String, BitSet> gatewayMap = new ConcurrentHashMap<String, BitSet>();
  private List<Short> gatewayIds = new ArrayList<>();
  private List<String> deletedBlockKeys = Collections.synchronizedList(new ArrayList<>());
  private Journal journal;
  private VolumeHandler vh;
  private long timeStamp;
  private long THRESHOLD;

  public BlockManager(List<Short> remoteGatewayIds, VolumeHandler vh, File journalDir)
      throws Exception {
    this.gatewayIds = remoteGatewayIds;
    this.gatewayIds.add(vh.getConfig().getGatewayId());
    this.vh = vh;

    // initialization of the journal
    boolean journalAlreadyExists = journalDir.exists();
    if (!journalAlreadyExists) {
      journalDir.mkdirs();
    }
    this.journal = JournalBuilder.of(journalDir).open();
    journal.setMaxFileLength(8192);

    if (journalAlreadyExists) {
      LOGGER.info("Replaying the journal {}", this.journal);
      this.replayBlockManager();
    }
    this.timeStamp = System.currentTimeMillis();
    this.THRESHOLD = 1000;
    vh.setBlockManager(this);
  }

  // update HashMap entry corresponding to response from the gateway
  private int updateResponseFromRemoteGateway(String key, short remoteGateway) {
    BitSet responseGateways = gatewayMap.get(key);
    if (responseGateways == null) {
      LOGGER.error("Block Key not found {}", key);
      return -1;
    }
    responseGateways.set(gatewayIds.indexOf(remoteGateway));
    gatewayMap.put(key, responseGateways);
    return responseGateways.cardinality();
  }

  private boolean isDeleteOk(String key) {
    BitSet responseGateways = gatewayMap.get(key);
    if (responseGateways == null) {
      LOGGER.error("Block Key not found {}", key);
      return false;
    }

    boolean deleteOk = true;

    for (short gwid : gatewayIds) {
      deleteOk = deleteOk & responseGateways.get(gatewayIds.indexOf(gwid));
    }
    return deleteOk;
  }

  private void markFileBlockDeleted(FileBlock fb) {
    String key = new String(BaseEncoding.base64Url().encode(fb.getKey()));
    LOGGER.info("Successfully marked the block for deletion {}", key);
    gatewayMap.remove(key);
    deletedBlockKeys.add(key);
  }

  private boolean triggerBlockManagerGarbageCollector(List<String> keyList) {

    // TODO:use new GC API

    LOGGER.info("Sending blocks to garbage collector");

    for (String key : keyList) {
      gatewayMap.remove(key);
      // delete corresponding record from the journal.
      try {
        GarbageBlockJournalRecord rObj = new GarbageBlockJournalRecord();
        // inefficient. need some better way.
        // there will be N records with same key, where N = #gateways
        for (Location location : journal.redo()) {
          byte[] data = location.getData();
          ThriftUtils.decodeBinary(data, rObj);
          if (rObj.getBlock_key().equals(key))
            journal.delete(location);
        }
      } catch (Exception e) {
        LOGGER.error("Deletion of journal records failed {}", e);
        return false;
      }
    }
    return true;
  }

  private GarbageBlockJournalRecord buildGarbageBlockJournalRecord(FileBlock fb, String key,
      short rmtGwid) {
    GarbageBlockJournalRecord record =
        new GarbageBlockJournalRecord(fb.getGateway(), fb.getFile().getOid(), fb.getOffset(),
            fb.getVersion(), fb.getLength(), fb.getFile().getKvs_ids(), key, fb.getTimestamp());

    record.setLast_response_time(System.currentTimeMillis());
    record.setRemotegwid(rmtGwid);
    return record;
  }

  private int replayBlockManager() throws Exception {
    int replayed = 0;

    for (Location location : journal.redo()) {
      byte[] record = this.journal.read(location, ReadType.SYNC);
      BitSet response;
      GarbageBlockJournalRecord recObj = new GarbageBlockJournalRecord();
      ThriftUtils.decodeBinary(record, recObj);
      String repKey = recObj.getBlock_key();
      short rmtGwid = recObj.getRemotegwid();

      response = gatewayMap.get(repKey);

      if (response == null) {
        response = new BitSet();
        response.set(gatewayIds.indexOf(rmtGwid));
        gatewayMap.put(repKey, response);
      } else {
        response.set(gatewayIds.indexOf(rmtGwid));
        gatewayMap.put(repKey, response);
      }

      if (isDeleteOk(repKey)) {
        deletedBlockKeys.add(repKey);
      }
      replayed++;
    }
    return replayed;
  }

  // put the response array into HashMap for FileBlock which is
  // being deleted.
  public boolean notifyDeleteLocalGateway(FileBlock fb) throws Exception {
    String key = new String(BaseEncoding.base64Url().encode(fb.getKey()));
    short localGwid = vh.getConfig().getGatewayId();
    BitSet responseGateways = new BitSet();
    GarbageBlockJournalRecord record;

    responseGateways.set(gatewayIds.indexOf(localGwid));
    gatewayMap.put(key, responseGateways);
    record = buildGarbageBlockJournalRecord(fb, key, localGwid);

    if (journal.write(ThriftUtils.encodeBinary(record), WriteType.ASYNC) != null)
      return true;
    else
      return false;
  }

  // update ACK from one destination gateway
  // this need to be invoked from local gateway once acknowledgement is received
  public boolean notifyDeleteRemoteGateway(FileBlock fb, short remoteGwid) throws Exception {
    String key = new String(BaseEncoding.base64Url().encode(fb.getKey()));
    GarbageBlockJournalRecord record;

    updateResponseFromRemoteGateway(key, remoteGwid);
    record = buildGarbageBlockJournalRecord(fb, key, remoteGwid);

    Location location = journal.write(ThriftUtils.encodeBinary(record), WriteType.ASYNC);
    if (location == null)
      return false;

    synchronized (this) {
      if (isDeleteOk(key)) {
        // remove the key from hash and put into the placeholder list
        markFileBlockDeleted(fb);
      }
    }

    // time based threshold. need to handle it in more efficient way
    if (System.currentTimeMillis() - timeStamp >= THRESHOLD) {
      List<String> tempBlockKeys = new ArrayList<>();

      synchronized (this) {
        for (String cKey : deletedBlockKeys) {
          tempBlockKeys.add(cKey);
        }
        deletedBlockKeys.clear();
        timeStamp = System.currentTimeMillis();
      }

      if (triggerBlockManagerGarbageCollector(tempBlockKeys) == false)
        return false;
    }

    return true;
  }

  public List<Short> getGatewayIds() {
    return gatewayIds;
  }

  public List<String> getDeletedBlockKeys() {
    return deletedBlockKeys;
  }

  public void setDeletedBlockKeys(List<String> deletedBlockKeys) {
    this.deletedBlockKeys = deletedBlockKeys;
  }

  public ConcurrentHashMap<String, BitSet> getGatewayMap() {
    return gatewayMap;
  }

  public Journal getJournal() {
    return journal;
  }
}
