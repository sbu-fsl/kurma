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
package edu.stonybrook.kurma.replicator;

import edu.stonybrook.kurma.message.CreateDir;
import edu.stonybrook.kurma.message.CreateFile;
import edu.stonybrook.kurma.message.RemoveDir;
import edu.stonybrook.kurma.message.Rename;
import edu.stonybrook.kurma.message.SetAttributes;
import edu.stonybrook.kurma.message.UnlinkFile;
import edu.stonybrook.kurma.message.UpdateFile;
import edu.stonybrook.kurma.server.VolumeHandler;

public interface IConflictResolver {
  public boolean resolveIfAnyUpdateFileConflict(VolumeHandler vh, UpdateFile uf, short remoteGwid)
      throws Exception;

  public boolean resolveIfAnyCreateDirConflict(VolumeHandler vh, short gwid, CreateDir cd);

  public boolean resolveIfAnyCreateFileConflict(VolumeHandler vh, short gwid, CreateFile cf);

  public boolean resolveIfAnyUnlinkFileConflict(VolumeHandler vh, UnlinkFile uf);

  public boolean resolveIfAnyRemoveDirectoryConflict(VolumeHandler vh, RemoveDir rd);

  public boolean resolveIfAnyRenameConflict(VolumeHandler vh, Rename rn);

  public boolean resolveIfAnySetAttrConflict(VolumeHandler vh, SetAttributes sa);

  public boolean ignoreMissingDependency();
}
