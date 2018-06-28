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
package edu.stonybrook.kurma.helpers;

import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.ObjectID;

public class FileHelper {
  public static File newFile(ObjectID oid) {
    File file = new File();
    file.setOid(oid);
    file.setBlock_map_count(0);
    return file;
  }
}
