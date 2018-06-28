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

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;

import edu.stonybrook.kurma.fs.OpenFlags;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.meta.ObjectID;

public class KurmaSession {
  private static final Logger LOGGER = LoggerFactory.getLogger(KurmaSession.class);

  /**
   * Timeout (milli. seconds) of a session.
   */
  private final int sessionTimeoutMs;

  private ByteBuffer clientId = null;
  private VolumeHandler volumeHandler = null;
  private long timestamp = 0;
  private HashCode sessionId;

  private ConcurrentHashMap<ObjectID, FileOpenState> openFiles = null;

  class FileOpenState {
    FileHandler handler;
    OpenFlags flags;

    public FileOpenState(FileHandler fh, OpenFlags of) {
      handler = fh;
      flags = of;
    }
  }

  public KurmaSession(ByteBuffer cid, VolumeHandler vh, Hasher hasher) throws Exception {
    this.clientId = cid;
    this.volumeHandler = vh;
    sessionId = hasher.putBytes(clientId.array(), clientId.position(), clientId.remaining())
        .putUnencodedChars(volumeHandler.getVolumeInfo().id).hash();
    timestamp = System.currentTimeMillis();
    openFiles = new ConcurrentHashMap<ObjectID, FileOpenState>();
    sessionTimeoutMs = 1000 * vh.getConfig().getSessionTimeout();
  }

  public KurmaSession(ByteBuffer cid, VolumeHandler vh, HashFunction hf, long random)
      throws Exception {
    this.clientId = cid;
    this.volumeHandler = vh;
    sessionId = hf.newHasher().putBytes(clientId.array(), clientId.position(), clientId.remaining())
        .putLong(random).putUnencodedChars(volumeHandler.getVolumeInfo().id).hash();
    timestamp = System.currentTimeMillis();
    openFiles = new ConcurrentHashMap<ObjectID, FileOpenState>();
    sessionTimeoutMs = 1000 * vh.getConfig().getSessionTimeout();
  }

  public FileHandler openFile(ObjectID oid, OpenFlags flags) {
    FileOpenState fos = openFiles.get(oid);
    if (fos != null) {
      LOGGER.error("file already opened with {} flags", fos.flags);
      return null;
    }

    FileHandler fh = volumeHandler.getLoadedFile(oid);
    if (fh == null) {
      LOGGER.error("could not load file metadata from ZK: {}", ObjectIdHelper.toString(oid));
      return null;
    }

    fos = new FileOpenState(fh, flags);
    openFiles.putIfAbsent(oid, fos);

    return fh;
  }

  private FileHandler doClose(FileOpenState fos) {
    // flush dirty data
    FileHandler fh = fos.handler;
    if (!fh.flush()) {
      LOGGER.error("failed to flush dirty data of {}", fh.getOid());
      return null;
    }

    volumeHandler.putFile(fos.handler);
    return fh;
  }

  public FileHandler closeFile(ObjectID oid) {
    FileOpenState fos = openFiles.remove(oid);
    if (fos == null) {
      LOGGER.error("closing a file that is not opened: {0}", oid);
      return null;
    }
    return doClose(fos);
  }

  public int countOpenFiles() {
    return openFiles.size();
  }

  public FileHandler findOpenFile(ObjectID oid) {
    FileOpenState fos = openFiles.get(oid);
    if (fos == null) {
      LOGGER.debug("file {} not open", ObjectIdHelper.toString(oid));
      return null;
    }

    return fos.handler;
  }

  public boolean closeAllFiles() {
    Iterator<Entry<ObjectID, FileOpenState>> it = openFiles.entrySet().iterator();
    while (it.hasNext()) {
      Entry<ObjectID, FileOpenState> entry = it.next();
      if (doClose(entry.getValue()) == null) {
        return false;
      }
      it.remove();
    }
    return true;
  }

  public ByteBuffer getClientId() {
    return clientId;
  }

  public VolumeHandler getVolumeHandler() {
    return volumeHandler;
  }

  public ByteBuffer getSessionId() {
    return ByteBuffer.wrap(sessionId.asBytes());
  }

  public void updateTimestamp() {
    timestamp = System.currentTimeMillis();
  }

  public long getTimestamp() {
    return timestamp;
  }

  public boolean hasTimedOut() {
    LOGGER.debug("timeout ms {}; timeout {}; current {}", sessionTimeoutMs, timestamp,
        System.currentTimeMillis());
    return (timestamp + sessionTimeoutMs) < System.currentTimeMillis();
  }

  @Override
  public String toString() {
    return sessionId.toString();
  }

}
