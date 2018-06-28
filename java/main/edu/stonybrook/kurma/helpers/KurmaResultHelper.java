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

import edu.stonybrook.kurma.fs.KurmaResult;

public class KurmaResultHelper {
  public static KurmaResult newResult() {
    KurmaResult kr = new KurmaResult();
    kr.setStatus(StatusHelper.newStatus());
    return kr;
  }
}
