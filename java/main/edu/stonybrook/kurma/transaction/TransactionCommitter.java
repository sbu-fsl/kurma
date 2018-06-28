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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import journal.io.api.Location;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.journal.MetaJournal;
import edu.stonybrook.kurma.records.ZKOperationType;

/**
 * Commit one or more metadata transactions into ZooKeeper.
 * TODO: add merging and tests for merging
 * @author mchen
 *
 */
public class TransactionCommitter implements Callable<TransactionCommitter> {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransactionCommitter.class);
  private CuratorFramework client;
  private MetaJournal journal;

  /**
   * Number of TransactionCommitter this committer depends on.
   */
  private CountDownLatch dependency;

  private final HashSet<String> zpaths;
  private KurmaTransactionManager manager;
  private List<KurmaTransaction> transactions;

  private ConcurrentLinkedQueue<TransactionCommitter> dependants = new ConcurrentLinkedQueue<>();

  private boolean finished = false;
  private boolean success = false;

  private void setFinished(boolean success) {
    LOGGER.debug("{} finished", this);
    this.finished = true;
    this.success = success;
  }

  public boolean isFinished() {
    return finished;
  }

  public boolean isFailed() {
    return !success;
  }

  public TransactionCommitter(CuratorFramework client, MetaJournal journal,
      KurmaTransactionManager manager, List<KurmaTransaction> txns,
      final HashSet<String> zpaths, int dependCount) {
    this.client = client;
    this.journal = journal;
    this.dependency = new CountDownLatch(dependCount);
    this.manager = manager;
    this.transactions = txns;
    this.zpaths = zpaths;
    LOGGER.debug("{} created with {} dependancy", toString(), dependCount);
  }

  public long getId() {
    return transactions.get(0).getTransactionID();
  }

  @Override
  public String toString() {
    return String.format("Committer-%d", getId());
  }

  public void satifyDependency() {
    dependency.countDown();
  }

  public long countDependency() {
    return dependency.getCount();
  }

  public void addDependant(TransactionCommitter tc) {
    dependants.add(tc);
  }

  public ConcurrentLinkedQueue<TransactionCommitter> getDependants() {
    return dependants;
  }

  @Override
  public TransactionCommitter call() throws Exception {
    try {
      LOGGER.debug("committing transactions by {}", toString());
      HashSet<OperationInfo> skipUpdates = mergeUpdates();
      dependency.await();
      CuratorTransaction txn = client.inTransaction();
      ArrayList<ResultProcessor> processors = new ArrayList<>();
      ArrayList<Location> locations = new ArrayList<>();
      for (KurmaTransaction kt : transactions) {
        for (OperationInfo op : kt.getOperations()) {
          if (skipUpdates.contains(op))
            continue;
          if (op.getType().equals(ZKOperationType.CREATE)) {
            txn = txn.create().forPath(op.getPath(), op.getData()).and();
            processors.add(op.getRp());
          } else if (op.getType().equals(ZKOperationType.REMOVE)) {
            txn = txn.delete().forPath(op.getPath()).and();
            processors.add(op.getRp());
          } else if (op.getType().equals(ZKOperationType.UPDATE)) {
            txn = txn.setData().forPath(op.getPath(), op.getData()).and();
            processors.add(op.getRp());
          }
        }
        locations.addAll(kt.getJournalLocations());
      }
      if (txn instanceof CuratorTransactionFinal) {
        ResultProcessor.commit((CuratorTransactionFinal)txn, processors);
      }
      for (Location loc : locations) {
        journal.delete(loc);
      }
      setFinished(true);
    } catch (CuratorException e) {
      LOGGER.error("CuratorTransaction failed to commit", e);
      LOGGER.error("Committer-{} with {} KurmaTransactions:", getId(), transactions.size());
      for (KurmaTransaction txn : transactions) {
        LOGGER.error(txn.toString());
      }
      setFinished(false);
    } catch (Exception e) {
      LOGGER.error("Unknown failure", e);
      setFinished(false);
    } finally {
      manager.finishCommit(this);
    }
    return this;
  }

  private HashSet<OperationInfo> mergeUpdates() {
    HashSet<OperationInfo> skipUpdates = new HashSet<OperationInfo>();
    HashMap<String, OperationInfo> updateOperations = new HashMap<>();
    for (KurmaTransaction kt : transactions) {
      for (OperationInfo op: kt.getOperations()) {
        if (op.getType().equals(ZKOperationType.UPDATE)) {
          if (updateOperations.containsKey(op.getPath())) {
            skipUpdates.add(updateOperations.get(op.getPath()));
          }
          updateOperations.put(op.getPath(), op);
        }
      }
    }
    return skipUpdates;
  }

  public HashSet<String> getZpaths() {
    return zpaths;
  }

}