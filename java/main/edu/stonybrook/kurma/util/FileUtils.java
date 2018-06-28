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
package edu.stonybrook.kurma.util;

import edu.stonybrook.kurma.cloud.FacadeManager;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.meta.File;

public class FileUtils {
  public static KvsFacade getFileKvsFacade(final File file, FacadeManager manager) {
    if (file.isSetKvs_type()) {
      return manager.findOrBuild(file.getKvs_type(), file.getKvs_ids());
    } else {
      return manager.getDefaultFacade();
    }
  }
}
