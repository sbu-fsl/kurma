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

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constructor takes interval in seconds time unit
 * There is an initial delay of 10 seconds before the first schedule
 *
 * @author rushabh
 *
 */
public class PeriodicTransactionCommiter {
  private static final Logger LOGGER = LoggerFactory.getLogger(PeriodicTransactionCommiter.class);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private ScheduledFuture<?> cleanerHandle;
  private KurmaTransactionManager manager;
  private int period;

  public PeriodicTransactionCommiter(KurmaTransactionManager manager, int period) {
    this.manager = manager;
    this.period = period;
  }

  public void startCleaner() {
    final Runnable cleaner = new Runnable() {
      @Override
      public void run() {
        LOGGER.trace("Commiting");
        try {
          manager.commitStagingTxnsIfNeeded();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    // there is an initial delay of 10 seconds
    cleanerHandle = scheduler.scheduleAtFixedRate(cleaner, period, period, SECONDS);
  }

  public void stopCleaner() {
    scheduler.schedule(new Runnable() {
      @Override
      public void run() {
        cleanerHandle.cancel(true);
      }
    }, 0, SECONDS);
    LOGGER.info("Stopped Commiter");
  }
}
