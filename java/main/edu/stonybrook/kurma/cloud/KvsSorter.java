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
package edu.stonybrook.kurma.cloud;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KvsSorter {
  private static final Logger LOGGER = LoggerFactory.getLogger(KvsSorter.class);

  private final int k;
  private final List<Kvs> originalKvsList;
  private final int refreshSeconds;

  private long lastUpdateTime;
  private AtomicReference<Entry<List<Kvs>, int[]>> kvsListAndErasures;

  public static int kvsSortPeriodSec = 5;

  public KvsSorter(final List<Kvs> kvsList, int k, int refreshSeconds) {
    this.originalKvsList = kvsList;
    this.k = k;
    this.refreshSeconds = refreshSeconds;
    this.lastUpdateTime = System.currentTimeMillis();
    int[] erasures = new int[k];
    for (int i = 0; i < k; ++i) {
      erasures[i] = i;
    }
    kvsListAndErasures = new AtomicReference<>(new AbstractMap.SimpleEntry<>(kvsList, erasures));
  }

  public Entry<List<Kvs>, int[]> getKvsListAndErasures() {
    LOGGER.info("KvsSorted Used");
    long now = System.currentTimeMillis();
    if (now - lastUpdateTime <= refreshSeconds * 1000L) {
      return kvsListAndErasures.get();
    }
    int[] erasures = new int[k];
    List<Kvs> sortedKvsList = new ArrayList<Kvs>(originalKvsList);
    Collections.sort(sortedKvsList, Kvs.COMPARATOR_BY_READS);
    LOGGER.info("kvs sorted {}", sortedKvsList);
    for (int i = 0; i < k; ++i) {
      erasures[i] = originalKvsList.indexOf(sortedKvsList.get(i));
    }
    Entry<List<Kvs>, int[]> newKvsListAndErasures =
        new AbstractMap.SimpleEntry<>(sortedKvsList, erasures);
    kvsListAndErasures.set(newKvsListAndErasures);
    lastUpdateTime = now;
    return newKvsListAndErasures;
  }

}
