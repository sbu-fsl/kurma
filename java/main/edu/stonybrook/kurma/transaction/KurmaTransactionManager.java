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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import edu.stonybrook.kurma.journal.MetaJournal;
import edu.stonybrook.kurma.records.ZKOperationType;
import edu.stonybrook.kurma.util.ZkUtils;

public class KurmaTransactionManager {
  private AtomicLong transactionID;
  private int opCount = 0;
  private CuratorFramework client;
  private PeriodicTransactionCommiter tc;
  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaTransactionManager.class);

  /**
   * A HashMap of zpath and all transaction committers that works on it.
   */
  private HashMap<String, LinkedList<TransactionCommitter>> committingZpaths = new HashMap<>();

  private long stagingChangeTime;
  private List<KurmaTransaction> stagingTransactions = new ArrayList<>();

  private ExecutorService executors = Executors.newCachedThreadPool();
  private HashMap<Long, Future<TransactionCommitter>> commitResults = new HashMap<>();
  private MetaJournal journal;
  private int commitInterval;   // periodic auto-commit in seconds
  private int commitThreshold;

  public KurmaTransactionManager(CuratorFramework client, MetaJournal journal, int interval, int threshold) {
    this.client = client;
    this.journal = journal;
    this.commitInterval = interval;
    this.commitThreshold = threshold;
    stagingChangeTime = System.currentTimeMillis();
    transactionID = new AtomicLong(0);
    startPeriodicCommiter();
  }

  public KurmaTransaction getNewTransaction() {
    KurmaTransaction txn = new KurmaTransaction(journal, transactionID.addAndGet(1));
    return txn;
  }

  public synchronized boolean allCommitted() {
    LOGGER.debug("there are {} working committers", commitResults.size());
    return commitResults.isEmpty();
  }

  /**
   * Initiate to commit all transactions and wait until all are done.
   */
  public void flush() {
    LOGGER.debug("flushing all transactions");
    commitStagingTxns();
    while (!allCommitted()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        LOGGER.warn("flushing interrupted. Retrying...");
      }
    }
    LOGGER.debug("all transactions flushed.");
  }

  protected boolean submit(KurmaTransaction txn) {
    boolean res = txn.commit();
    if (res) {
      addTransaction(txn);
    }
    LOGGER.trace("submitted KurmaTransaction: {}", txn);
    return res;
  }

  protected synchronized void commitStagingTxnsIfNeeded() {
    if (System.currentTimeMillis() - stagingChangeTime >= commitInterval * 1000) {
      commitStagingTxns();
    }
  }

  private synchronized void commitStagingTxns() {
    if (stagingTransactions.isEmpty()) {
      return;
    }

    HashSet<String> stagingZpaths = new HashSet<>();
    HashSet<String> depZpaths = new HashSet<>();
    for (KurmaTransaction txn : stagingTransactions) {
      for (OperationInfo op : txn.getOperations()) {
        String zpath = op.getPath();
        if (zpath == null) {
          continue;
        }
        if (op.getType().equals(ZKOperationType.CREATE)) {
          String parent = ZkUtils.getParentZpath(zpath);
          if ("/".equals(parent)) {
            depZpaths.add(parent);
          }
        }
        stagingZpaths.add(zpath);
        depZpaths.add(zpath);
      }
    }

    HashSet<String> deps = new HashSet<>();
    int ndeps = 0;
    for (String zp : depZpaths) {
      LinkedList<TransactionCommitter> committers = committingZpaths.get(zp);
      if (committers != null) {
        LOGGER.debug("Committer-{} depends on {} for {}",
            stagingTransactions.get(0).getTransactionID(), committers, zp);
        ndeps += committers.size();
        deps.add(zp);
      }
    }

    TransactionCommitter committer = new TransactionCommitter(client, journal, this,
        stagingTransactions, stagingZpaths, ndeps);

    for (String dep : deps) {
      committingZpaths.get(dep).forEach(tc -> { tc.addDependant(committer); });
    }

    for (String zp : stagingZpaths) {
      LinkedList<TransactionCommitter> committers = committingZpaths.get(zp);
      if (committers == null) {
        committers = new LinkedList<>();
        committers.add(committer);
        committingZpaths.put(zp, committers);
      } else {
        committers.addLast(committer);
      }
    }

    commitResults.put(committer.getId(), executors.submit(committer));
    LOGGER.debug("staging transactions being committed by {}", committer);

    stagingTransactions = new ArrayList<>();
    stagingChangeTime = System.currentTimeMillis();
    opCount = 0;
  }

  public synchronized boolean addTransaction(KurmaTransaction txn) {
    if (txn.isEmpty()) {
      return true;
    }
    Preconditions.checkArgument(txn.isCommited());
    if (!stagingTransactions.add(txn)) {
      return false;
    }
    stagingChangeTime = System.currentTimeMillis();
    opCount += txn.getOperations().size();
    if (opCount >= commitThreshold)
      commitStagingTxns();
    return true;
  }

  public synchronized void finishCommit(TransactionCommitter committer) {
    LOGGER.debug("committer finished");
    for (TransactionCommitter dependant : committer.getDependants()) {
      dependant.satifyDependency();
    }
    for (String p : committer.getZpaths()) {
      LinkedList<TransactionCommitter> dependants = committingZpaths.get(p);
      dependants.remove(committer);
      if (dependants.isEmpty()) {
        committingZpaths.remove(p);
      }
    }
    // TODO deal with the results
    commitResults.remove(committer.getId());
    LOGGER.debug("Finish a commit; {} left", commitResults.size());
  }

  public void startPeriodicCommiter() {
    tc = new PeriodicTransactionCommiter(this, commitInterval);
    tc.startCleaner();
  }

  public void stop() {
    tc.stopCleaner();
  }
}