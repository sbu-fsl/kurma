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

import static java.util.concurrent.TimeUnit.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import journal.io.api.Journal;
import journal.io.api.Location;

public class JournalCleaner {
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  ScheduledFuture<?> cleanerHandle;
  Journal journal;
  Location prevLocation = null;
  int time_in_seconds;

  public JournalCleaner(Journal journal, int time_in_seconds) {
    this.journal = journal;
    this.time_in_seconds = time_in_seconds;
  }

  public void startCleaner() {
    final Runnable cleaner = new Runnable() {
      public void run() {
        // System.out.println("Journal Cleaner");
        try {
          journal.compact();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    cleanerHandle = scheduler.scheduleAtFixedRate(cleaner, time_in_seconds, time_in_seconds, SECONDS);

  }

  public void stopCleaner() {
    scheduler.schedule(new Runnable() {
      public void run() {
        cleanerHandle.cancel(true);
      }
    }, 0, SECONDS);
    System.out.println("Stopped");
  }
}
