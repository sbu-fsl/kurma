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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.hedwig.client.HedwigClient;
import org.apache.hedwig.client.api.MessageHandler;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.fs.KurmaService;
import edu.stonybrook.kurma.gc.DefaultCollector;
import edu.stonybrook.kurma.gc.GarbageCollector;
import edu.stonybrook.kurma.replicator.ConflictResolver;
import edu.stonybrook.kurma.replicator.GatewayMessageFilter;
import edu.stonybrook.kurma.replicator.GatewayMessageHandler;
import edu.stonybrook.kurma.replicator.IReplicator;
import edu.stonybrook.kurma.replicator.KurmaReplicator;
import edu.stonybrook.kurma.replicator.NullReplicator;

public class KurmaServer {
  private final static int SESSION_RECLAIM_INTERVAL = 5000;

  private final static Logger LOGGER = LoggerFactory.getLogger(KurmaServer.class);

  public static KurmaServiceHandler handler;

  public static KurmaService.Processor<KurmaServiceHandler> processor;

  public static TestingServer server;

  public static CuratorFramework buildClient(String connectString) {
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework client = CuratorFrameworkFactory.builder().connectString(connectString)
        .retryPolicy(retryPolicy).build();
    client.start();
    return client;
  }

  public static void main(String[] args) {
    String connectString = "0.0.0.0:2181";
    if (args.length == 0) {
      // use local test server
      try {
        server = new TestingServer();
        server.start();
        connectString = String.format("0.0.0.0:%d", server.getPort());
      } catch (Exception e) {
        e.printStackTrace();
        System.exit(1);
      }
    } else {
      connectString = args[0];
    }
    IGatewayConfig config = null;
    try {
      config = new GatewayConfig(GatewayConfig.KURMA_DEFAULT_CONFIG_FILE);
      CuratorFramework client = buildClient(connectString);
      GarbageCollector gc = new DefaultCollector(client, config);
      ConflictResolver conflictResolver = new ConflictResolver(config);
      KurmaHandler kh = new KurmaHandler(client, gc, config, conflictResolver);
      SessionManager sm = new SessionManager(kh, SESSION_RECLAIM_INTERVAL);
      IReplicator replicator = null;
      if (config.getHedwigConfig() == null) {
        replicator = new NullReplicator("");
      } else {
        MessageHandler mh = new GatewayMessageHandler(kh, config.getReplicationThreads());
        HedwigClient hc = new HedwigClient(config.getHedwigConfig());
        GatewayMessageFilter gmf = new GatewayMessageFilter(config.getLocalGateway().getId());
        replicator = new KurmaReplicator(config, hc, mh, gmf);
      }
      handler = new KurmaServiceHandler(kh, sm, config, replicator);

      processor = new KurmaService.Processor<KurmaServiceHandler>(handler);
    } catch (Exception e) {
      LOGGER.error("failed to start Kurma service", e);
      System.exit(1);
    }

    // final int maxWorkerThreads = config.getMaxServerThreads();
    // final int requestTimeoutSeconds = config.getRequestTimeoutSeconds();

    Thread serverThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          TServerTransport serverTransport = new TServerSocket(9091);
          TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport);
          // args = args.maxWorkerThreads(maxWorkerThreads);
          // args = args.requestTimeoutUnit(TimeUnit.SECONDS);
          // args = args.requestTimeout(requestTimeoutSeconds);

          TServer server = new TThreadPoolServer(args.processor(processor));
          server.serve();
        } catch (TTransportException e) {
          LOGGER.error("failed to start ThriftServer", e);
        }
      }
    });

    serverThread.start();

    final String statFile = config.getTimeStatsFile();
    Thread statThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          Files.createFile(Paths.get(statFile));
          LOGGER.info("writing time statistics to new file: {}", statFile);
        } catch (FileAlreadyExistsException e) {
          LOGGER.info("writing time statistics to existing file: {}", statFile);
        } catch (IOException e) {
          LOGGER.error(String.format("failed to create file %s", statFile), e);
        }
        while (true) {
          try {
            Thread.sleep(5 * 1000);
          } catch (InterruptedException e) {
            LOGGER.info(e.getMessage());
          }
          String stats = String.format("%d\t%d\t%d\t%d\t%d\t%d\n",
              KurmaServiceHandler.openTime.get(), KurmaServiceHandler.closeTime.get(),
              KurmaServiceHandler.createTime.get(), KurmaServiceHandler.lookupTime.get(),
              KurmaServiceHandler.readTime.get(), KurmaServiceHandler.writeTime.get());
          try {
            Files.write(Paths.get("/tmp/kurma-time-stats.txt"), stats.getBytes(),
                StandardOpenOption.APPEND);
          } catch (IOException e) {
            LOGGER.error("failed to dump time statistics", e);
          }
        }
      }
    });
    statThread.start();
  }

}
