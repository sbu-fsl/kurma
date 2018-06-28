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

import edu.stonybrook.kurma.server.VolumeHandler;

public class VolumeCollector extends AbstractWorker<VolumeHandler> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryCollector.class);

  @Override
  public boolean clean(VolumeHandler vh) {
    try {
      vh.delete();
    } catch (Exception e) {
      LOGGER.error("could not delete volume", e);
      e.printStackTrace();
    }
    return false;
  }

}
