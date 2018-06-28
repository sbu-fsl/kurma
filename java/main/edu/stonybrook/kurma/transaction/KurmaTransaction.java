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
package edu.stonybrook.kurma.transaction;

import java.util.ArrayList;
import java.util.List;

import journal.io.api.Location;

import com.google.common.base.Preconditions;

import edu.stonybrook.kurma.journal.MetaJournal;
import edu.stonybrook.kurma.records.ZKOperationType;

/**
 * Not thread-safe. Need external synchronization.
 *
 * @author mchen
 *
 */
public class KurmaTransaction {
  private List<OperationInfo> operations;
  private List<Location> locations = new ArrayList<>();
  private MetaJournal journal;
  private final long transactionID;
  private boolean committed = false;

  public KurmaTransaction(MetaJournal journal, long transactionID) {
    operations = new ArrayList<OperationInfo>();
    this.journal = journal;
    this.transactionID = transactionID;
  }

  public boolean addOperation(ZKOperationType type, String zpath,
      byte[] data, ResultProcessor rp, Location location) {
    Preconditions.checkState(!committed);
    OperationInfo info =
        new OperationInfo(type, zpath, System.currentTimeMillis(), rp, data);

    if (type.equals(ZKOperationType.COMMIT)) {
      committed = true;
    }
    operations.add(info);
    locations.add(location);
    return true;
  }

  private boolean recordOperation(ZKOperationType type, String zpath, byte[] data) {
    Location location = journal.record(transactionID, type, zpath, data);
    if (location == null) {
      return false;
    }
    return addOperation(type, zpath, data, null, location);
  }

  public boolean create(String zpath, byte[] data) {
    return recordOperation(ZKOperationType.CREATE, zpath, data);
  }

  public boolean delete(String zpath) {
    return recordOperation(ZKOperationType.REMOVE, zpath, null);
  }

  public boolean update(String zpath, byte[] data) {
    return recordOperation(ZKOperationType.UPDATE, zpath, data);
  }

  protected boolean commit() {
    if (operations.isEmpty()) {
      return true;
    }
    return recordOperation(ZKOperationType.COMMIT, null, null);
  }

  public List<OperationInfo> getOperations() {
    return operations;
  }

  public List<Location> getJournalLocations() {
    return locations;
  }

  public long getTransactionID() {
    return transactionID;
  }

  public boolean isCommited() {
    return committed;
  }

  public boolean isEmpty() {
    return operations.isEmpty();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("KurmaTransaction-%d (%d ops):\n", transactionID, operations.size()));
    for (OperationInfo op : operations) {
      sb.append(op);
      sb.append('\n');
    }
    return sb.toString();
  }
}
