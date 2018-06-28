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

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.crypto.SecretKey;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.KurmaException.NoZNodeException;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.fs.KurmaError;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.DirectoryHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.ZkClient;

public class DirectoryHandler extends AbstractHandler<Directory> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryHandler.class);

  private VolumeHandler volumeHandler;
  private ZkClient zkClient;

  private final IGatewayConfig config;

  private AtomicReference<ConcurrentHashMap<String, DirEntry>> children;

  /*
   * We want to allow simultaneous operations on directory only if those operations are from local
   * gateway. We want remote operation on dir executed exclusively to avoid race condition described
   * below.
   *
   * Race condition can occur when remote operation handles create file/dir conflict. First it
   * checks for existence of file/dir with some name 'x' and then try to add suffix to it if
   * file/dir exists. Let's say if it didn't find file/dir with that name and after that it is
   * possible that local gateway creates file/dir with same name. So remote operation will fail if
   * we allow both operations to operate simultaneously on that directory. It is not possible to
   * handle this without lock as it is not possible to retry as we are dependent on file collector
   * to collect created znode first.
   */
  private ReentrantReadWriteLock dirLock = new ReentrantReadWriteLock();

  public DirectoryHandler(ObjectID oid, VolumeHandler vh) {
    this(oid, vh, false);
  }

  public DirectoryHandler(ObjectID oid, VolumeHandler vh, boolean isNew) {
    Directory dir = DirectoryHelper.newDirectory(oid, Optional.empty());
    setWrapper(new TWrapper<>(vh.getObjectZpath(oid), dir, vh.getConfig().compressMetadata(), isNew));
    this.volumeHandler = vh;
    this.zkClient = volumeHandler.getZkClient();
    this.config = vh.getConfig();
    children = new AtomicReference<>(new ConcurrentHashMap<>());
  }

  public DirLockHolder lockDirRead() {
    dirLock.readLock().lock();
    return new DirLockHolder(true);
  }

  public DirLockHolder lockDirWrite() {
    dirLock.writeLock().lock();
    return new DirLockHolder(false);
  }

  class DirLockHolder {
    public DirLockHolder() {
      // empty holder
      released = true;
    }

    public DirLockHolder(boolean isRead) {
      this.isRead = isRead;
      released = false;
    }

    public void release() {
      if (!released) {
        if (isRead) {
          dirLock.readLock().unlock();
        } else {
          dirLock.writeLock().unlock();
        }
        released = true;
      }
    }

    private boolean isRead;
    private boolean released;
  }

  public boolean create(ObjectID parentOid, String name, ObjectAttributes attrs, KurmaTransaction txn) {
    boolean res = false;
    // Hold write lock for creation of directory itself
    DirLockHolder lock = lockDirWrite();
    try {
      zkClient.ensurePath(getZpath(), true);
      synchronized (wrapper) {
        update().setParent_oid(parentOid);
        update().setName(name);
        update().setEntries(new ArrayList<DirEntry>());
        long timestamp = System.currentTimeMillis();
        attrs.setCreate_time(timestamp);
        attrs.setAccess_time(timestamp);
        attrs.setChange_time(timestamp);
        attrs.setModify_time(timestamp);
        attrs.setNlinks(1);
        update().setAttrs(attrs);

        res = wrapper.create(txn);
        // res = wrapper.create(client);
        volumeHandler.incrementObjectCount();
      }
    } catch (Exception e) {
      LOGGER.error("could not create directoryin ZK", e);
      e.printStackTrace();
      res = false;
    } finally {
      // release lock
      lock.release();
    }

    return res;
  }

  /**
   * Load directory metadata from ZK.
   *
   * @return whether the loading is successful
   */
  public boolean load() {
    // Hold write lock
    DirLockHolder lock = lockDirWrite();

    ObjectID oldOid = getOid();
    try {
      synchronized (wrapper) {
        wrapper.read(zkClient);
        assert (ObjectIdHelper.equals(oldOid, getOid()));
        _refreshChildren();
      }
    } catch (CuratorException e) {
      if (e instanceof NoZNodeException) {
        LOGGER.debug("znode of object {} does not exist", ObjectIdHelper.getShortId(oldOid));
      } else {
        LOGGER.error("unknown error when loading directory {}", ObjectIdHelper.getShortId(oldOid),
            e.getCause());
        e.printStackTrace();
      }
      return false;
    } finally {
      // release lock
      lock.release();
    }
    return true;
  }

  public boolean save() {
    // Hold read lock
    DirLockHolder lock = lockDirRead();
    try {
      synchronized (wrapper) {
        wrapper.persist(zkClient, false);
      }
    } catch (Exception e) {
      LOGGER.error("could not save directory", e);
      return false;
    } finally {
      // release lock
      lock.release();
    }
    return true;
  }

  public boolean delete() {
    boolean res = false;

    // Hold write lock as we are deleting dir itself
    DirLockHolder lock = lockDirWrite();

    try {
      synchronized (wrapper) {
        LOGGER.info("deleting directory {}", getOid());
        if (!children.get().isEmpty()) {
          LOGGER.debug("trying to delete non-empty directory");
          return false;
        }
        KurmaTransaction txn = zkClient.newTransaction();
        res = wrapper.delete(txn);
        zkClient.submitTransaction(txn);
        volumeHandler.decrementObjectCount();
      }
    } catch (Exception e) {
      LOGGER.error("could not delete directory", e);
      return false;
    } finally {
      // release lock
      lock.release();
    }
    return res;
  }

  // TODO Check for caller and change location if necessary
  private void _updateTimestamps() {
    // Change directory content should update both change and modify
    // timestamps.
    ObjectAttributes attrs = update().getAttrs();
    long now = System.currentTimeMillis();
    attrs.setChange_time(now);
    attrs.setModify_time(now);
  }

  /**
   * Set attributes.
   *
   * @param newAttrs New attributes to be set.
   * @return Old attributes
   */
  public ObjectAttributes setAttrs(ObjectAttributes newAttrs) {
    // Hold write lock
    DirLockHolder lock = lockDirWrite();

    ObjectAttributes attrs = update().getAttrs();
    ObjectAttributes oldAttrs = attrs.deepCopy();
    if (!AttributesHelper.setAttrs(attrs, newAttrs)) {
      LOGGER.error("failed to setAttributes");
      // return null in case of error
      oldAttrs = null;
    }

    // release lock
    lock.release();
    return oldAttrs;
  }

  public FileHandler createChildFile(String name, ObjectAttributes attrs) {
    return createChildFile(volumeHandler.newFileOid(), name, attrs, config.getDefaultKvsFacade(),
        null, null, true);
  }

  /**
   * Create child file inside this directory
   *
   * @param oid Object ID of child file
   * @param name name of child file
   * @param attrs attr of child file
   * @param fileKey filekey of child file
   * @param localOperation indicates whether this is local gateway operation
   * @return file handler for created file
   */
  public FileHandler createChildFile(ObjectID oid, String name, ObjectAttributes attrs,
      KvsFacade kvsFacade, SecretKey fileKey, KeyMap keymap, boolean localOperation) {
    FileHandler fh = null;
    DirLockHolder lock = null;

    try {

      /*
       * Hold write lock if this is remote operation as we want to wait for all local insertions to
       * this directory
       */
      if (localOperation) {
        lock = lockDirRead();
      } else {
        lock = lockDirWrite();
      }

      /*
       * Check if child with this name already exists If yes, check if this change is initiated by
       * remote gateway or local gateway If it is local gateway then return null If it is remote
       * gateway then create dir with suffix name This suffix name is determined by
       * 'getSuffixedName' function
       */
      if (_hasChild(name)) {
        LOGGER.debug("Try to add {} at Gateway-{}", oid, config.getGatewayName());
        if (oid.getCreator() != config.getGatewayId()) {
          // remote operation
          name = DirectoryHelper.getSuffixedName(name, oid.getCreator());
        } else {
          LOGGER.error("could not create file {}: name already exists in Gateway-{}",
              name, config.getGatewayName());
          return null;
        }
      }

      KurmaTransaction txn = zkClient.newTransaction();

      fh = new FileHandler(oid, volumeHandler);
      if (!fh.create(getOid(), name, attrs, kvsFacade, fileKey, keymap, txn)) {
        LOGGER.error("could not create new file {} {}", name, ObjectIdHelper.toString(oid));
        return null;
      }

      if (!_addChild(name, oid, txn)) {
        // We don't need to collect the created file because the whole transaction is not submitted.
        LOGGER.error("could not add file into directory {}", get().name);
        return null;
      }

      // set no-replication for child if this directory has it set
      if (AttributesHelper.isNoReplicationSet(get().getAttrs())) {
        AttributesHelper.setNoReplicationBit(fh.getAttrsRef());
      }

      zkClient.submitTransaction(txn);

    } finally {
      if (lock != null) {
        lock.release();
      }
    }

    LOGGER.debug("file {} created and added into {}", name, getName());
    return fh;
  }

  public DirectoryHandler createChildDirectory(String name, ObjectAttributes attrs) {
    return createChildDirectory(volumeHandler.newDirectoryOid(), name, attrs, true);
  }

  /**
   * Create child directory inside this directory
   *
   * @param oid Object ID of child dir
   * @param name name of child dir
   * @param attrs attr of child dir
   * @param localOperation indicates whether this is local gateway operation
   * @return directory handler for created directory
   */
  public DirectoryHandler createChildDirectory(ObjectID oid, String name, ObjectAttributes attrs,
      boolean localOperation) {
    DirectoryHandler dh = null;
    DirLockHolder lock = null;
    try {

      /*
       * Hold write lock if this is remote operation as we want to make wait all local insertion to
       * this directory
       */
      if (localOperation) {
        lock = lockDirRead();
      } else {
        lock = lockDirWrite();
      }

      /*
       * Check if child with this name already exists If yes, check if this change is initiated by
       * remote gateway or local gateway If it is local gateway then return null If it is remote
       * gateway then create dir with suffix name This suffix name is determined by
       * 'getSuffixedName' function
       */
      if (_hasChild(name)) {
        if (oid.getCreator() != config.getGatewayId()) {
          // remote operation
          name = DirectoryHelper.getSuffixedName(name, oid.getCreator());
        } else {
          LOGGER.error("child with name {} already exists", name);
          return null;
        }
      }

      KurmaTransaction txn = zkClient.newTransaction();

      dh = new DirectoryHandler(oid, volumeHandler);

      if (!dh.create(getOid(), name, attrs, txn)) {
        LOGGER.error("could not create new directory {}", name);
        return null;
      }

      if (!_addChild(name, oid, txn)) {
        // No need to garbage collect, because the txn will not be submitted.
        LOGGER.error("could not add {} directory into directory {}", name, get().name);
        return null;
      }

      // set no-replication for child if this directory has it set
      if (AttributesHelper.isNoReplicationSet(get().getAttrs())) {
        AttributesHelper.setNoReplicationBit(dh.getAttrsRef());
      }
      zkClient.submitTransaction(txn);
    } finally {
      if (lock != null) {
        lock.release();
      }
    }
    // add the new directory into the volume's directory cache
    volumeHandler.addDirectoryHandler(dh);

    LOGGER.debug("directory {} created and added into {}", name, getName());
    return dh;
  }

  /**
   * Add the given oid as a child. It only add a DirEntry into the directory, the underlying object
   * should be created before calling this.
   *
   *
   * @param name name of the child object
   * @param oid ObjectID of the child to be added, should exists in ZK
   * @return whether the child is successfully added
   */
  public boolean addChild(String name, ObjectID oid) {
    // Hold read lock
    KurmaTransaction txn = zkClient.newTransaction();
    DirLockHolder lock = lockDirRead();
    boolean res = _addChild(name, oid, txn);
    lock.release();
    zkClient.submitTransaction(txn);
    return res;
  }

  /**
   * Add the given oid as a child. It only add a DirEntry into the directory, the underlying object
   * should be created before calling this. Caller is required to hold the lock.
   *
   * @param name name of the child object
   * @param oid ObjectID of the child to be added, should exists in ZK
   * @return whether the child is successfully added
   */
  public boolean _addChild(String name, ObjectID oid, KurmaTransaction txn) {
    boolean res = false;
    DirEntry dentry = DirectoryHelper.newDirEntry(oid, name);

    while (true) {
      DirEntry oldEntry = children.get().putIfAbsent(name, dentry);
      if (oldEntry != null) {
        LOGGER.warn("{} already exists in directory '{}'", name, get().name);
        break;
      }

      LOGGER.info("adding {} into directory {}: {}", name, get().getOid(),
          children.get().hashCode());
      try {
        synchronized (wrapper) {
          // update metadata in ZK
          if (!update().isSetEntries()) {
            update().setEntries(new ArrayList<DirEntry>());
          }
          update().entries.add(dentry);
          get().attrs.nlinks += 1;
          _updateTimestamps();
          // wrapper.write(client);
          res = wrapper.write(txn);
        }
      } catch (Exception e) {
        // TODO Try removing this, try existing unit tests
        if (e.getCause() instanceof KeeperException.BadVersionException) {
          LOGGER.debug("version mismatch, reloading");
          if (load()) {
            continue;
          }
        }
        LOGGER.error(String.format("could not write directory metadata of %s", name), e);
        break;
      }
      res = true;
      break;
    }

    return res;
  }

  /**
   * Unlink a file, optionally delete it if the nlinks drop to zero.
   *
   * @param dentry
   * @return true if the unlink is successful.
   */
  private boolean _unlinkFile(DirEntry dentry) {
    FileHandler handler = volumeHandler.getLoadedFile(dentry.getOid());
    if (handler == null) {
      LOGGER.error("could not load FileHandler");
      return false;
    }

    // TODO persist the nlink?
    if (handler.unlink() <= 0) {
      volumeHandler.getGarbageCollector().collectFile(handler);
    }

    volumeHandler.putFile(handler);

    return true;
  }

  /**
   * Unlink a directory. The directory will be deleted as there is no hard link for directories.
   *
   * @param dentry
   * @return true if the deletion is successful.
   */
  private boolean _unlinkDirectory(DirEntry dentry) {
    DirectoryHandler dh = volumeHandler.getDirectoryHandler(dentry.oid);
    if (dh == null) {
      LOGGER.error("directory does not exist");
      return false;
    }

    // the volume handler will garbage collect the znode of the directory
    volumeHandler.removeDirectoryHandler(dh);

    return true;
  }

  public DirEntry removeChild(String name) {
    return removeChild(name, Optional.empty(), true);
  }

  private boolean _checkRemove(String name, Optional<ObjectID> oid) {
    if (!_hasChildEntry(name, oid)) {
      LOGGER.warn("child entry not found");
      return false;
    }

    DirEntry dentry = children.get().get(name);
    if (ObjectIdHelper.isDirectory(dentry.getOid())) {
    long start = System.nanoTime();
      DirectoryHandler dh = volumeHandler.getDirectoryHandler(dentry.oid);
    zkTime2.addAndGet(System.nanoTime() - start);
      if (dh.getChildrenCount() > 0) {
        return false;
      }
    }
    return true;
  }

  public static AtomicLong zkTime1 = new AtomicLong();
  public static AtomicLong zkTime2 = new AtomicLong();

  public DirEntry removeChild(String name, Optional<ObjectID> oid, boolean localOperation) {
    DirEntry dentry = null;
    DirLockHolder lock = null;
    // Hold read lock for local operation and write lock for remote
    // operation
    if (localOperation) {
      lock = lockDirRead();
    } else {
      lock = lockDirWrite();
    }

    try {
      LOGGER.info("removing '{}' from directory '{}'", name, get().getName());
      if (!_checkRemove(name, oid)) {
        LOGGER.warn("Remove check failed");
        return null;
      }

      dentry = _removeChildEntry(name);
      if (dentry == null) {
        LOGGER.warn("child entry doesn't exists");
        return null;
      }

      long start = System.nanoTime();
      // TODO Should we sync entire block?
      synchronized (wrapper) {
        wrapper.persist(zkClient, false);
      }

      _collectChildEntry(dentry);
      zkTime1.addAndGet(System.nanoTime() - start);

    } catch (Exception e) {
      LOGGER.error("could not update directory in ZK", e);
      e.printStackTrace();
      return null;
    } finally {
      // release lock
      lock.release();
    }

    return dentry;
  }

  private boolean _hasChildEntry(String name, Optional<ObjectID> oid) {
    DirEntry dentry = children.get().get(name);
    return dentry != null && (!oid.isPresent() || dentry.getOid().equals(oid.get()));
  }

  public boolean hasChildEntry(String name, Optional<ObjectID> oid) {
    // Hold read lock for remote operation
    DirLockHolder lock = lockDirRead();
    boolean res = _hasChildEntry(name, oid);
    // release lock
    lock.release();
    return res;
  }

  public int getChildrenCount() {
    DirLockHolder lock = lockDirRead();
    int n = 0;
    try {
      n = children.get().size();
    } finally {
      lock.release();
    }
    return n;
  }

  /**
   * Garbage collect the child entry.
   *
   * @param dentry
   */
  private void _collectChildEntry(DirEntry dentry) {
    boolean res = false;
    if (dentry != null) {
      res = ObjectIdHelper.isDirectory(dentry.getOid()) ? _unlinkDirectory(dentry)
          : _unlinkFile(dentry);
    }
    if (!res) {
      LOGGER.error("Unable to delete dentry");
    }
  }

  /**
   * Insert new or replace existing entry. It will fail if the name already exists and is an
   * non-empty directory. The caller should hold a read or write directory lock.
   *
   * @param name
   * @param newEntry
   * @return The old entry associated with the name if any; otherwise, return null.
   */
  private DirEntry _putChildEntry(String name, DirEntry newEntry) {
    Directory dir = update();
    DirEntry oldEntry = children.get().put(name, newEntry);
    if (oldEntry != null) {
      if (ObjectIdHelper.isDirectory(oldEntry.getOid())
          && volumeHandler.getDirectoryHandler(oldEntry.getOid()).getChildrenCount() > 0) {
        children.get().put(name, oldEntry);
        return null;
      }
      dir.entries.remove(oldEntry);
    } else {
      update().attrs.nlinks += 1;
    }
    dir.entries.add(newEntry);
    _updateTimestamps();
    return oldEntry;
  }

  /**
   * Remove the child entry with specified name. The caller must hold a read or write directory
   * lock.
   *
   * @param name
   * @return The removed directory entry.
   */
  private DirEntry _removeChildEntry(String name) {
    DirEntry oldEntry = children.get().remove(name);
    if (oldEntry != null) {
      Directory dir = update();
      // AlgorithmUtils.unorderedRemove(dir.entries, oldEntry);
      dir.entries.remove(oldEntry);
      dir.attrs.nlinks -= 1;
      _updateTimestamps();
    }
    return oldEntry;
  }

  public ObjectID getOid() {
    return get().oid;
  }

  public String getName() {
    // Hold read lock
    DirLockHolder lock = lockDirRead();
    String name = get().name;
    // release lock
    lock.release();
    return name;
  }

  private boolean _hasChild(String name) {
    return children.get().containsKey(name);
  }

  /**
   * Sanity check of rename().
   *
   * @param srcName
   * @param dstDh
   * @param dstName
   * @return
   */
  private int _checkRename(String srcName, DirectoryHandler dstDh, String dstName) {
    if (srcName.equals(dstName) && getOid().equals(dstDh.getOid())) {
      String errmsg = String.format("cannot rename '%s' to itself", srcName);
      LOGGER.error(errmsg);
      return KurmaError.INVALID_OPERATION.getValue();
      // throw new InvalidInputException(errmsg);
    }

    DirEntry dstDentry = dstDh.lookup(dstName);
    if (dstDentry != null) {
      DirEntry srcDentry = children.get().get(srcName);
      if (srcDentry == null) {
        LOGGER.error("{} does not exist in directory {}", srcName, get().name);
        return KurmaError.OBJECT_NOT_FOUND.getValue();
      }

      boolean srcIsFile = ObjectIdHelper.isFile(srcDentry.getOid());
      boolean dstIsFile = ObjectIdHelper.isFile(dstDentry.getOid());
      if (srcIsFile && !dstIsFile) {
        LOGGER.error("could not rename file {} to directory {}", srcName, dstName);
        return KurmaError.NOT_DIRECTORY.getValue();
      }
      if (!srcIsFile && dstIsFile) {
        LOGGER.error("could not rename directory {} to file {}", srcName, dstName);
        return KurmaError.NOT_DIRECTORY.getValue();
      }

      // Check if the destination directory is empty or not.
      if (!dstIsFile) {
        DirectoryHandler dh = volumeHandler.getDirectoryHandler(dstDentry.getOid());
        if (dh.getChildrenCount() > 0) {
          return KurmaError.DIRECTORY_NOT_EMPTY.getValue();
        }
      }
    }

    return 0;
  }

  public static Entry<DirLockHolder, DirLockHolder> lockBothDirectories(DirectoryHandler dh1,
      DirectoryHandler dh2, boolean forRead) {
    int r = ObjectIdHelper.compare(dh1.getOid(), dh1.getOid());
    if (r == 0) {
      if (forRead) {
        return new SimpleEntry<DirLockHolder, DirLockHolder>(dh1.lockDirRead(), null);
      } else {
        return new SimpleEntry<DirLockHolder, DirLockHolder>(dh1.lockDirWrite(), null);
      }
    } else if (r < 0) {
      DirLockHolder lock1 = forRead ? dh1.lockDirRead() : dh1.lockDirWrite();
      DirLockHolder lock2 = forRead ? dh2.lockDirRead() : dh2.lockDirWrite();
      return new SimpleEntry<>(lock1, lock2);
    } else {
      DirLockHolder lock1 = forRead ? dh2.lockDirRead() : dh2.lockDirWrite();
      DirLockHolder lock2 = forRead ? dh1.lockDirRead() : dh1.lockDirWrite();
      return new SimpleEntry<>(lock1, lock2);
    }
  }

  public static void unlockBothDirectories(Entry<DirLockHolder, DirLockHolder> locks) {
    if (locks.getValue() != null) {
      locks.getValue().release();
    }
    locks.getKey().release();
  }

  /**
   * Rename the specified object to the dstDh/dstName.
   *
   * @param name Name of the file or directory.
   * @param dstDh The destination directory.
   * @param dstName The new name in the destination directory.
   * @param isLocalOperation true if this is local gateway operation
   * @return The ObjectID of the renamed object, or null in case of failure.
   */
  public Entry<ObjectID, Integer> renameChild(String name, DirectoryHandler dstDh, String dstName,
      boolean isLocalOperation) {
    Entry<DirLockHolder, DirLockHolder> locks = null;
    DirEntry dentry = null;
    DirEntry replacedEntry = null;
    boolean srcDirtyState = false;
    boolean dstDirtyState = false;

    // Hold lock
    locks = lockBothDirectories(this, dstDh, isLocalOperation);
    try {
      LOGGER.info("rename {} to {}", name, dstName);
      int ret = _checkRename(name, dstDh, dstName);
      if (ret != 0) {
        return new SimpleEntry<ObjectID, Integer>(null, ret);
      }

      srcDirtyState = this.isDirty();
      dstDirtyState = dstDh.isDirty();

      dentry = _removeChildEntry(name);
      if (dentry == null) {
        LOGGER.error("{} does not exist in directory {}", name, get().name);
        return new SimpleEntry<ObjectID, Integer>(null, KurmaError.OBJECT_NOT_FOUND.getValue());
      }

      // if the target name already exist, we need to replace it and
      // recycle the old entry
      replacedEntry =
          dstDh._putChildEntry(dstName, DirectoryHelper.newDirEntry(dentry.getOid(), dstName));

      // TODO Verify location of sync block
      synchronized (wrapper) {
        if (this == dstDh) {
          wrapper.persist(zkClient, false);
        } else {
          Preconditions.checkState(!ObjectIdHelper.equals(get().oid, dstDh.get().oid));
          KurmaTransaction txn = zkClient.newTransaction();
          if (wrapper.write(txn) && dstDh.wrapper.write(txn)) {
            zkClient.submitTransaction(txn);
          } else {
            throw new CuratorException("cannot update directory after renaming file");
          }
        }
      }

      // update names in FileHandler or DirectoryHandler
      if (!name.equals(dstName)) {
        if (ObjectIdHelper.isDirectory(dentry.getOid())) {
          // TODO don't save name for file and directory?
          DirectoryHandler renamedDh = volumeHandler.getDirectoryHandler(dentry.getOid());
          if (renamedDh != null) {
            renamedDh.update().setName(dstName);
          }
        }
      }

      if (replacedEntry != null) {
        _collectChildEntry(replacedEntry);
      }

    } catch (Exception e) {
      LOGGER.error(e.getMessage());
      e.printStackTrace();
      // roll back
      // TODO add unittest of roll back
      if (dentry != null) {
        _putChildEntry(name, dentry);
      }
      this.getWrapper().setDirty(srcDirtyState);

      if (replacedEntry == null) {
        dstDh._removeChildEntry(dstName);
      } else {
        dstDh._putChildEntry(dstName, replacedEntry);
      }
      dstDh.getWrapper().setDirty(dstDirtyState);

      return new SimpleEntry<>(null, KurmaError.SERVER_ERROR.getValue());
    } finally {
      // release locks
      unlockBothDirectories(locks);
    }

    return new SimpleEntry<>(dentry.getOid(), 0);
  }

  public List<DirEntry> list() {
    // Hold read lock
    DirLockHolder lock = lockDirRead();
    Collection<DirEntry> values = children.get().values();
    LOGGER.info("listing {} {} with {} items", get().getOid(), children.get().hashCode(),
        children.get().size());
    // release lock
    lock.release();
    List<DirEntry> entries = new ArrayList<DirEntry>(values.size());
    entries.addAll(values);
    return entries;
  }

  public DirEntry lookup(String name) {
    // Hold read lock
    DirLockHolder lock = lockDirRead();
    if (".".equals(name)) {
      DirEntry dentry = new DirEntry();
      dentry.setName(name);
      dentry.setOid(getOid());
      dentry.setTimestamp(get().getAttrs().getCreate_time());
      return dentry;
    }
    if ("..".equals(name)) {
      DirEntry dentry = new DirEntry();
      dentry.setName("..");
      dentry.setOid(get().getParent_oid());
      dentry.setTimestamp(get().getAttrs().getCreate_time());
      return dentry;
    }
    DirEntry dirEntry = children.get().get(name);
    lock.release();
    return dirEntry;
  }

  private void _refreshChildren() {
    Preconditions.checkNotNull(get().entries);
    ConcurrentHashMap<String, DirEntry> newChildren = new ConcurrentHashMap<>();
    for (DirEntry entry : get().entries) {
      newChildren.put(entry.name, entry);
    }
    children.set(newChildren);
  }

  @Override
  public List<String> getSubpaths() {
    // Directory does not have any subpath, so we return an empty list.
    return new ArrayList<String>();
  }

  public VolumeHandler getVolumeHandler() {
    return volumeHandler;
  }

  // TODO Needs external write/read lock?
  public ObjectAttributes getAttrsRef() {
    return get().getAttrs();
  }

  public ObjectAttributes getAttrsCopy() {
    // Hold read lock
    DirLockHolder lock = lockDirRead();
    ObjectAttributes attrs = get().getAttrs().deepCopy();
    // release lock
    lock.release();
    return attrs;
  }

  public void setNoReplicationForChildren() {
    // TODO Verify this lock type
    // Hold read lock
    DirLockHolder lock = lockDirRead();
    for (DirEntry dirEntry : children.get().values()) {
      ObjectID childOid = dirEntry.getOid();
      if (ObjectIdHelper.isDirectory(childOid)) {
        DirectoryHandler childDh = volumeHandler.getDirectoryHandler(childOid);
        AttributesHelper.setNoReplicationBit(childDh.getAttrsRef());
        childDh.setNoReplicationForChildren();
      } else {
        FileHandler childFh = volumeHandler.getLoadedFile(childOid);
        if (childFh != null) {
          AttributesHelper.setNoReplicationBit(childFh.getAttrsRef());
          volumeHandler.putFile(childFh);
        } else {
          // TODO Handle this
        }
      }
    }
    lock.release();
  }
}
