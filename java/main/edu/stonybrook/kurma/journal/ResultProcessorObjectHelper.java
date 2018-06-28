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

import java.util.Iterator;

import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.transaction.ResultProcessor;

public class ResultProcessorObjectHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(ResultProcessorObjectHelper.class);
    public static ResultProcessor create(TWrapper<?> wrapper, String message) {
      return new ResultProcessor() {
        @Override
        public void process(Object ctx, Iterator<CuratorTransactionResult> it) throws CuratorException {
          LOGGER.info(message+" - {}", this.toString());
          wrapper.setExist(true);
          wrapper.setDirty(false);
        }
      };
    }

    public static ResultProcessor delete(TWrapper<?> wrapper, String message) {
      return new ResultProcessor() {
        @Override
        public void process(Object ctx, Iterator<CuratorTransactionResult> it) throws CuratorException {
          LOGGER.info(message+" - {}", this.toString());
          wrapper.setExist(false);
          wrapper.setDirty(false);
        }
      };
    }

    public static ResultProcessor update(TWrapper<?> wrapper, String message) {
      return new ResultProcessor() {
        @Override
        public void process(Object ctx, Iterator<CuratorTransactionResult> it) throws CuratorException {
          LOGGER.info(message + "- {}", this.toString());
          //TODO check how to set Stat zkStat
          wrapper.setDirty(false);
          wrapper.setExist(true);
        }
      };
    }

    public static ResultProcessor noop(String message) {
      return new ResultProcessor() {
        @Override
        public void process(Object ctx, Iterator<CuratorTransactionResult> it) throws CuratorException {
          LOGGER.info("noop result processor: {}", message);
        }
      };
    }
}
