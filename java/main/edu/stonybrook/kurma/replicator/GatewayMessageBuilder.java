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

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.DirectoryHelper;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.message.OperationType;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.VolumeInfo;

public class GatewayMessageBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMessageBuilder.class);

  private AtomicLong sequenceNumber = new AtomicLong(0);
  private IGatewayConfig local;

  public GatewayMessageBuilder(IGatewayConfig localGateway) {
    local = localGateway;
  }

  private GatewayMessage newMessage(OperationType ot) {
    GatewayMessage gm = new GatewayMessage();
    gm.setOp_type(ot);
    gm.setSeq_number(sequenceNumber.getAndIncrement());
    gm.setTimestamp(System.currentTimeMillis());
    gm.setGwid(local.getGatewayId());
    return gm;
  }

  public GatewayMessage buildVolumeCreation(VolumeInfo vi) {
    GatewayMessage gm = newMessage(OperationType.CREATE_VOLUME);
    gm.setOp(KurmaOperations.newVolumeCreation(vi));
    return gm;
  }

  public GatewayMessage buildDirCreation(String volumeId, Directory dir) {
    GatewayMessage gm = newMessage(OperationType.CREATE_DIR);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newDirCreation(dir));
    return gm;
  }

  public GatewayMessage buildFileCreation(String volumeId, File file, SecretKey fileKey, KeyMap keymap) {
    File filecopy = file.deepCopy();

    try {
      if (local.useKeyMap()) {
        filecopy.unsetKey();
        if (keymap == null) {
          keymap = KeyMapHelper.newKeyMap(fileKey, local);
        }
      } else {
        filecopy.setKey(fileKey.getEncoded());
      }
    } catch (GeneralSecurityException e) {
      e.printStackTrace();
      return null;
    }
    GatewayMessage gm = newMessage(OperationType.CREATE_FILE);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newFileCreation(filecopy, keymap));
    return gm;
  }

  public GatewayMessage buildFileUpdate(String volumeId, ObjectID oid, long offset, long length,
      List<Long> newVersions, ObjectAttributes attrs) {
    GatewayMessage gm = newMessage(OperationType.UPDATE_FILE);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newFileUpdate(oid, offset, length, newVersions, attrs));
    return gm;
  }

  public GatewayMessage buildAttributeSet(String volumeId, ObjectID oid, ObjectAttributes oldAttrs,
      ObjectAttributes newAttrs) {
    GatewayMessage gm = newMessage(OperationType.SET_ATTRIBUTES);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newAttributeSet(oid, oldAttrs, newAttrs));
    return gm;
  }

  public GatewayMessage buildRename(String volumeId, ObjectID srcDirOid, ObjectID srcOid,
      String srcName, ObjectID dstDirOid, String dstName) {
    // Check for file name and remove suffix created at the time of conflict
    // resolution, if any
    if (DirectoryHelper.isSuffixedName(srcName)) {
      srcName = DirectoryHelper.getNameFromSuffixedName(srcName);
    }

    // Check for file name and remove suffix created at the time of conflict
    // resolution, if any
    if (DirectoryHelper.isSuffixedName(dstName)) {
      dstName = DirectoryHelper.getNameFromSuffixedName(dstName);
    }

    GatewayMessage gm = newMessage(OperationType.RENAME);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newRename(srcDirOid, srcOid, srcName, dstDirOid, dstName));
    return gm;
  }

  public GatewayMessage buildFileRemoval(String volumeId, ObjectID dirOid, ObjectID oid,
      String name) {
    // Check for file name and remove suffix created at the time of conflict
    // resolution, if any
    if (DirectoryHelper.isSuffixedName(name)) {
      name = DirectoryHelper.getNameFromSuffixedName(name);
    }

    GatewayMessage gm = newMessage(OperationType.UNLINK_FILE);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newFileRemoval(dirOid, oid, name));
    return gm;
  }

  public GatewayMessage buildDirRemoval(String volumeId, ObjectID dirOid, ObjectID oid,
      String name) {
    // Check for file name and remove suffix created at the time of conflict
    // resolution, if any
    if (DirectoryHelper.isSuffixedName(name)) {
      name = DirectoryHelper.getNameFromSuffixedName(name);
    }

    GatewayMessage gm = newMessage(OperationType.REMOVE_DIR);
    gm.setVolumeid(volumeId);
    gm.setOp(KurmaOperations.newDirRemoval(dirOid, oid, name));
    return gm;
  }
}
