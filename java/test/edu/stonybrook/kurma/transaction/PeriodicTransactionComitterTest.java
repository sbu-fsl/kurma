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

import org.junit.Test;
import org.mockito.Mockito;

public class PeriodicTransactionComitterTest {
  
  @Test
  public void testCountAsManyScheduled() throws InterruptedException {
    KurmaTransactionManager manager = Mockito.mock(KurmaTransactionManager.class);
    Mockito.doNothing().when(manager).commitStagingTxnsIfNeeded();
    PeriodicTransactionCommiter committer = new PeriodicTransactionCommiter(manager, 1);
    
    committer.startCleaner();
    Thread.sleep(10000);
    committer.stopCleaner();
    
    Mockito.verify(manager, Mockito.atLeast(9)).commitStagingTxnsIfNeeded();
    Mockito.verify(manager, Mockito.atMost(11)).commitStagingTxnsIfNeeded();
    
    Thread.sleep(10000);
    Mockito.verifyNoMoreInteractions(manager);
  }
}
