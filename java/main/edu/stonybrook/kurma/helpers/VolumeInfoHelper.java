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

import edu.stonybrook.kurma.meta.VolumeInfo;

public class VolumeInfoHelper {
  public static VolumeInfo newVolumeInfo(String volumeId) {
    VolumeInfo vi = new VolumeInfo();
    vi.setId(volumeId);
    return vi;
  }
}
