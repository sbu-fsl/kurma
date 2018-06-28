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
package edu.stonybrook.kurma.bench;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.caliper.api.VmOptions;

import edu.stonybrook.kurma.util.RandomBuffer;

@VmOptions("-XX:-TieredCompilation")
public class TransactionOperationBenchMark {
  static final int RANDOM_SEED = 8839;

  @Param({"5", "10", "15", "20"})
  int n;

  RandomBuffer randomBuffer;
  byte data[];
  RetryPolicy retryPolicy;
  TestingServer server;

  @BeforeExperiment
  public void startExp() throws Exception {
    randomBuffer = new RandomBuffer(RANDOM_SEED);
    data = randomBuffer.genRandomBytes(64);
    retryPolicy = new ExponentialBackoffRetry(1000, 3);
    server = new TestingServer();
    server.start();
  }

  @AfterExperiment
  public void endExp() throws Exception {
    server.close();
  }

  @Benchmark
  public void transactionCommitCreateDelete(int reps) throws Exception {
    for (int j = 0; j < reps; j++) {
      CuratorFramework client;
      client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      CuratorTransaction transaction = client.inTransaction();
      for (int i = 0; i < n; i++) {
        transaction = transaction.create().forPath("/CommitFile" + i, "some data".getBytes()).and()
            .delete().forPath("/CommitFile" + i).and();
      }
      ((CuratorTransactionFinal) transaction).commit();
      client.close();
    }
  }

  @Benchmark
  public void transactionCommitCreatesThenDeletes(int reps) throws Exception {
    for (int j = 0; j < reps; j++) {
      CuratorFramework client;
      client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      CuratorTransaction transaction = client.inTransaction();
      for (int i = 0; i < n; i++) {
        transaction = transaction.create().forPath("/CommitFile" + i, "some data".getBytes()).and();
      }
      ((CuratorTransactionFinal) transaction).commit();

      transaction = client.inTransaction();
      for (int i = 0; i < n; i++) {
        transaction = transaction.delete().forPath("/CommitFile" + i).and();
      }
      ((CuratorTransactionFinal) transaction).commit();
      client.close();
    }
  }

  @Benchmark
  public void singleCreateDelete(int reps) throws Exception {
    for (int j = 0; j < reps; j++) {
      CuratorFramework client;
      client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      for (int i = 0; i < n; i++) {
        client.create().forPath("/onefile" + i, data);
        client.delete().forPath("/onefile" + i);
      }
      client.close();
    }
  }

  @Benchmark
  public void singleCreatesThenDeletes(int reps) throws Exception {
    for (int j = 0; j < reps; j++) {
      CuratorFramework client;
      client = CuratorFrameworkFactory.newClient(getConnectString(server), retryPolicy);
      client.start();
      for (int i = 0; i < n; i++) {
        client.create().forPath("/onefile" + i, data);
      }
      for (int i = 0; i < n; i++) {
        client.delete().forPath("/onefile" + i);
      }
      client.close();
    }
  }

  public static void main(String[] args) {
    String[] myargs = new String[5];
    myargs[0] = TransactionOperationBenchMark.class.getName();
    myargs[1] = "-i";
    myargs[2] = "runtime";
    myargs[3] = "-t";
    myargs[4] = "20";
    com.google.caliper.runner.CaliperMain.main(myargs);
  }

  public static String getConnectString(TestingServer server) {
    return String.format("localhost:%d", server.getPort());
  }
}

