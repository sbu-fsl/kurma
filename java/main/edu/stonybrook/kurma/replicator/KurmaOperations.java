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

import java.util.List;

import edu.stonybrook.kurma.message.CreateDir;
import edu.stonybrook.kurma.message.CreateFile;
import edu.stonybrook.kurma.message.CreateVolume;
import edu.stonybrook.kurma.message.KurmaOperation;
import edu.stonybrook.kurma.message.RemoveDir;
import edu.stonybrook.kurma.message.Rename;
import edu.stonybrook.kurma.message.SetAttributes;
import edu.stonybrook.kurma.message.UnlinkFile;
import edu.stonybrook.kurma.message.UpdateFile;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.VolumeInfo;

public class KurmaOperations {
  public static KurmaOperation newOperation(UpdateFile uf) {
    KurmaOperation ko = new KurmaOperation();
    ko.setUpdate_file(uf);
    return ko;
  }

  public static KurmaOperation newVolumeCreation(VolumeInfo vi) {
    CreateVolume cv = new CreateVolume();
    cv.setVolume(vi);
    KurmaOperation ko = new KurmaOperation();
    ko.setCreate_volume(cv);
    return ko;
  }

  public static KurmaOperation newFileCreation(File file, KeyMap keymap) {
    CreateFile cf = new CreateFile();
    cf.setFile(file);
    if (keymap != null) {
      cf.setKeymap(keymap);
    } else {
      cf.unsetKeymap();
    }
    KurmaOperation ko = new KurmaOperation();
    ko.setCreate_file(cf);
    return ko;
  }

  public static KurmaOperation newDirCreation(Directory dir) {
    CreateDir cd = new CreateDir();
    cd.setDir(dir);
    KurmaOperation ko = new KurmaOperation();
    ko.setCreate_dir(cd);
    return ko;
  }

  public static KurmaOperation newFileUpdate(ObjectID oid, long offset, long length,
      List<Long> newVersions, ObjectAttributes attrs) {
    UpdateFile uf = new UpdateFile();
    uf.setFile_oid(oid);
    uf.setOffset(offset);
    uf.setLength(length);
    uf.setNew_versions(newVersions);
    uf.setNew_attrs(attrs);
    KurmaOperation ko = new KurmaOperation();
    ko.setUpdate_file(uf);
    return ko;
  }

  public static KurmaOperation newAttributeSet(ObjectID oid, ObjectAttributes oldAttrs,
      ObjectAttributes newAttrs) {
    SetAttributes sa = new SetAttributes();
    sa.setOid(oid);
    sa.setOld_attrs(oldAttrs);
    sa.setNew_attrs(newAttrs);
    KurmaOperation ko = new KurmaOperation();
    ko.setSet_attrs(sa);
    return ko;
  }

  public static KurmaOperation newRename(ObjectID srcDirOid, ObjectID srcOid, String srcName,
      ObjectID dstDirOid, String dstName) {
    Rename rn = new Rename();
    rn.setSrc_dir_oid(srcDirOid);
    rn.setSrc_oid(srcOid);
    rn.setSrc_name(srcName);
    rn.setDst_dir_oid(dstDirOid);
    rn.setDst_name(dstName);
    KurmaOperation ko = new KurmaOperation();
    ko.setRename(rn);
    return ko;
  }

  public static KurmaOperation newFileRemoval(ObjectID dirOid, ObjectID oid, String name) {
    UnlinkFile uf = new UnlinkFile();
    uf.setParent_oid(dirOid);
    uf.setOid(oid);
    uf.setName(name);
    KurmaOperation ko = new KurmaOperation();
    ko.setUnlink_file(uf);
    return ko;
  }

  public static KurmaOperation newDirRemoval(ObjectID dirOid, ObjectID oid, String name) {
    RemoveDir rd = new RemoveDir();
    rd.setParent_oid(dirOid);
    rd.setOid(oid);
    rd.setName(name);
    KurmaOperation ko = new KurmaOperation();
    ko.setRemove_dir(rd);
    return ko;
  }
}
