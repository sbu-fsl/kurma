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
package edu.stonybrook.kurma.gc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.server.FileHandler;

public class FileCollector extends AbstractWorker<FileHandler> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FileCollector.class);

  @Override
  public boolean clean(FileHandler fh) {
    if (!fh.isLoaded()) {
      boolean res = fh.load();
      if (!res) {
        LOGGER.warn("could not load deleted filehandler, will retry later");
      }
    }
    return fh.delete();
  }
}
