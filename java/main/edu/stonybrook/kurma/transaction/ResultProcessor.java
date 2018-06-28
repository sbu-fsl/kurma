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

import java.util.Iterator;
import java.util.List;

import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;

import edu.stonybrook.kurma.KurmaException.CuratorException;

public interface ResultProcessor {
  public abstract void process(Object ctx, Iterator<CuratorTransactionResult> it)
      throws CuratorException;

  public static void commit(CuratorTransactionFinal txn, List<ResultProcessor> processors)
      throws CuratorException {
    Iterator<CuratorTransactionResult> results = null;
    try {
      txn.check();
      results = txn.commit().iterator();
      for (ResultProcessor p : processors) {
        if (p != null) {
          p.process(null, results);
        }
      }
    } catch (Exception e) {
      throw new CuratorException("curator transaction failed", e);
    }
  }
}
