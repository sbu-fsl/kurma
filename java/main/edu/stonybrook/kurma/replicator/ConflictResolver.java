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

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.io.BaseEncoding;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.DirectoryHelper;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.message.CreateDir;
import edu.stonybrook.kurma.message.CreateFile;
import edu.stonybrook.kurma.message.RemoveDir;
import edu.stonybrook.kurma.message.Rename;
import edu.stonybrook.kurma.message.SetAttributes;
import edu.stonybrook.kurma.message.UnlinkFile;
import edu.stonybrook.kurma.message.UpdateFile;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.FileHandler;
import edu.stonybrook.kurma.server.VolumeHandler;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;
import edu.stonybrook.kurma.util.FileUtils;

/*
 * Used for conflict resolution It implements different policy based on type of conflict
 */
public class ConflictResolver implements IConflictResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConflictResolver.class);

  private IGatewayConfig config;
  private static int updateFileConflictCount = 0;
  private static int createFileConflictCount = 0;
  private static int createDirConflictCount = 0;
  private static int conflictsResolved = 0;
  private boolean updateConflictFound = false;
  private boolean updateConflictResolved = false;

  public ConflictResolver(IGatewayConfig config) {
    this.config = config;
  }

  @Override
  public boolean resolveIfAnyUpdateFileConflict(VolumeHandler vh, UpdateFile uf, short remoteGwid)
      throws Exception {
    Iterator<Long> remoteVersionsIterator = uf.getNew_versionsIterator();
    boolean res = false;
    ObjectID oid = uf.getFile_oid();
    FileHandler fh = vh.getLoadedFile(oid);

    if (fh == null) {
      LOGGER.warn("File to be updated doesn't exists");
      return ignoreMissingDependency();
    }
    LOGGER.debug("file size is {}", fh.getFileSize());
    /* The caller of this lambda function holds the file and range lock */
    res = fh.iterBlockForConflictResolve(uf.getOffset(), uf.getLength(), uf.getNew_attrs(),
        (localFileBlock, gwid) -> {
          long localBlockVersion = localFileBlock.getVersion();
          long remoteBlockVersion = 0;
          if (remoteVersionsIterator.hasNext()) {
            remoteBlockVersion = remoteVersionsIterator.next().intValue();
          } else {
            return false;
          }
          long offset = localFileBlock.getOffset();

          /*
           * Conflict will NOT occur only if remote block version is higher and modifier gateway is
           * same
           */
          if (!((remoteBlockVersion > localBlockVersion) && (gwid == remoteGwid))) {
            updateConflictFound = true;
            FileBlock remoteFileBlock =
                new FileBlock(fh, offset, localFileBlock.getLength(), remoteBlockVersion,
                    remoteGwid, ByteBuffer.wrap(new byte[localFileBlock.getLength()]), false);
            /* Load block data from cloud to get its time stamp */
            fh._loadBlockData(localFileBlock);
            fh._loadBlockData(remoteFileBlock);

            short localGatewayId = localFileBlock.getGateway();
            long localBlockTimestamp = localFileBlock.getTimestamp();
            long remoteBlockTimestamp = remoteFileBlock.getTimestamp();
            short remoteGatewayId = remoteFileBlock.getGateway();

            /*
             * Select file block version which has latest time stamp, in case of same time stamp
             * choose block for higher gateway number
             */
            if ((localBlockTimestamp < remoteBlockTimestamp)
                || (localBlockTimestamp == remoteBlockTimestamp
                    && localGatewayId < remoteGatewayId)) {
              updateConflictResolved = fh._resolveBlockVersions(offset, localFileBlock.getLength(),
                  remoteGwid, remoteBlockVersion, uf.getNew_attrs());
              LOGGER.info("=======UPDATED BLOCK VERSION=====");
              if (!updateConflictResolved) {
                return false;
              }
            } else {
              LOGGER.info("=======NO NEED TO UPDATE BLOCK VERSION=====");
              updateConflictResolved = true;
            }
          } else {
            fh._resolveBlockVersions(offset, localFileBlock.getLength(), remoteGwid,
                remoteBlockVersion, uf.getNew_attrs());
          }
          return true;
        });
    if (updateConflictFound) {
      updateFileConflictCount++;
    }
    if (updateConflictResolved) {
      conflictsResolved++;
    }
    vh.putFile(fh);
    return res;
  }

  @Override
  public boolean resolveIfAnyCreateDirConflict(VolumeHandler vh, short gwid, CreateDir cd) {
    Directory dir = cd.getDir();
    Preconditions.checkArgument(dir.isSetParent_oid());
    Preconditions.checkArgument(dir.isSetOid());
    Preconditions.checkArgument(dir.isSetAttrs());
    Preconditions.checkArgument(dir.isSetName());

    DirectoryHandler parent = vh.getDirectoryHandler(dir.getParent_oid());
    if (parent == null) {
      LOGGER.warn("parent directory not exist");
      return ignoreMissingDependency();
    }

    /*
     * Creates child directory with suffix name in case of conflict is found
     */
    DirectoryHandler dh =
        parent.createChildDirectory(dir.getOid(), dir.getName(), dir.getAttrs(), false);
    if (dh == null) {
      LOGGER.error("could not create dir: {}", dir.getOid());
      // TODO Should we return true?
      return false;
    }

    // TODO How to ensure entire operation in this function is sync
    /*
     * Check if create file conflict found or not and add hints for suffix file name
     */
    if (DirectoryHelper.isSuffixedName(dh.getName())) {
      // Add hint in this dir attr, so that we can use its original name
      // while replicating its changes
      AttributesHelper.setSuffixNameBit(dh.getAttrsRef());
      // We assume that if conflict found then it will be resolved
      createDirConflictCount++;
      conflictsResolved++;
    }
    return true;
  }

  @Override
  public boolean resolveIfAnyCreateFileConflict(VolumeHandler vh, short gwid, CreateFile cf) {
    File file = cf.getFile();
    Preconditions.checkArgument(file.isSetParent_oid());
    Preconditions.checkArgument(file.isSetOid());
    Preconditions.checkArgument(file.isSetName());
    Preconditions.checkArgument(file.isSetAttrs());

    LOGGER.info("Create file message received at Gateway-{}: ObjectID = {}",
        vh.getConfig().getLocalGateway().getName(), file.getOid());
    DirectoryHandler parent = vh.getDirectoryHandler(file.getParent_oid());
    if (parent == null) {
      LOGGER.warn("parent directory not exist");
      return ignoreMissingDependency();
    }

    SecretKey fileKey = null;
    try {
      if (config.useKeyMap()) {
        KeyMap keyMap = cf.getKeymap();
        fileKey = KeyMapHelper.readKeyMap(keyMap, config);
      } else {
        fileKey =
            new SecretKeySpec(cf.getFile().getKey(), EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
      }
    } catch (Exception e) {
      LOGGER.error("Unable to read filekey from keymap" + e);
      // TODO Should we return true?
      return false;
    }
    LOGGER.debug("recovered file key: ", BaseEncoding.base64Url().encode(fileKey.getEncoded()));

    /* Creates child file with suffix name in case of conflict is found */
    FileHandler fh = parent.createChildFile(file.getOid(), file.getName(), file.getAttrs(),
        FileUtils.getFileKvsFacade(file, vh.getFacadeManager()), fileKey, cf.getKeymap(), false);
    if (fh == null) {
      LOGGER.error("could not create file: {}", file.getOid());
      // TODO Should we return true?
      return false;
    }

    vh.invalidateNegativeObject(file.getParent_oid(), file.getName());
    /*
     * Check if create file conflict found or not and add hints for suffix file name
     */
    if (DirectoryHelper.isSuffixedName(fh.getFile().getName())) {
      // Add hint in this file's attr, so that we can use its original
      // name while replicating its changes
      AttributesHelper.setSuffixNameBit(fh.getFile().getAttrs());
      // We assume that if conflict found then it will be resolved
      createFileConflictCount++;
      conflictsResolved++;
    }
    return true;
  }

  @Override
  public boolean resolveIfAnyUnlinkFileConflict(VolumeHandler vh, UnlinkFile uf) {
    ObjectID parentOid = uf.getParent_oid();
    String fileName = uf.getName();
    ObjectID oid = uf.getOid();
    DirectoryHandler dh = vh.getDirectoryHandler(parentOid);

    DirEntry dentry = dh.removeChild(fileName, Optional.of(oid), false);

    if (dentry != null) {
      // successful deletion of file
      return true;
    } else {
      // file maybe deleted or renamed locally
      FileHandler fh = vh.getLoadedFile(oid);
      if (fh == null) {
        // local deletion
        return true;
      } else {
        // local rename, no replication required for this file from now
        // on
        AttributesHelper.setNoReplicationBit(fh.getAttrsRef());
        LOGGER.error("file {} not deleted, no-replciation bit set for it", fileName);
        vh.putFile(fh);
        return true;
      }
    }
  }

  @Override
  public boolean resolveIfAnyRemoveDirectoryConflict(VolumeHandler vh, RemoveDir rd) {
    ObjectID parentOid = rd.getParent_oid();
    String name = rd.getName();
    ObjectID oid = rd.getOid();

    DirectoryHandler parent = vh.getDirectoryHandler(parentOid);
    if (parent == null) {
      LOGGER.warn("parent directory not exist");
      return ignoreMissingDependency();
    }

    DirEntry dentry = parent.removeChild(name, Optional.of(oid), false);

    if (dentry != null) {
      // successful deletion of directory
      return true;
    } else {
      // directory maybe deleted or renamed locally
      DirectoryHandler dh = vh.getDirectoryHandler(oid);
      if (dh == null) {
        // local deletion
        return true;
      } else {
        // local rename, no replication required for this directory and
        // its children
        AttributesHelper.setNoReplicationBit(dh.getAttrsRef());
        // set no-replication bit for all of its children
        dh.setNoReplicationForChildren();
        LOGGER.error("Directory {} not deleted, no-replciation bit set for it", name);
        return true;
      }
    }

  }

  @Override
  public boolean resolveIfAnyRenameConflict(VolumeHandler vh, Rename rn) {
    ObjectID srcDirOid = rn.getSrc_dir_oid();
    String srcName = rn.getSrc_name();
    ObjectID dstDirOid = rn.getDst_dir_oid();
    String dstName = rn.getDst_name();

    DirectoryHandler srcDh = vh.getDirectoryHandler(srcDirOid);
    if (srcDh == null) {
      LOGGER.warn("source directory not found: {}", srcDirOid);
      return ignoreMissingDependency();
    }

    if (!srcDh.hasChildEntry(srcName, Optional.of(rn.getSrc_oid()))) {
      LOGGER.warn("cannot rename: source directory {} does not have child entry {} named {}",
          srcDirOid, rn.getSrc_oid(), srcName);
      return ignoreMissingDependency();
    }

    DirectoryHandler dstDh = vh.getDirectoryHandler(dstDirOid);
    if (dstDh == null) {
      LOGGER.warn("destination directory not found: {}", dstDirOid);
      return ignoreMissingDependency();
    }

    java.util.Map.Entry<ObjectID, Integer> entry =
        srcDh.renameChild(srcName, dstDh, dstName, false);
    if (entry.getValue() != 0) {
      LOGGER.error("could not rename {} to {}", srcName, dstName);
      return false;
    }

    ObjectID srcOid = entry.getKey();
    if (!srcOid.equals(rn.getSrc_oid())) {
      LOGGER.error("rename conflicts!");
      // TODO handle conflict and roll back
      return false;
    } else {
      return true;
    }
  }

  @Override
  public boolean resolveIfAnySetAttrConflict(VolumeHandler vh, SetAttributes sa) {
    ObjectID oid = sa.getOid();
    // Ignore setAttr if file/dir is already deleted
    if (ObjectIdHelper.isDirectory(oid)) {
      DirectoryHandler dh = vh.getDirectoryHandlerIfPresent(oid);
      if (dh != null) {
        dh.setAttrs(sa.getNew_attrs());
      }
    } else if (ObjectIdHelper.isFile(oid)) {
      FileHandler fh = vh.getLoadedFile(oid);
      if (fh != null) {
        fh.setAttrs(sa.getNew_attrs());
      } else {
        // TODO How to handle this?
        LOGGER.info("file doesn't exists. SetAttr will be skiped!");
      }
    } else {
      LOGGER.warn("ObjectID {} doesn't exists for setAttr", oid);
    }
    return true;

  }

  @Override
  public boolean ignoreMissingDependency() {
    return true;
  }

  // TODO Should we include this in interface?
  public int getUpdateFileConflictCount() {
    return updateFileConflictCount;
  }

  public int getCreateDirConflictCount() {
    return createDirConflictCount;
  }

  public int getCreateFileConflictCount() {
    return createFileConflictCount;
  }

  public int getConflictResolvedCount() {
    return conflictsResolved;
  }
}
