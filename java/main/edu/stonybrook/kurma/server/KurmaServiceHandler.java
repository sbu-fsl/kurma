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
package edu.stonybrook.kurma.server;

import static edu.stonybrook.kurma.util.LoggingUtils.hash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.fs.DynamicInfo;
import edu.stonybrook.kurma.fs.KurmaError;
import edu.stonybrook.kurma.fs.KurmaResult;
import edu.stonybrook.kurma.fs.KurmaService;
import edu.stonybrook.kurma.fs.LockRange;
import edu.stonybrook.kurma.fs.OpenFlags;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.DirectoryHelper;
import edu.stonybrook.kurma.helpers.KurmaResultHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.StatusHelper;
import edu.stonybrook.kurma.helpers.VolumeInfoHelper;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.VolumeInfo;
import edu.stonybrook.kurma.replicator.GatewayMessageBuilder;
import edu.stonybrook.kurma.replicator.IReplicator;
import edu.stonybrook.kurma.server.FileHandler.SnapshotInfo;
import edu.stonybrook.kurma.util.LoggingUtils;

public class KurmaServiceHandler implements KurmaService.Iface {
  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaServiceHandler.class);

  private KurmaHandler kurmaHandler;
  private SessionManager sessionManager;
  private final IGatewayConfig config;
  private final IReplicator replicator;
  private GatewayMessageBuilder messageBuilder;

  public static AtomicLong openTime = new AtomicLong();
  public static AtomicLong closeTime = new AtomicLong();
  public static AtomicLong lookupTime = new AtomicLong();
  public static AtomicLong createTime = new AtomicLong();
  public static AtomicLong readTime = new AtomicLong();
  public static AtomicLong writeTime = new AtomicLong();

  public KurmaServiceHandler(KurmaHandler kh, SessionManager sm, IGatewayConfig config,
      IReplicator replicator) {
    this.kurmaHandler = kh;
    this.sessionManager = sm;
    this.config = config;
    this.replicator = replicator;
    this.messageBuilder = new GatewayMessageBuilder(this.config);
  }

  @Override
  public KurmaResult format_volume(String volumeid, int flags) throws TException {
    LOGGER.info("creating volume '{}' with flags '{}'", volumeid, flags);
    VolumeInfo vi = VolumeInfoHelper.newVolumeInfo(volumeid);
    VolumeHandler vh = kurmaHandler.createVolume(vi);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (vh == null) {
      String errmsg = String.format("failed to format volume: %s", volumeid);
      LOGGER.error(errmsg);
      kr.status = StatusHelper.newStatus(KurmaError.ZOOKEEPER_ERROR, errmsg);
      return kr;
    }

    replicator.broadcast(messageBuilder.buildVolumeCreation(vi));

    kr.status = StatusHelper.OKAY;
    return kr;
  }

  @Override
  public KurmaResult create_session(ByteBuffer clientid, String volumeid) throws TException {
    LOGGER.info("creating session of {} for client {}", volumeid, hash(clientid));
    VolumeHandler vh = kurmaHandler.getVolumeHandler(volumeid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (vh == null) {
      kr.status.errcode = 1;
      kr.status.errmsg = "volume does not exist";
      return kr;
    }

    try {
      KurmaSession session = sessionManager.createSession(clientid, vh);
      LOGGER.info("session {} created for client {}", hash(session.getSessionId()),
          hash(clientid));
      kr.setSessionid(session.getSessionId());
      kr.setTimeout_sec(config.getSessionTimeout());
    } catch (Exception e) {
      kr.status = StatusHelper.serverError("could not create session for %s", hash(clientid));
      LOGGER.error(kr.status.getErrmsg(), e);
      return kr;
    }

    LOGGER.info("done creating session of {} for client {}", volumeid, hash(clientid));
    return kr;
  }

  @Override
  public KurmaResult renew_session(ByteBuffer sessionid) throws TException {
    LOGGER.info("renewing session {}", hash(sessionid));
    KurmaSession session = sessionManager.getSession(sessionid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }
    return kr;
  }

  @Override
  public KurmaResult open(ByteBuffer sessionid, ObjectID file_oid, OpenFlags flags)
      throws TException {
    LOGGER.info("opening file '{}' with flags {}", file_oid, flags);
    long start = System.currentTimeMillis();
    KurmaSession session = sessionManager.getSession(sessionid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    FileHandler fh = session.openFile(file_oid, flags);
    if (fh == null) {
      kr.status = StatusHelper.zkError("could not open file: %s", file_oid);
      LOGGER.error(kr.status.getErrmsg());
      return kr;
    }

    kr.setNew_attrs(fh.getAttrsCopy());
    kr.setOid(file_oid);
    openTime.addAndGet(System.currentTimeMillis() - start);
    LOGGER.info("done opening file '{}' with flags {}", file_oid, flags);
    return kr;
  }

  @Override
  public KurmaResult close(ByteBuffer sessionid, ObjectID file_oid) throws TException {
    LOGGER.info("closing file {}", file_oid);
    long start = System.currentTimeMillis();
    KurmaSession session = sessionManager.getSession(sessionid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    FileHandler fh = session.closeFile(file_oid);
    if (fh == null) {
      kr.status = StatusHelper.zkError("could not close file: %s", file_oid);
      return kr;
    }

    kr.setNew_attrs(fh.getAttrsCopy());
    kr.setOid(file_oid);
    closeTime.addAndGet(System.currentTimeMillis() - start);
    LOGGER.info("done closing file {}", file_oid);
    return kr;
  }

  private DirectoryHandler useDirectory(VolumeHandler vh, ObjectID dirOid, KurmaResult kr) {
    if (dirOid.id.id1 == 0 && dirOid.id.id2 == 0) {
      return vh.getRootDirectory();
    }

    if (!ObjectIdHelper.isDirectory(dirOid)) {
      kr.status = StatusHelper.invalidOid("not a directory ObjectID: %s", dirOid.toString());
      LOGGER.error(kr.status.getErrmsg());
      return null;
    }

    DirectoryHandler dh = vh.getDirectoryHandler(dirOid);
    if (dh == null) {
      kr.status = StatusHelper.notFound("directory not found: %s", dirOid.toString());
      return null;
    }

    return dh;
  }

  @Override
  public KurmaResult create(ByteBuffer sessionid, ObjectID dir_oid, String name,
      ObjectAttributes attrs) throws TException {
    LOGGER.info("creating file '{}' under '{}'", name, dir_oid);
    long start = System.currentTimeMillis();
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    /*
     * Prevent file/dir creation with special name, this name is used as a part of conflict
     * resolution
     */
    if (DirectoryHelper.isSuffixedName(name)) {
      String errmsg =
          String.format("Creation of  file or directory with name - %s is not allowed", name);
      kr.status = StatusHelper.newStatus(KurmaError.PERMISSION_DENIED, errmsg);
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    DirectoryHandler dh = useDirectory(vh, dir_oid, kr);
    if (dh == null) {
      kr.status = StatusHelper.notFound("parent directory not found", dir_oid);
      return kr;
    }

    FileHandler newFh = dh.createChildFile(name, attrs);
    if (newFh == null) {
      kr.status = StatusHelper.zkError("could not create file %s in %s", name, dh.getName());
      return kr;
    }

    vh.addFileHandler(newFh);
    vh.invalidateNegativeObject(dir_oid, name);

    LOGGER.info("file '{}' created under '{}' at Gateway-{}", name, dir_oid,
        vh.getConfig().getGatewayName());

    // Replicate only if no-replication bit is not set
    if (!AttributesHelper.isNoReplicationSet(dh.getAttrsRef())) {
      LOGGER.debug("broadcasting creation of {} at Gateway-{}", vh.getConfig().getGatewayName());
      replicator.broadcast(messageBuilder.buildFileCreation(vh.getVolumeId(), newFh.get(),
          newFh.getFileKey(), null));
    } else {
      LOGGER.debug("not broadcasting creation of {} at Gateway-{}", vh.getConfig().getGatewayName());
    }

    kr.setNew_attrs(newFh.getAttrsCopy());
    kr.setOid(newFh.getOid());

    LOGGER.info("file '{}' created with oid: '{}'", name, newFh.getOid());
    createTime.addAndGet(System.currentTimeMillis() - start);
    return kr;
  }

  @Override
  public KurmaResult mkdir(ByteBuffer sessionid, ObjectID dir_oid, String name,
      ObjectAttributes attrs) throws TException {
    LOGGER.info("creating new directory '{}' under '{}'", name, dir_oid);
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    DirectoryHandler dh = useDirectory(vh, dir_oid, kr);
    if (dh == null) {
      return kr;
    }

    DirectoryHandler newDh = dh.createChildDirectory(name, attrs);
    if (newDh == null) {
      // TODO Should we use _getName()? What if parent 'dh' is renamed?
      String errmsg = String.format("could not create new directory %s in %s", name, dh.getName());
      kr.status = StatusHelper.newStatus(KurmaError.ZOOKEEPER_ERROR, errmsg);
      return kr;
    }

    vh.invalidateNegativeObject(dir_oid, name);
    // Replicate only if no-replication bit is not set
    if (!AttributesHelper.isNoReplicationSet(newDh.getAttrsRef())) {
      replicator.broadcast(messageBuilder.buildDirCreation(vh.getVolumeId(), newDh.get()));
    }

    kr.setNew_attrs(newDh.getAttrsCopy());
    kr.setOid(newDh.getOid());

    LOGGER.info("dir '{}' created with oid: '{}'", name, newDh.getOid());
    return kr;
  }

  @Override
  public KurmaResult lookup(ByteBuffer sessionid, ObjectID dir_oid, String path) throws TException {
    LOGGER.info("lookup '{}' under '{}'", path, dir_oid);
    KurmaResult kr = KurmaResultHelper.newResult();
    long start = System.currentTimeMillis();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    if (vh.isNegative(dir_oid, path)) {
      kr.status = StatusHelper.notFound("'%s' not found under %s", path, dir_oid);
      return kr;
    }

    DirectoryHandler dh = null;
    if (path.charAt(0) == '/') {
      dh = vh.getRootDirectory();
      path = path.substring(1);
    } else {
      dh = useDirectory(vh, dir_oid, kr);
    }

    if (dh == null) {
      kr.status = StatusHelper.notFound("directory '%s' does not exist", dir_oid);
      return kr;
    }

    // TODO Make it efficient by not using deep copy of attr
    long oldTimestamp = dh.getAttrsCopy().getChange_time();
    DirEntry de = dh.lookup(path);
    if (de == null) {
      // TODO Make it efficient by not using deep copy of attr
      long newTimestamp = dh.getAttrsCopy().getChange_time();
      if (newTimestamp == oldTimestamp) {
        // We need this check to avoid the race condition when an object
        // named
        // "path" is added between "lookup" and "cacheLookupNotFound".
        // This
        // check is not perfect because the timestamp is in millisecond
        // and
        // cannot prevent racing file creation happened within a
        // millisecond.
        // However, our benchmarks show operations on ZK has latency
        // more than
        // one millisecond.
        vh.cacheLookupNotFound(dir_oid, path);
      }
      kr.status = StatusHelper.notFound("'%s' not found in %s", path, dh.getName());
      return kr;
    }

    List<DirEntry> entries = new ArrayList<DirEntry>(1);
    entries.add(de);
    kr.setDir_data(entries);
    kr.setOid(de.getOid());

    kr.setNew_attrs(loadAttributes(vh, de.getOid(), kr));
    lookupTime.addAndGet(System.currentTimeMillis() - start);
    LOGGER.info("done lookup '{}' under '{}'", path, dir_oid);
    return kr;
  }

  @Override
  public KurmaResult read(ByteBuffer sessionid, ObjectID file_oid, long offset, int length)
      throws TException {
    LOGGER.info("reading {} bytes from file '{}' at {}", length, file_oid, offset);
    long start = System.currentTimeMillis();
    KurmaSession session = sessionManager.getSession(sessionid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    FileHandler fh = session.findOpenFile(file_oid);
    if (fh == null) {
      kr.status = StatusHelper.OBJECT_NOT_FOUND;
      return kr;
    }

    AbstractMap.SimpleEntry<ByteBuffer, ObjectAttributes> res = fh.read(offset, length);
    if (res == null) {
      kr.status = StatusHelper.newStatus(KurmaError.SERVER_ERROR, "read failed to %s at [%d, +%d]",
          file_oid, offset, length);
      return kr;
    }
    kr.setNew_attrs(res.getValue());
    kr.setFile_data(res.getKey());
    kr.setOid(file_oid);
    readTime.addAndGet(System.currentTimeMillis() - start);

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("done reading {} bytes from file '{}' at {}: {}", length, file_oid, offset,
          LoggingUtils.hash(res.getKey()));
    }

    return kr;
  }

  @Override
  public KurmaResult write(ByteBuffer sessionid, ObjectID file_oid, long offset, ByteBuffer data)
      throws TException {
    LOGGER.info("writing {} bytes to file '{}' at {}", data.remaining(), file_oid, offset);
    long start = System.currentTimeMillis();
    KurmaSession session = sessionManager.getSession(sessionid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    FileHandler fh = session.findOpenFile(file_oid);
    if (fh == null) {
      kr.status = StatusHelper.notFound("file not found or not opened: %s", file_oid.toString());
      LOGGER.error(kr.status.getErrmsg());
      return kr;
    }

    int length = data.remaining();
    int nblocks = (length + fh.getBlockSize() - 1) >> fh.getBlockShift();
    List<Long> newVersions = new ArrayList<>(nblocks);
    if (!fh.write(offset, data, Optional.of(newVersions))) {
      kr.status = StatusHelper.zkError("could not write to file: %s", fh.getName());
      LOGGER.error(kr.status.getErrmsg());
      return kr;
    }

    ObjectAttributes newAttrs = fh.getAttrsCopy();

    // Replicate only if no-replication bit is not set
    if (!AttributesHelper.isNoReplicationSet(newAttrs)) {
      replicator.broadcast(messageBuilder.buildFileUpdate(session.getVolumeHandler().getVolumeId(),
          file_oid, offset, length, newVersions, newAttrs));
    }

    kr.setNew_attrs(newAttrs);
    kr.setOid(file_oid);
    writeTime.addAndGet(System.currentTimeMillis() - start);

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("done writing {} bytes to file '{}' at {}: {}", data.remaining(), file_oid,
          offset, LoggingUtils.hash(data.array()));
    }
    return kr;
  }

  @Override
  public KurmaResult listdir(ByteBuffer sessionid, ObjectID dir_oid) throws TException {
    LOGGER.info("listing directory '{}'", dir_oid);
    KurmaResult kr = KurmaResultHelper.newResult();
    if (sessionid == null) {
      kr.status = StatusHelper.newStatus(KurmaError.SESSION_NOT_EXIST, "sessionId not specified");
      return kr;
    }
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    DirectoryHandler dh = useDirectory(vh, dir_oid, kr);
    if (dh == null) {
      return kr;
    }

    kr.setDir_data(dh.list());
    kr.setNew_attrs(dh.get().attrs);

    LOGGER.info("done listing directory '{}'", dir_oid);
    return kr;
  }

  private ObjectAttributes loadAttributes(VolumeHandler vh, ObjectID oid, KurmaResult kr) {
    ObjectAttributes attrs = null;
    if (ObjectIdHelper.isDirectory(oid)) {
      DirectoryHandler dh = vh.getDirectoryHandler(oid);
      if (dh == null) {
        kr.status = StatusHelper.notFound("directory not found: %s", oid);
      } else {
        attrs = dh.getAttrsCopy();
      }
    } else if (ObjectIdHelper.isFile(oid)) {
      FileHandler fh = vh.getLoadedFile(oid);
      if (fh == null) {
        kr.status = StatusHelper.invalidOid("file ObjectId invalid", oid);
      } else {
        attrs = fh.getAttrsCopy();
        LOGGER.debug("read mtime {} from {}@{}", attrs.getModify_time(), fh,
            System.identityHashCode(fh));
        vh.putFile(fh);
      }
    } else {
      kr.status = StatusHelper.invalidOid("not file or directory: %s", oid);
    }

    return attrs;
  }

  @Override
  public KurmaResult getattrs(ByteBuffer sessionid, ObjectID oid) throws TException {
    LOGGER.info("getattr of '{}'", oid);
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    if (vh.isNegative(oid, "")) {
      kr.status = StatusHelper.notFound("Object '%s' does not exist", oid);
      return kr;
    }

    kr.setNew_attrs(loadAttributes(vh, oid, kr));
    if (kr.status.getErrcode() == KurmaError.OBJECT_NOT_FOUND.getValue()) {
      // Unlike the case in "lookup", we don't need to worry about race
      // conditions here because Kurma never reuse ObjectIDs.
      vh.cacheLookupNotFound(oid, "");
    }

    LOGGER.info("done getattr of '{}'", oid);
    return kr;
  }

  @Override
  public KurmaResult setattrs(ByteBuffer sessionid, ObjectID oid, ObjectAttributes attrs)
      throws TException {
    LOGGER.info("setattr of '{}' to {}", oid, attrs);
    boolean isNoReplicationBitSet = false;
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    ObjectAttributes oldAttrs = null;
    ObjectAttributes newAttrs = null;
    VolumeHandler vh = session.getVolumeHandler();
    if (ObjectIdHelper.isDirectory(oid)) {
      DirectoryHandler dh = vh.getDirectoryHandler(oid);
      if (dh == null) {
        kr.status = StatusHelper.OBJECT_NOT_FOUND;
        return kr;
      }

      oldAttrs = dh.setAttrs(attrs);
      if (oldAttrs == null) {
        kr.status =
            StatusHelper.permissionDenied("could not setattrs of directory %s", dh.getName());
        return kr;
      }

      if (dh.isDirty() && !dh.save()) {
        kr.status = StatusHelper.zkError("could not save new attrs of directory %s", dh.getName());
        return kr;
      }
      newAttrs = dh.getAttrsCopy();
      if (AttributesHelper.isNoReplicationSet(newAttrs)) {
        isNoReplicationBitSet = true;
      }

    } else if (ObjectIdHelper.isFile(oid)) {
      FileHandler fh = vh.getLoadedFile(oid);
      if (fh == null) {
        kr.status = StatusHelper.OBJECT_NOT_FOUND;
        return kr;
      }

      oldAttrs = fh.setAttrs(attrs);
      if (oldAttrs == null) {
        kr.status = StatusHelper.permissionDenied("could not setattrs of file %s", oid);
        LOGGER.error(kr.status.getErrmsg());
        return kr;
      }

      if (attrs.isSetFilesize()) {
        try {
          fh.truncate(attrs.getFilesize());
        } catch (Exception e) {
          kr.status = StatusHelper.zkError("could not set the size of file %s to %d", oid,
              attrs.getFilesize());
          LOGGER.error(kr.status.getErrmsg(), e);
          e.printStackTrace();
          kr.setNew_attrs(fh.getAttrsCopy());
          return kr;
        }
      }

      if (fh.isDirty() && !fh.flush()) {
        kr.status = StatusHelper.zkError("could not save dirty data of file %s", oid);
        LOGGER.error(kr.status.getErrmsg());
        ObjectAttributes revertedResult = fh.setAttrs(oldAttrs);
        kr.setNew_attrs(oldAttrs);
        assert (revertedResult != null);
        return kr;
      }
      newAttrs = fh.getAttrsCopy();
      if (AttributesHelper.isNoReplicationSet(newAttrs)) {
        isNoReplicationBitSet = true;
      }
    }

    if (!isNoReplicationBitSet) {
      replicator
          .broadcast(messageBuilder.buildAttributeSet(vh.getVolumeId(), oid, oldAttrs, newAttrs));
    }

    kr.setNew_attrs(newAttrs);
    LOGGER.info("done setattr of '{}' to {}", oid, attrs);
    return kr;
  }

  @Override
  public KurmaResult rename(ByteBuffer sessionid, ObjectID src_dir_oid, String src_name,
      ObjectID dst_dir_oid, String dst_name) throws TException {
    LOGGER.info("renaming '{}' under '{}' to '{}' under '{}'", src_name, src_dir_oid, dst_name,
        dst_dir_oid);
    boolean isNoReplicationBitSet = false;
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    DirectoryHandler srcDh = useDirectory(vh, src_dir_oid, kr);
    DirectoryHandler dstDh = useDirectory(vh, dst_dir_oid, kr);
    if (srcDh == null || dstDh == null) {
      return kr;
    }

    // Get handler of child and check if replication required for it
    DirEntry dirEntry = srcDh.lookup(src_name);
    if (dirEntry != null) {
      ObjectID oid = dirEntry.getOid();
      if (ObjectIdHelper.isDirectory(oid)) {
        DirectoryHandler dh = vh.getDirectoryHandler(oid);
        if (AttributesHelper.isNoReplicationSet(dh.getAttrsRef())) {
          isNoReplicationBitSet = true;
        }
      } else {
        FileHandler fh = vh.getLoadedFile(oid);
        if (fh != null) {
          if (AttributesHelper.isNoReplicationSet(fh.getAttrsCopy())) {
            isNoReplicationBitSet = true;
            vh.putFile(fh);
          }
          vh.putFile(fh);
        } else {
          // TODO Handle this
        }
      }
    }

    Entry<ObjectID, Integer> res = srcDh.renameChild(src_name, dstDh, dst_name, true);
    if (res.getValue() != 0) {
      kr.status = StatusHelper.newStatus(KurmaError.findByValue(res.getValue()),
          "could not rename %s/%s to %s/%s", srcDh.getName(), src_name, dstDh.getName(), dst_name);
      LOGGER.error(kr.status.getErrmsg());
      return kr;
    }

    vh.invalidateNegativeObject(dst_dir_oid, dst_name);
    if (!isNoReplicationBitSet) {
      replicator.broadcast(messageBuilder.buildRename(vh.getVolumeId(), src_dir_oid, res.getKey(),
          src_name, dst_dir_oid, dst_name));
    }

    kr.setNew_attrs(dstDh.getAttrsCopy());
    LOGGER.info("done renaming '{}' under '{}' to '{}' under '{}'", src_name, src_dir_oid, dst_name,
        dst_dir_oid);
    return kr;
  }

  @Override
  public KurmaResult unlink(ByteBuffer sessionid, ObjectID dir_oid, String name) throws TException {
    LOGGER.info("unlink '{}' from '{}'", name, dir_oid);
    boolean isNoReplicationBitSet = false;
    boolean isDirectory = false;
    ObjectID childOid = null;
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return kr;
    }

    VolumeHandler vh = session.getVolumeHandler();
    DirectoryHandler dh = useDirectory(vh, dir_oid, kr);
    if (dh == null) {
      return kr;
    }

    DirEntry child = dh.lookup(name);
    if (child != null) {
      childOid = child.getOid();
      if (ObjectIdHelper.isDirectory(childOid)) {
        isDirectory = true;
        if (AttributesHelper.isNoReplicationSet(vh.getDirectoryHandler(childOid).getAttrsRef())) {
          isNoReplicationBitSet = true;
        }
      } else if (ObjectIdHelper.isFile(childOid)) {
        FileHandler fh = vh.getLoadedFile(childOid);
        if (fh != null) {
          if (AttributesHelper.isNoReplicationSet(fh.getAttrsCopy())) {
            isNoReplicationBitSet = true;
          }
          vh.putFile(fh);
        } else {
          // TODO How to handle this
        }
      }
    }

    DirEntry dentry = dh.removeChild(name);
    if (dentry == null) {
      String errmsg =
          String.format("could not remove child \"%s\" from directory %s", name, dh.getName());
      kr.status = StatusHelper.newStatus(KurmaError.ZOOKEEPER_ERROR, errmsg);
      return kr;
    }

    vh.cacheRemovedObject(dentry.getOid());

    if (!isNoReplicationBitSet && isDirectory) {
      replicator
          .broadcast(messageBuilder.buildDirRemoval(vh.getVolumeId(), dir_oid, childOid, name));
    } else if (!isNoReplicationBitSet && !isDirectory) {
      replicator
          .broadcast(messageBuilder.buildFileRemoval(vh.getVolumeId(), dir_oid, childOid, name));
    }

    kr.setNew_attrs(dh.getAttrsCopy());
    kr.setOid(dentry.getOid());
    LOGGER.info("done unlink '{}' from '{}'", name, dir_oid);
    return kr;
  }

  public KurmaHandler getKurmaHandler() {
    return this.kurmaHandler;
  }

  @Override
  public KurmaResult lock(ByteBuffer sessionid, ObjectID oid, LockRange locks) throws TException {
    LOGGER.error("'lock' is not implemented");
    return null;
  }

  @Override
  public KurmaResult unlock(ByteBuffer sessionid, ObjectID oid, LockRange locks) throws TException {
    LOGGER.error("'unlock' is not implemented");
    return null;
  }

  Entry<FileHandler, KurmaResult> _getSnapshotFile(ByteBuffer sessionid, ObjectID oid) {
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);
    if (session == null) {
      kr.status = StatusHelper.SESSION_NOT_EXIST;
      return new SimpleEntry<FileHandler, KurmaResult>(null, kr);
    }

    if (!ObjectIdHelper.isFile(oid)) {
      kr.status = StatusHelper.invalidOid("ObjectId %s does not map to a regular file", oid);
      return new SimpleEntry<FileHandler, KurmaResult>(null, kr);
    }

    VolumeHandler vh = session.getVolumeHandler();
    FileHandler fh = vh.getLoadedFile(oid);
    if (fh == null) {
      kr.status = StatusHelper.notFound("ObjectId %s not found", oid);
    }

    return new SimpleEntry<FileHandler, KurmaResult>(fh, kr);
  }

  private void _setResultsWithSnapshotInfo(KurmaResult kr, SnapshotInfo info, ObjectID oid) {
    kr.setSnapshot_id(info.id);
    kr.setSnapshot_time(info.createTime);
    kr.setOid(oid);
    kr.setNew_attrs(info.attrs);
  }

  @Override
  public KurmaResult take_snapshot(ByteBuffer sessionid, ObjectID oid, String snapshot_name,
      String description) throws TException {
    LOGGER.info("Taking snapshot '{}' for '{}'", snapshot_name, oid);
    Entry<FileHandler, KurmaResult> kv = _getSnapshotFile(sessionid, oid);
    KurmaResult kr = kv.getValue();
    if (!StatusHelper.isOk(kr.getStatus())) {
      return kr;
    }

    FileHandler fh = kv.getKey();
    SnapshotInfo info = fh.takeSnapshot(snapshot_name, description);
    if (info == null) {
      kr.status =
          StatusHelper.serverError("Cannot create snapshot %s for File %s", snapshot_name, oid);
      return kr;
    }

    _setResultsWithSnapshotInfo(kr, info, oid);

    return kr;
  }

  @Override
  public KurmaResult restore_snapshot(ByteBuffer sessionid, ObjectID oid, String snapshot_name,
      int id) throws TException {
    String name = snapshot_name == null ? ("ID-" + id) : snapshot_name;
    LOGGER.info("Resotre snapshot '{}' for '{}'", name, oid);

    Entry<FileHandler, KurmaResult> kv = _getSnapshotFile(sessionid, oid);
    KurmaResult kr = kv.getValue();
    if (!StatusHelper.isOk(kr.getStatus())) {
      return kr;
    }

    FileHandler fh = kv.getKey();
    SnapshotInfo info = fh.restoreSnapshot(snapshot_name);
    if (info == null) {
      kr.status = StatusHelper.notFound("Cannot restore snapshot %s for file %s", name, oid);
      return kr;
    }

    kr.setNew_attrs(fh.getAttrsCopy());
    _setResultsWithSnapshotInfo(kr, info, oid);

    return kr;
  }

  @Override
  public KurmaResult list_snapshots(ByteBuffer sessionid, ObjectID oid) throws TException {
    LOGGER.info("listing snapshots of '{}'", oid);
    Entry<FileHandler, KurmaResult> kv = _getSnapshotFile(sessionid, oid);
    KurmaResult kr = kv.getValue();
    if (!StatusHelper.isOk(kr.getStatus())) {
      return kr;
    }

    FileHandler fh = kv.getKey();
    kr.setDir_data(fh.listSnapshots());

    return kr;
  }

  @Override
  public KurmaResult delete_snapshot(ByteBuffer sessionid, ObjectID oid, String snapshot_name,
      int id) throws TException {
    String name = snapshot_name == null ? ("ID-" + id) : snapshot_name;
    LOGGER.info("Resotre snapshot '{}' for '{}'", name, oid);

    Entry<FileHandler, KurmaResult> kv = _getSnapshotFile(sessionid, oid);
    KurmaResult kr = kv.getValue();
    if (!StatusHelper.isOk(kr.getStatus())) {
      return kr;
    }

    FileHandler fh = kv.getKey();
    SnapshotInfo info = fh.lookupSnapshot(snapshot_name, id);
    if (fh.deleteSnapshot(info.name) == null) {
      kr.status =
          StatusHelper.serverError("Failed to delete snapshot %s for file %s", info.name, oid);
      return kr;
    }

    _setResultsWithSnapshotInfo(kr, info, oid);
    return kr;
  }

  @Override
  public KurmaResult lookup_snapshot(ByteBuffer sessionid, ObjectID oid, String snapshot_name,
      int id) throws TException {
    LOGGER.info("looking up snapshot '{}' (ID-{}) of '{}'", snapshot_name, id, oid);
    Entry<FileHandler, KurmaResult> kv = _getSnapshotFile(sessionid, oid);
    KurmaResult kr = kv.getValue();
    if (!StatusHelper.isOk(kr.getStatus())) {
      return kr;
    }

    FileHandler fh = kv.getKey();
    SnapshotInfo info = fh.lookupSnapshot(snapshot_name, id);
    if (info == null) {
      kr.status = StatusHelper.notFound("Cannot find snapshot %s for file %s", snapshot_name, oid);
      return kr;
    }

    _setResultsWithSnapshotInfo(kr, info, oid);
    return kr;
  }

  @Override
  public KurmaResult get_dynamic_info(ByteBuffer sessionid) throws TException {
    KurmaResult kr = KurmaResultHelper.newResult();
    KurmaSession session = sessionManager.getSession(sessionid);

    DynamicInfo dinfo = new DynamicInfo();
    VolumeHandler vh = session.getVolumeHandler();
    try {
      dinfo.setBytes(vh.getConfig().getDefaultKvsFacade().getBytesUsed());
      dinfo.setFiles(vh.getObjectCount());
    } catch (IOException e) {
      LOGGER.error("failed to get the bytes used", e);
    }

    kr.setDynamic_info(dinfo);
    return kr;
  }

}
