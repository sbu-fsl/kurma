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
import java.security.GeneralSecurityException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.BiFunction;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.KurmaException.NoZNodeException;
import edu.stonybrook.kurma.cloud.KvsFacade;
import edu.stonybrook.kurma.cloud.KvsManager;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.AttributesHelper;
import edu.stonybrook.kurma.helpers.BlockMapHelper;
import edu.stonybrook.kurma.helpers.FileHelper;
import edu.stonybrook.kurma.helpers.KeyMapHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.SnapshotHelper;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.meta.BlockMap;
import edu.stonybrook.kurma.meta.DirEntry;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.Snapshot;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.ZkClient;
import edu.stonybrook.kurma.util.AlgorithmUtils;
import edu.stonybrook.kurma.util.AuthenticatedEncryption;
import edu.stonybrook.kurma.util.EncryptThenAuthenticate;
import edu.stonybrook.kurma.util.FileUtils;
import edu.stonybrook.kurma.util.LoggingUtils;
import edu.stonybrook.kurma.util.RangeLock;
import edu.stonybrook.kurma.util.ThriftUtils;

public class FileHandler extends AbstractHandler<File> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FileHandler.class);
  public static final String TOMESTONE_NAME = "TOMESTONE";
  public static final String BLOCKMAP_PREFIX = "BLOCKMAP.";
  public static final String KEYMAP_NAME = "KEYMAP";

  /**
   * Max number of blocks in a BlockMap.
   */
  public static final int MAX_BLOCK_MAP_LENGTH = (1 << 16);
  public static final int MAX_BLOCK_MAP_LEN_SHIFT = 16;

  /**
   * Max number of BlockMaps to be cached.
   *
   * We should limit the concurrent threads to access no more than this number of BlockMaps.
   */
  public static final int BLOCK_MAP_CACHE_SIZE = 8;

  /**
   * Min/Max block size shift.
   */
  public static final int MIN_BLOCK_SHIFT = 16; // 64KB
  public static final int MAX_BLOCK_SHIFT = 20; // 1MB

  private ZkClient zkClient = null;

  // Each file opener should take a refcount.
  private AtomicInteger refcount = new AtomicInteger(0);

  /**
   * The blockmap that is currently cached. TODO use in combination with curator's NodeCache?
   */
  private LoadingCache<Long, TWrapper<BlockMap>> blockMaps;

  private BlockKeyGenerator blockKeyGen = null;

  private KvsFacade kvsFacade = null;

  private VolumeHandler volumeHandler;

  private AuthenticatedEncryption aeCipher;

  private final IGatewayConfig config;

  private SecretKey fileKey;

  private int blockSize;
  private int blockShift = 0;
  private int blockMapShift;

  private boolean loaded = false;

  private BlockExecutor blockExecutor;

  public class SnapshotInfo {
    public String name;
    public String description;
    public long createTime;
    public long updateTime;
    public int id; // unsigned
    public ObjectAttributes attrs;
  }

  private HashMap<String, SnapshotInfo> snapshots = null;
  private HashMap<Integer, SnapshotInfo> snapshotsById = null;
  private int maxSnapshotId = 0;

  /**
   * snapshotBlocks[i] is set if the current version Block-i of this file is used by any snapshots.
   * snapshotBlocks can be null of files without any snapshots.
   */
  private BitSet snapshotBlocks = null;

  /**
   * Protects the file's metadata including the size of the BlockMap but EXCLUDING the version
   * numbers of the BlockMap.
   */
  private ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();

  /**
   * Protects the address space of this file, and the version numbers of the BlockMaps. To avoid
   * deadlock, @rangeLock should be taken before @rwlock in cases where both locks need to be taken.
   * Also protect the snapshot data blocks.
   */
  private final RangeLock rangeLock = new RangeLock();

  public FileHandler(ObjectID oid, VolumeHandler vh) {
    Preconditions.checkArgument(ObjectIdHelper.isFile(oid));
    File file = FileHelper.newFile(oid);
    setWrapper(new TWrapper<File>(vh.getObjectZpath(oid), file));
    this.volumeHandler = vh;
    this.zkClient = volumeHandler.getZkClient();
    this.config = vh.getConfig();
    this.blockExecutor = vh.getBlockExecutor();
    this.kvsFacade = vh.getConfig().getDefaultKvsFacade();

    setBlockShift(config.getBlockShift());

    initBlockMapCache();
  }

  private RangeLockHolder lockReadRange(long offset, long length) {
    synchronized (rangeLock) {
      while (!rangeLock.lockRead(offset, offset + length)) {
        try {
          rangeLock.wait();
        } catch (InterruptedException e) {
          LOGGER.debug("waiting for ReadRangeLock is interrupted", e);
        }
      }
    }
    return new RangeLockHolder(offset, length, true);
  }

  private void unlockReadRange(long offset, long length) {
    synchronized (rangeLock) {
      rangeLock.unlockRead(offset, offset + length);
      rangeLock.notify();
    }
  }

  private RangeLockHolder lockWriteRange(long offset, long length) {
    synchronized (rangeLock) {
      while (!rangeLock.lockWrite(offset, offset + length)) {
        try {
          rangeLock.wait();
        } catch (InterruptedException e) {
          LOGGER.debug("waiting for WriteRangeLock is interrupted", e);
        }
      }
    }
    return new RangeLockHolder(offset, length, false);
  }

  private void unlockWriteRange(long offset, long length) {
    synchronized (rangeLock) {
      rangeLock.unlockWrite(offset, offset + length);
      rangeLock.notify();
    }
  }

  class RangeLockHolder {
    public RangeLockHolder() {
      // empty holder
      released = true;
      offset = 0;
      length = 0;
    }

    public RangeLockHolder(long off, long len, boolean isRead) {
      offset = off;
      length = len;
      this.isRead = isRead;
    }

    public void release() {
      if (!released) {
        if (isRead) {
          unlockReadRange(offset, length);
        } else {
          unlockWriteRange(offset, length);
        }
        released = true;
      }
    }

    private long offset;
    private long length;
    private boolean isRead;
    private boolean released;
  }

  FileLockHolder lockFileRead() {
    rwlock.readLock().lock();
    return new FileLockHolder(true);
  }

  FileLockHolder lockFileWrite() {
    rwlock.writeLock().lock();
    return new FileLockHolder(false);
  }

  class FileLockHolder {
    public FileLockHolder() {
      // empty holder
      released = true;
    }

    public FileLockHolder(boolean isRead) {
      this.isRead = isRead;
      released = false;
    }

    public void release() {
      if (!released) {
        if (isRead) {
          rwlock.readLock().unlock();
        } else {
          rwlock.writeLock().unlock();
        }
        released = true;
      }
    }

    private boolean isRead;
    private boolean released;
  }

  private void lockBlockMapContains(long offset, boolean read) {
    long bmOffset = (offset >> blockMapShift) << blockMapShift;
    long bmLength = 1L << blockMapShift;
    if (read) {
      lockReadRange(bmOffset, bmLength);
    } else {
      lockWriteRange(bmOffset, bmLength);
    }
  }

  private void unlockBlockMapContains(long offset, boolean read) {
    long bmOffset = (offset >> blockMapShift) << blockMapShift;
    long bmLength = 1L << blockMapShift;
    if (read) {
      unlockReadRange(bmOffset, bmLength);
    } else {
      unlockWriteRange(bmOffset, bmLength);
    }
  }

  private void initBlockMapCache() {
    CacheLoader<Long, TWrapper<BlockMap>> loader = new CacheLoader<Long, TWrapper<BlockMap>>() {
      @Override
      public TWrapper<BlockMap> load(Long offset) throws Exception {
        TWrapper<BlockMap> bm = new TWrapper<BlockMap>(getBlockMapZpath(offset), new BlockMap());
        bm.read(zkClient);
        return bm;
      }
    };

    RemovalListener<Long, TWrapper<BlockMap>> listener =
        new RemovalListener<Long, TWrapper<BlockMap>>() {
          @Override
          public void onRemoval(RemovalNotification<Long, TWrapper<BlockMap>> notification) {
            TWrapper<BlockMap> blockMap = notification.getValue();
            try {
              if (notification.getCause() == RemovalCause.SIZE
                  || notification.getCause() == RemovalCause.COLLECTED
                  || notification.getCause() == RemovalCause.EXPIRED) {
                if (blockMap.isDirty() && !blockMap.persist(zkClient, false)) {
                  LOGGER.error("could not write dirty blockmap back");
                }
              }
            } catch (Exception e) {
              LOGGER.error("could not write/create blockMap", e);
            }
          }
        };

    blockMaps = CacheBuilder.newBuilder().maximumSize(BLOCK_MAP_CACHE_SIZE)
        .removalListener(listener).build(loader);
  }

  public boolean create(ObjectID parentOid, String name, ObjectAttributes attrs) {
    KurmaTransaction txn = zkClient.newTransaction();
    boolean res = create(parentOid, name, attrs, config.getDefaultKvsFacade(), null, null, txn);
    zkClient.submitTransaction(txn);
    return res;
  }

  private void setKvsFacade(KvsFacade facade) {
    if (kvsFacade == null) {
      kvsFacade = facade;
      File file = update();
      file.setKvs_type(kvsFacade.getKvsType());
      file.setKvs_ids(KvsManager.kvsListToString(kvsFacade.getKvsList()));
    }
  }

  public boolean create(ObjectID parentOid, String name, ObjectAttributes attrs, KvsFacade facade,
      SecretKey fileKey, KeyMap keymap, KurmaTransaction txn) {

    try {
      zkClient.ensurePath(getZpath(), true);
    } catch (Exception e1) {
      LOGGER.error("could not create zpath prefix", e1);
      e1.printStackTrace();
      return false;
    }

    boolean res = true;
    rwlock.writeLock().lock();

    try {
      // set up file
      File file = update();

      file.setParent_oid(parentOid);
      file.setName(name);
      if (attrs.isSetBlock_shift()) {
        setBlockShift(attrs.getBlock_shift());
      } else {
        if (isBlockSet()) {
          attrs.setBlock_shift(blockShift);
        } else {
          attrs.unsetBlock_shift(); // the block size is not known
                                    // until first write
        }
      }

      setKvsFacade(facade);

      attrs.setFilesize(0); // Ignore the file size in the supplied
                            // attributes
      attrs.setNblocks(0);
      attrs.setNlinks(1); // Ignore the link count in the supplied
                          // attributes
      file.setAttrs(attrs.deepCopy());

      if (fileKey == null) {
        Entry<SecretKey, KeyMap> p = volumeHandler.generateKey();
        fileKey = p.getKey();
        keymap = p.getValue();
      }

      res = wrapper.create(txn);
      _createFileKey(fileKey, keymap, txn);

      TWrapper<BlockMap> bmWrapper = addNewBlockMap(0L);
      blockMaps.put(0L, bmWrapper);
      bmWrapper.create(txn);

      LOGGER.info("file {} written to znode {}", name, getZpath());

      volumeHandler.incrementObjectCount();
      setLoaded(true);
    } catch (GeneralSecurityException e) {
      LOGGER.error("could not generate KeyMap", e);
      res = false;
    } catch (CuratorException e) {
      LOGGER.error("could not create file", e);
      res = false;
    } catch (NullPointerException e) {
      e.printStackTrace();
      res = false;
    } finally {
      rwlock.writeLock().unlock();
    }
    return res;
  }

  /**
   * Load the file's metadata from ZooKeeper.
   *
   * This method is NOT thread-safe because rangeLock is not taken internally. So the caller need to
   * make sure no other file-system operations are issued to this object before "load()" returns.
   *
   * @return Whether the loading is successful.
   */
  public boolean load() {
    boolean res = true;
    // Before the file is loaded, we don't know the file size yet, so we
    // cannot determine its size.
    // RangeLockHolder rangeLockHolder = lockWriteRange(0, getFileSize());
    FileLockHolder fileLockHolder = lockFileWrite();
    try {
      // load file main znode
      ObjectID oldOid = getOid();
      if (!wrapper.read(zkClient)) {
        LOGGER.error("could not load file");
        res = false;
      } else {
        assert (ObjectIdHelper.equals(oldOid, getOid()));
        setBlockShift(get().attrs.block_shift);

        // load the key file and snapshots
        res = loadKey() && _loadSnapshots();

        setKvsFacade(FileUtils.getFileKvsFacade(get(), volumeHandler.getFacadeManager()));
        setLoaded(res);
      }
    } catch (Exception e) {
      LOGGER.error("could not load FileHandler from ZK", e);
      e.printStackTrace();
      res = false;
    } finally {
      fileLockHolder.release();
      // rangeLockHolder.release();
    }
    return res;
  }

  public String getSnapshotZpath(String snapshotName) {
    if (snapshotName == null) {
      return String.format("%s/SNAPSHOTS", getZpath());
    } else {
      return String.format("%s/SNAPSHOTS/%s", getZpath(), snapshotName);
    }
  }

  /**
   * Re-load snapshot information.
   *
   * The caller should hold rangeLock and rwLock during this call.
   *
   * @snapshots and @snapshotBlocks will be cleared and re-built.
   *
   * @return Whether the re-loading is successfully or not.
   */
  private boolean _loadSnapshots() {
    try {
      List<String> snapshotNames = SnapshotHelper.listSnapshots(zkClient, getSnapshotZpath(null));
      if (snapshotNames == null || snapshotNames.isEmpty()) {
        return true; // No snapshots
      }
      int nblocks = (int) ((_getFileSize() + (1 << blockShift) - 1) >> blockShift);
      if (snapshots == null) {
        snapshots = new HashMap<>();
      } else {
        snapshots.clear();
      }
      if (snapshotsById == null) {
        snapshotsById = new HashMap<>();
      } else {
        snapshotsById.clear();
      }
      if (snapshotBlocks == null) {
        snapshotBlocks = new BitSet(nblocks);
      } else {
        snapshotBlocks.clear();
      }
      // TODO avoid this for read-only files, or postpone this until
      // writes
      for (String sn : snapshotNames) {
        Snapshot snapshot = SnapshotHelper.readSnapshot(zkClient, getSnapshotZpath(sn));
        Iterator<Long> ver_it = snapshot.getBlocks().getVersionsIterator();
        Iterator<Short> gw_it = snapshot.getBlocks().getLast_modifierIterator();
        int limit = java.lang.Math.min(snapshot.getBlocks().getVersionsSize(), nblocks);
        for (int i = 0; i < limit; ++i) {
          Entry<Long, Short> ver_gw = _getBlockVersion(i, false);
          Long ver = ver_it.next();
          Short gw = gw_it.next();
          if (ver.equals(ver_gw.getKey()) && gw.equals(ver_gw.getValue())) {
            snapshotBlocks.set(i);
          }
        }
        SnapshotInfo info = new SnapshotInfo();
        info.name = sn;
        info.createTime = snapshot.getCreate_time();
        info.updateTime = snapshot.getUpdate_time();
        info.description = snapshot.getDescription();
        info.attrs = snapshot.getSaved_file().getAttrs();
        info.id = snapshot.getId();
        snapshots.put(sn, info);
        snapshotsById.put(snapshot.getId(), info);
        maxSnapshotId = Math.max(maxSnapshotId, snapshot.getId());
      }
    } catch (CuratorException e) {
      e.printStackTrace();
      LOGGER.error("Failed to load snapshots", e);
      return false;
    }
    return true;
  }

  public boolean flush() {
    boolean res = true;
    rwlock.readLock().lock();
    try {
      KurmaTransaction txn = zkClient.newTransaction();
      if (isDirty()) {
        wrapper.write(txn);
      }
      for (long l = getBlockMapCount() - 1; l >= 0; --l) {
        TWrapper<BlockMap> bm = blockMaps.getIfPresent(l);
        if (bm != null && bm.isDirty()) {
          bm.persist(txn);
        }
      }
      zkClient.submitTransaction(txn);
    } catch (Exception e) {
      LOGGER.error("could not flush dirty data to ZK", e);
      e.printStackTrace();
      res = false;
    } finally {
      rwlock.readLock().unlock();
    }
    return res;
  }

  /**
   * The caller should hold a write lock.
   *
   * @param key Secret file key
   * @throws GeneralSecurityException
   */
  private void _setFileKey(SecretKey key) throws GeneralSecurityException {
    if (fileKey == null) {
      fileKey = key;
      byte[] keybuf = fileKey.getEncoded();
      wrapper.update().setKey(keybuf);
      blockKeyGen = new BlockKeyGenerator(getOid(), keybuf);
      aeCipher = new EncryptThenAuthenticate(fileKey);
    }
  }

  /**
   * Initialize file's key to the specified value and also use the key to generate cipher.
   * @param key
   * @throws GeneralSecurityException
   * @throws CuratorException
   */
  private void _createFileKey(SecretKey key, KeyMap keymap, KurmaTransaction txn) throws GeneralSecurityException, CuratorException {
    assert (fileKey == null);
    fileKey = key;
    byte[] keybuf = fileKey.getEncoded();
    if (config.useKeyMap()) {
      if (keymap == null) {
        keymap = KeyMapHelper.newKeyMap(key, config);
      }
      txn.create(getKeyMapZpath(), ThriftUtils.encode(keymap, false));
    } else {
      wrapper.update().setKey(keybuf);
    }
    blockKeyGen = new BlockKeyGenerator(getOid(), keybuf);
    aeCipher = new EncryptThenAuthenticate(fileKey);
  }

  public SecretKey getFileKey() {
    return fileKey;
  }

  private boolean loadKey() {
    try {
      SecretKey fileKey = null;
      if (config.useKeyMap()) {
        TWrapper<KeyMap> kmWrapper = new TWrapper<>(getKeyMapZpath(), new KeyMap(), false, true);
        kmWrapper.read(zkClient);
        fileKey = KeyMapHelper.readKeyMap(kmWrapper.get(), config);
      } else {
        fileKey = new SecretKeySpec(wrapper.get().getKey(), EncryptThenAuthenticate.ENCRYPT_ALGORITHM);
      }
      _setFileKey(fileKey);
    } catch (Exception e) {
      e.printStackTrace();
      LOGGER.error("could not load file key", e);
      return false;
    }
    return true;
  }

  public boolean delete() {
    LOGGER.info("deleting file {}", getOid());
    boolean res = true;

    lockWriteRange(0, Long.MAX_VALUE);
    rwlock.writeLock().lock();
    try {
      KurmaTransaction txn = zkClient.newTransaction();

      HashSet<FileBlock> blocks =
          new HashSet<FileBlock>(_getValidFileBlocks(0, _getFileSize(), false));

      if (!loaded) {
        load();
      }

      if (snapshots != null && !snapshots.isEmpty()) {
        for (String sn : snapshots.keySet()) {
          Snapshot snapshot = SnapshotHelper.readSnapshot(zkClient, getSnapshotZpath(sn));
          Iterator<Long> ver_it = snapshot.getBlocks().getVersionsIterator();
          Iterator<Short> gw_it = snapshot.getBlocks().getLast_modifierIterator();
          for (long l = 0; ver_it.hasNext(); ++l) {
            Long ver = ver_it.next();
            Short gw = gw_it.next();
            blocks.add(new FileBlock(this, l << blockShift, blockSize, ver, gw));
          }
          txn.delete(getSnapshotZpath(sn));
        }
        txn.delete(getSnapshotZpath(null));
      }

      for (int i = 0; i < getBlockMapCount(); ++i) {
        txn.delete(getBlockMapZpath((1L << blockMapShift) * i));
      }

      LOGGER.info("DELETION: collecting old blocks {}", blocks);
      volumeHandler.getGarbageCollector().collectBlocks(blocks);
      volumeHandler.decrementObjectCount();

      txn.delete(getKeyMapZpath());
      wrapper.delete(txn);
      zkClient.submitTransaction(txn);
    } catch (Exception e) {
      LOGGER.error("could not delete FileHandler", e);
      //e.printStackTrace();
      res = false;
    } finally {
      rwlock.writeLock().unlock();
      unlockWriteRange(0, Long.MAX_VALUE);
    }
    return res;
  }

  public boolean isBlockAligned(long n) {
    return Long.numberOfTrailingZeros(n) >= blockShift;
  }

  private TWrapper<BlockMap> addNewBlockMap(long offset) {
    long bmOffset = offset >> blockMapShift;
    TWrapper<BlockMap> bmWrapper = new TWrapper<BlockMap>(getBlockMapZpath(offset), new BlockMap(), true, false);
    BlockMap bm = bmWrapper.update();
    bm.setVersions(new ArrayList<Long>());
    bm.setLast_modifier(new ArrayList<Short>());
    bm.offset = bmOffset << MAX_BLOCK_MAP_LEN_SHIFT;
    blockMaps.put(bmOffset, bmWrapper);
    return bmWrapper;
  }

  /**
   * Get the BlockMap that contains the version number of the block with the given offset.
   *
   * @param offset block offset in bytes
   * @return BlockMap contains the block
   * @throws ExecutionException
   */
  public TWrapper<BlockMap> _getBlockMap(long offset, boolean createIfNotExist)
      throws ExecutionException {
    long bmOffset = offset >> blockMapShift;
    TWrapper<BlockMap> bmWrapper = null;
    try {
      bmWrapper = blockMaps.get(bmOffset);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof NoZNodeException) {
        if (createIfNotExist) {
          LOGGER.info("============================{} creating blockmap{} at offset {}", this,
              blockMaps, bmOffset);
          bmWrapper = addNewBlockMap(offset);
        } else {
          LOGGER.warn("{} for blockmap {}, znode does not exist: {} ", this, blockMaps, getZpath());
        }
      } else {
        LOGGER.error("unknown error", e);
        throw e;
      }
    }
    return bmWrapper;
  }

  /**
   * Save both file attributes and block maps to Zookeeper.
   *
   * @throws Exception
   */
  private void _saveToZk() throws Exception {
    try {
      if (!get().isSetKvs_type()) {
        File f = update();
        f.setKvs_type(kvsFacade.getKvsType());
        f.setKvs_ids(KvsManager.kvsListToString(kvsFacade.getKvsList()));
      }
      KurmaTransaction txn = zkClient.newTransaction();
      TWrapper<BlockMap> bmWrapper = _getBlockMap(0L, true);
      if (wrapper.write(txn) && bmWrapper.persist(txn)) {
        zkClient.submitTransaction(txn);
      } else {
        throw new CuratorException("cannot save to ZK");
      }
    } catch (Exception e) {
      if (e.getCause() instanceof KeeperException.NoNodeException) {
        KurmaTransaction txn = zkClient.newTransaction();
        TWrapper<BlockMap> bmWrapper = _getBlockMap(0L, true);
        if (wrapper.write(txn) && bmWrapper.create(txn)) {
          zkClient.submitTransaction(txn);
        }
      } else {
        throw e;
      }
    }
  }

  public boolean save() {
    FileLockHolder fileLockHolder = lockFileWrite();
    boolean res = true;
    try {
      _saveToZk();
    } catch (Exception e) {
      LOGGER.error("cannot save file", e);
      res = false;
    } finally {
      fileLockHolder.release();
    }
    return res;
  }

  /**
   * Given an offset in the file, get its block index inside the residing blockmap.
   *
   * @param offset target offset in the file's address space.
   * @return index inside the blockmap that contains the given offset.
   */
  private int getBlockMapIndex(long offset) {
    return (int) ((offset >> blockShift) & ((1 << MAX_BLOCK_MAP_LEN_SHIFT) - 1));
  }

  /**
   * Build file blocks at [begin, end) excluding blocks containing only file holes. The caller
   * should hold the range lock of [begin, end). Note that "end" may be larger than current file
   * when we are shrinking the file.
   *
   * @param begin -- offset beging
   * @param end -- offset end
   * @param excludeSnapshot -- exclude blocks used by snapshots
   * @return A list of valid FileBlocks that [begin, end) covers.
   */
  private List<FileBlock> _getValidFileBlocks(long begin, long end, boolean excludeSnapshot) {
    long validEnd = Long.min(_getDataSize(), end);
    if (validEnd <= begin) {
      return new ArrayList<FileBlock>(0);
    }
    long blkBeg = begin >> blockShift;
    long blkEnd = (validEnd - 1) >> blockShift;
    List<FileBlock> blocks = new ArrayList<FileBlock>((int) (blkEnd - blkBeg + 1));
    for (long i = blkBeg; i <= blkEnd; ++i) {
      if (excludeSnapshot && snapshotBlocks != null && snapshotBlocks.get((int) i)) {
        continue;
      }
      long offset = (i << blockShift);
      TWrapper<BlockMap> bm = null;
      try {
        bm = _getBlockMap(offset, false);
      } catch (ExecutionException e) {
        LOGGER.error("could not read blockmap", e);
        e.printStackTrace();
        System.exit(1);
      }
      int index = getBlockMapIndex(offset);
      long ver = bm.get().getVersions().get(index);
      short gw = bm.get().getLast_modifier().get(index);
      blocks.add(new FileBlock(this, offset, blockSize, ver, gw));
    }
    return blocks;
  }

  public Entry<Long, Short> getBlockVersion(long offset) {
    try {
      lockBlockMapContains(offset, true);
      return _getBlockVersion(offset, false);
    } finally {
      unlockBlockMapContains(offset, true);
    }
  }

  private Entry<Long, Short> _getBlockVersion(long offset, boolean createIfNotExist) {
    Long version = 0l;
    Short gw = 0;
    try {
      TWrapper<BlockMap> bm = _getBlockMap(offset, createIfNotExist);
      int i = getBlockMapIndex(offset);
      if (bm.get().versions != null && bm.get().versions.size() > i) {
        version = bm.get().versions.get(i);
        gw = bm.get().last_modifier.get(i);
      }
      if (version < 0 && createIfNotExist) {
        version ^= (1L << 63);
      }
    } catch (ExecutionException e) {
      if (e.getCause() instanceof NoZNodeException) {
        LOGGER.warn("Blockmap at {} not exist: {}", offset, getZpath());
      } else {
        LOGGER.error("unknown error", e);
      }
    }
    return new AbstractMap.SimpleEntry<>(version, gw);
  }

  /**
   * Break a large buffer into per-block buffers.
   *
   * REQUIRES: the caller hold a read or write file lock
   *
   * @param offset Offset within the file @buffer is mapped onto
   * @param length The length of @buffer to be broken into blocks
   * @param limit The max length the blocks can cover
   * @param buffer memory buffer for reading or writing
   * @param isWrite is write or read
   * @return a list of FileBlock that contains the per-block buffers
   */
  protected List<FileBlock> _breakBufferIntoBlocks(long offset, int length, long limit,
      ByteBuffer buffer, boolean isWrite) {
    Preconditions.checkArgument(isBlockAligned(offset),
        String.format("offset %d is not aligned with block size (%d)", offset, blockSize));
    Preconditions.checkArgument((offset + length) <= limit, "exceeds limit");
    Preconditions.checkArgument((isBlockAligned(length) || offset + length == limit),
        "length is not block- or file-aligned");
    Preconditions.checkArgument(buffer.hasArray());
    Preconditions.checkArgument(buffer.remaining() >= length);
    int n = (length >> blockShift) + (isBlockAligned(length) ? 0 : 1);
    List<FileBlock> blocks = new ArrayList<FileBlock>(n);
    byte[] array = buffer.array();
    int arrayOffset = buffer.arrayOffset() + buffer.position();
    for (int i = 0; i < n; ++i) {
      int offsetInBuffer = (i << blockShift);
      long offsetInFile = offset + offsetInBuffer;
      int len = Integer.min(length - offsetInBuffer, blockSize);
      ByteBuffer buf = ByteBuffer.wrap(array, offsetInBuffer + arrayOffset, len);
      Entry<Long, Short> pair = _getBlockVersion(offsetInFile, isWrite);
      long version = isWrite ? pair.getKey() + 1 : pair.getKey();
      short gw = isWrite ? config.getGatewayId() : pair.getValue();
      blocks.add(i, new FileBlock(this, offsetInFile, len, version, gw, buf, isWrite));
    }
    return blocks;
  }

  public BlockKeyGenerator getBlockKeyGenerator() {
    return blockKeyGen;
  }

  /**
   * Read file data.
   *
   * @param offset Blocked-aligned offset
   * @param length Blocked-aligned length
   * @return A ByteBuffer of the data. If the read range [offset, offset + length) is completely
   *         within the address space of the file, it is guaranteed that this function read as much
   *         as @length bytes; otherwise, it will read until the end of the file. Return null in
   *         case of errors.
   */
  public AbstractMap.SimpleEntry<ByteBuffer, ObjectAttributes> read(long offset, int length) {
    LOGGER.info("{}: reading file at {} for {} bytes", this, offset, length);
    Preconditions.checkArgument(isBlockAligned(offset));
    RangeLockHolder rangeLockHolder = lockReadRange(offset, length);
    FileLockHolder fileLockHolder = lockFileRead();
    ObjectAttributes attrs = getFile().attrs.deepCopy();
    try {
      long filesize = _getFileSize();
      if (offset >= filesize) {
        LOGGER.info("nothing to read at {} file size is {}", offset, filesize);
        // Nothing to read; returns an empty buffer.
        return new AbstractMap.SimpleEntry<ByteBuffer, ObjectAttributes>(ByteBuffer.allocate(0),
            attrs);
      }
      long datasize = _getDataSize();
      fileLockHolder.release();

      length = Integer.min(length, (int) (filesize - offset));
      byte[] buffer = new byte[length];

      if (offset < datasize) {
        // We only need to read if this read covers the any data other
        // than the hole at the end.
        if (offset + length > datasize) {
          length = (int) (datasize - offset);
        }

        List<FileBlock> blocks =
            _breakBufferIntoBlocks(offset, length, datasize, ByteBuffer.wrap(buffer), false);
        if (!blockExecutor.read(blocks, aeCipher, kvsFacade)) {
          LOGGER.error("read failed {}", ObjectIdHelper.getShortId(getOid()));
          return null;
        }
      }

      rwlock.writeLock().lock();
      AttributesHelper.updateAccessTime(getFile().attrs);
      rwlock.writeLock().unlock();

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("read {} bytes at {} of {}: {}", length, offset, this,
            LoggingUtils.hash(buffer));
      }

      return new AbstractMap.SimpleEntry<ByteBuffer, ObjectAttributes>(ByteBuffer.wrap(buffer),
          attrs);
    } catch (Exception e) {
      String errmsg =
          String.format("read to file %s at [%d, +%d] failed", getOid(), offset, length);
      LOGGER.error(errmsg, e);
    } finally {
      fileLockHolder.release();
      rangeLockHolder.release();
    }
    return null;
  }

  public boolean write(long offset, ByteBuffer data) {
    return write(offset, data, Optional.empty());
  }

  private void _updateTimestampsFromRemote(ObjectAttributes attrs) {
    long ctime = attrs.getChange_time();
    long mtime = attrs.getModify_time();
    File file = update();
    ObjectAttributes targetAttrs = file.getAttrs();
    LOGGER.debug("old ctime is {} new ctime is {}", ctime, targetAttrs.getChange_time());
    targetAttrs.setChange_time(Math.max(targetAttrs.getChange_time(), ctime));
    targetAttrs.setModify_time(Math.max(targetAttrs.getModify_time(), mtime));
    targetAttrs.setRemote_change_time(ctime);
  }

  /**
   * Update the version number and last_modifier in the BlockMap. Called as a result of update by
   * remote gateways.
   *
   * TODO restrict write to a single blockmap?
   *
   * TODO add journaling of the version updates, which might lose upon crash
   *
   * @param offset block-aligned offset in bytes
   * @param length length of the range to be update (in bytes)
   * @param gwid GatewayId of the last_modifier
   * @param newVersions new versions of the update blocks
   * @throws Exception
   */
  public boolean updateBlockVersions(long offset, long length, short gwid,
      List<Long> newVersions, ObjectAttributes attrs) throws Exception {
    // We need to enlarge the file size if any block is beyond the size of the file.
    LOGGER.debug("update block version from gateway replicator: {} {}+", offset, length);
    boolean res = false;
    long newFileSize = offset + length;
    Entry<FileLockHolder, RangeLockHolder> locks = lockForTruncate(newFileSize);
    RangeLockHolder rangeLockHolder = locks.getValue();
    FileLockHolder fileLockHolder = locks.getKey();
    try {
      if (newFileSize > _getFileSize()) {
        assert (newFileSize == attrs.getFilesize());
        // enlarge file size
        _setDataSize(newFileSize);
        res = _truncate(newFileSize, true);
        _updateTimestampsFromRemote(attrs);
      }
      if (res) {
        fileLockHolder.release();
        _updateBlockVersions(offset, length, gwid, Optional.of(newVersions), true);
        res = true;
      }
    } finally {
      rangeLockHolder.release();
      // duplicate release is fine.
      fileLockHolder.release();
    }
    return res;
  }

  private int _updateBlockVersions(long offset, long length, short gwid,
      Optional<List<Long>> newVersions, boolean createBlockMapIfNotExist) throws Exception {
    AtomicInteger newBlocks = new AtomicInteger(0);
    List<FileBlock> oldBlocks = new ArrayList<FileBlock>();
    _iterBlocks(offset, length, createBlockMapIfNotExist, (bm, i) -> {
      int len = bm.versions.size();
      assert (i < len);
      Entry<Long, Short> old = BlockMapHelper.incrementVersion(bm, i, gwid);
      if (old.getKey() > 0) {
        // Only GC blocks that are not used by any snapshots
        if (snapshotBlocks == null || i >= snapshotBlocks.size() || !snapshotBlocks.get(i)) {
          oldBlocks.add(new FileBlock(this, ((i + 0L) << blockShift), blockSize, old.getKey(),
              old.getValue()));
        }
      } else {
        newBlocks.incrementAndGet();
      }
      if (snapshotBlocks != null && i < snapshotBlocks.size()) {
        snapshotBlocks.clear(i);
      }
      newVersions.ifPresent(l -> l.add(old.getKey() + 1));
      return true;
    });
    LOGGER.info("collecting old blocks {}", oldBlocks);
    volumeHandler.getGarbageCollector().collectBlocks(oldBlocks);
    return newBlocks.get();
  }

  /**
   * Update the version number and last_modifier in the BlockMap. Called as a result of update by
   * remote gateways. Caller is required to hold file and range lock.
   *
   * @param offset block-aligned offset in bytes
   * @param length length of the range to be update (in bytes)
   * @param gwid GatewayId of the last_modifier
   * @param newVersions new versions of the update blocks
   * @throws Exception
   */
  public boolean _resolveBlockVersions(long offset, long length, short gwid, Long newVersion,
      ObjectAttributes attrs) {
    try {
      _updateBlockVersionToResolveConflict(offset, length, gwid, newVersion, true);
      _updateTimestampsFromRemote(attrs);
    } catch (Exception e) {
      LOGGER.error("Unable to update block versions" + e);
    }
    return true;
  }

  private void _updateBlockVersionToResolveConflict(long offset, long length, short gwid,
      Long newVersion, boolean createBlockMapIfNotExist) throws Exception {
    List<FileBlock> oldBlocks = new ArrayList<FileBlock>(1);
    _iterBlocks(offset, length, createBlockMapIfNotExist, (bm, i) -> {
      int len = bm.versions.size();
      assert (i < len);
      Entry<Long, Short> old = BlockMapHelper.updateBlock(bm, i, gwid, newVersion);
      oldBlocks.add(new FileBlock(this, offset, blockSize, old.getKey(), old.getValue()));
      return true;
    });
    volumeHandler.getGarbageCollector().collectBlocks(oldBlocks);
  }

  private void _initializeBlockVersions(long offset, long length, short gwid,
      Optional<List<Long>> newVersions) throws Exception {
    _iterBlocks(offset, length, true, (bm, i) -> {
      int len = bm.versions.size();
      assert (len >= i);
      if (len > i) {
        long v = bm.versions.get(i);
        if (v > 0) {
          final long nv = v ^ (1L << 63);
          LOGGER.debug("set version of block {} from {} to {}", i, v, nv);
          bm.versions.set(i, nv);
          bm.last_modifier.set(i, gwid);
          newVersions.ifPresent(l -> l.set(i, nv));
        } else if (v == 0) {
          newVersions.ifPresent(l -> l.set(i, 0L));
        }
      } else { // len == i
        assert (len == i);
        // extend the block version array if necessary
        bm.versions.add(0L);
        bm.last_modifier.add(gwid);
        bm.length = bm.versions.size();
        newVersions.ifPresent(l -> l.add(0L));
      }
      return true;
    });
  }

  private void _iterBlocks(long offset, long length, boolean createBlockMapIfNotExist,
      BiFunction<BlockMap, Integer, Boolean> fn) throws Exception {
    for (long end = offset + length; offset < end;) {
      TWrapper<BlockMap> bmWrapper = _getBlockMap(offset, createBlockMapIfNotExist);
      BlockMap bm = bmWrapper.update();
      long bmEnd = bm.getOffset() + (1L << blockMapShift);
      int i = getBlockMapIndex(offset); // index into the block map
      for (long itEnd = Long.min(bmEnd, end); offset < itEnd; offset += blockSize) {
        if (!fn.apply(bm, i++)) {
          return;
        }
      }
    }
  }

  /**
   * Iterate blocks to check for any conflict between local and remote changes It will get local
   * block and calls provided function to compare it with remote block
   *
   * @param offset block-aligned offset in bytes
   * @param length length of the range to be update (in bytes)
   * @param attrs attrs received from remote gateway
   * @param fn function that will be called to compare local and remote block
   * @throws Exception
   */
  public boolean iterBlockForConflictResolve(long offset, long length, ObjectAttributes attrs,
      BiFunction<FileBlock, Short, Boolean> fn) throws Exception {
    RangeLockHolder rangeLockHolder = null;
    FileLockHolder fileLockHolder = null;
    long newFileSize = offset + length;
    long oldFileSize = _getFileSize();
    boolean res = false;

    LOGGER.info("{}: old filesize is {} and new file size is {}", System.identityHashCode(this),
        oldFileSize, newFileSize);
    rangeLockHolder = lockWriteRange(offset, length);

    if (oldFileSize != newFileSize) {
      fileLockHolder = lockFileWrite();
    }

    try {
      if (newFileSize > oldFileSize) {
        LOGGER.info("SHOULD BE PRINTED old filesize is {} and new file size is {}", oldFileSize,
            newFileSize);
        assert (newFileSize == attrs.getFilesize());
        LOGGER.debug("updating change timestamp to {}", attrs.getChange_time());
        _updateTimestampsFromRemote(attrs);
        // enlarge file size
        _setDataSize(newFileSize);
        res = _truncate(newFileSize, true);
        if (!res) {
          return res;
        }
        fileLockHolder.release();
      }
      _iterBlocks(offset, length, true, (bm, i) -> {
        FileBlock fb = null;
        Entry<Long, Short> blockEntry = BlockMapHelper.getBlockEntry(bm, i);
        long localBlockVersion = blockEntry.getKey();
        short gwid = blockEntry.getValue();
        if (localBlockVersion != 0) {
          fb = new FileBlock(this, i * blockSize, blockSize, localBlockVersion, gwid,
              ByteBuffer.wrap(new byte[blockSize]), false);
        } else {
          // if block version is zero than we will not read it as it
          // is considered as file hole
          fb = new FileBlock(this, i * blockSize, blockSize, localBlockVersion, gwid);
        }
        return fn.apply(fb, gwid);
      });
    } catch (Exception e) {
      LOGGER.error(String.format("Unable to set file size"), e);
      return false;
    } finally {
      // release locks
      if (fileLockHolder != null) {
        fileLockHolder.release();
      }
      rangeLockHolder.release();
    }
    LOGGER.info("new file size is {} and new date size is {}", getFileSize(), getDataSize());
    return true;
  }

  /**
   * read data from cloud for given block, used to compare remote and local changes in case of
   * conflict
   *
   * @param fb file block which we want to read from cloud
   *
   *        returns true in case of successful read operation, else false
   */
  public boolean _loadBlockData(FileBlock fb) {
    if (!blockExecutor.read(Collections.singletonList(fb), aeCipher, kvsFacade)) {
      LOGGER.error("Unable to read block for conflict detection");
      return false;
    }
    return true;
  }

  public boolean write(long offset, ByteBuffer data, Optional<List<Long>> newVersions) {
    Preconditions.checkArgument(isBlockAligned(offset));
    boolean res = true;
    int length = data.remaining();
    List<FileBlock> blocksToBeCollected = new ArrayList<FileBlock>();

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("written {} bytes at {} of {}: {}", length, offset, this,
          LoggingUtils.hash(data));
    }

    RangeLockHolder rangeLockHolder = lockWriteRange(offset, length);
    /* in case we need to fill any file hole */
    RangeLockHolder holeLockHolder = new RangeLockHolder();
    FileLockHolder fileLockHolder = lockFileWrite();

    try {
      long oldFileSize = _getFileSize();
      long oldDataSize = _getDataSize();
      setBlockSizeIfNeeded(length);
      long newFileSize = offset + length;
      if (newFileSize > oldFileSize) {
        _truncate(newFileSize, false);
      } else if (newFileSize > oldDataSize) {
        _setDataSize(newFileSize);
      }

      // We can release the file lock before the time-consuming write
      // operations. We can re-take it if needed later.
      fileLockHolder.release();

      List<FileBlock> blocks = _breakBufferIntoBlocks(offset, length, newFileSize, data, true);

      // If we are creating a hole by writing after an unaligned tail, we
      // need
      // to fill the hole in the tail block with zeros.
      boolean updateTail = false;
      if (offset > oldDataSize && !isBlockAligned(oldDataSize)) {
        LOGGER.debug("updating unaligned tail at {}", offset);
        int len = (int) (oldDataSize % blockSize);
        long off = oldDataSize - len;
        holeLockHolder = lockWriteRange(off, blockSize);
        long ver = _getBlockVersion(off, false).getKey();
        if (ver != 0) {
          FileBlock oldTailBlock = new FileBlock(this, off, blockSize, ver, config.getGatewayId(),
              ByteBuffer.allocate(blockSize), false);
          List<FileBlock> tmp = new ArrayList<>();
          tmp.add(oldTailBlock);
          if (!blockExecutor.read(tmp, aeCipher, kvsFacade)) {
            LOGGER.error("failed to read old tail with hole: {}", oldTailBlock);
            return false;
          }
          byte[] buf = oldTailBlock.getValue().array();
          Arrays.fill(buf, len, blockSize, (byte) 0);
          oldTailBlock.setValue(ByteBuffer.wrap(buf));
          oldTailBlock.setLoaded(true);
          oldTailBlock.setVersion(ver + 1);
          blocks.add(oldTailBlock);
          updateTail = true;
        }
      }

      res = blockExecutor.write(blocks, aeCipher, kvsFacade);
      if (!res) {
        for (FileBlock block : blocks) {
          if (block.getOperationResult()) {
            blocksToBeCollected.add(block);
          }
        }
        if (!blocksToBeCollected.isEmpty()) {
          volumeHandler.getGarbageCollector().collectBlocks(blocksToBeCollected);
        }
        LOGGER.error("write failed {}", ObjectIdHelper.getShortId(getOid()));
        return res;
      }

      if (updateTail) {
        LOGGER.info("updating version of unaligned tail");
        _updateBlockVersions(oldDataSize & ~(blockSize - 1), blockSize, config.getGatewayId(),
            Optional.empty(), false);
      }

      int newBlocks =
          _updateBlockVersions(offset, length, config.getGatewayId(), newVersions, false);
      fileLockHolder = lockFileWrite();
      AttributesHelper.addBlocks(update().getAttrs(), newBlocks);
      AttributesHelper.updateModifyTime(update().getAttrs());
      _saveToZk();
    } catch (Exception e) {
      // TODO roll file size, and block numbers back?
      LOGGER.error("write to FileHandler failed", e);
      e.printStackTrace();
      res = false;
    } finally {
      fileLockHolder.release();
      holeLockHolder.release();
      rangeLockHolder.release();
    }

    return res;
  }

  /**
   * Grab the locks needed to truncate file size to the specified new size.
   *
   * @param newSize The target file size to set.
   * @return The locks grabbed, or null in case of failure, or a pair of <null, null> in case the
   *         target file size is the same as the original file size.
   */
  private Entry<FileLockHolder, RangeLockHolder> lockForTruncate(final long newSize) {
    while (true) {
      WriteLock lock = rwlock.writeLock();
      lock.lock();
      long oldSize = _getFileSize();
      if (oldSize == newSize) {
        // 1. Same file size. Nothing needs to be done.
        /*
         * Note that the modify time should be updated even if the file size is not changed.
         */
        AttributesHelper.updateModifyTime(update().getAttrs());
        try {
          getWrapper().persist(zkClient, false);
        } catch (CuratorException e) {
          LOGGER.error("failed to save to ZooKeeper after updating timestamps", e);
          return null;
        } finally {
          lock.unlock();
        }
        return new AbstractMap.SimpleEntry<FileLockHolder, RangeLockHolder>(null, null);
      }

      RangeLockHolder rangeLockHolder = null;
      if (oldSize < newSize) {
        // 2. Enlarge file. Check if we need to populate the BlockMap.
        long blockMapCount = (newSize + (1L << blockMapShift) - 1L) >> blockMapShift;
        if (getFile().getBlock_map_count() < blockMapCount) {
          lock.unlock();
          // Hold the range lock that covers the BlockMaps to be
          // populated.
          long lower = roundDown(oldSize);
          long upper = blockMapCount << blockMapShift;
          rangeLockHolder = lockWriteRange(lower, upper - lower);
          lock.lock();
        } else {
          rangeLockHolder = new RangeLockHolder(); // empty holder
        }
      } else {
        // 3. Shrink file size. We need to release the write lock, take
        // the range
        // lock, and then re-take the write lock to make sure locks are
        // held in
        // the right order.
        lock.unlock();
        long lower = roundDown(newSize);
        long upper = roundUp(oldSize);
        rangeLockHolder = lockWriteRange(lower, upper - lower);
        lock.lock();
      }

      if (oldSize != _getFileSize()) {
        // Somebody change the file size after our last check of the
        // file size. Retry.
        //
        // TODO: What if the file gets deleted during the break?
        lock.unlock();
        rangeLockHolder.release();
        continue;
      }

      return new AbstractMap.SimpleEntry<>(new FileLockHolder(false), rangeLockHolder);
    }
  }

  /**
   * truncate helper. The caller should hold the appropriate locks.
   *
   * @param filesize -- File size to truncate to
   * @param hole -- Whether it is filled by file holes
   */
  private boolean _truncate(final long filesize, boolean hole) {
    long oldSize = _getFileSize();
    if (oldSize == 0) {
      setBlockSizeIfNeeded(filesize >> 4);
    }

    try {
      LOGGER.info("truncating to file size {}", filesize);
      _setFileSize(filesize);

      List<FileBlock> truncatedBlocks = null;
      if (oldSize > filesize) {
        // We need to GC discarded blocks in case of shrink.
        truncatedBlocks = _getValidFileBlocks(roundUp(filesize), oldSize, true);
        _setNBlocks(_getNBlocks() - truncatedBlocks.size());
      }
      if (filesize < _getDataSize() || !hole) {
        _setDataSize(filesize);
      }

      if (oldSize > filesize) {
        volumeHandler.getGarbageCollector().collectBlocks(truncatedBlocks);
      } else if (oldSize < filesize) {
        // Initialize blockMap and version number.
        long sz = roundUp(oldSize);
        _initializeBlockVersions(sz, roundUp(filesize) - sz, config.getGatewayId(),
            Optional.empty());
      }

    } catch (Exception e) {
      LOGGER.error(
          String.format("could not truncate %s from %d to %d", getOid(), oldSize, filesize), e);
      e.printStackTrace();
      _setFileSize(oldSize);
      return false;
    }

    return oldSize != filesize;
  }

  /**
   * Change the size of the file.
   *
   *
   * @param filesize
   * @return true if the file has been changed
   * @throws ExecutionException
   */
  public boolean truncate(final long filesize) {
    // TODO deal with block not set
    LOGGER.info("truncate file size to {}", filesize);
    Preconditions.checkArgument(filesize >= 0, "file size could not be negative");

    Entry<FileLockHolder, RangeLockHolder> locks = lockForTruncate(filesize);
    if (locks == null || locks.getKey() == null) {
      return false;
    }

    RangeLockHolder rangeLockHolder = locks.getValue();
    FileLockHolder fileLockHolder = locks.getKey();

    boolean res = _truncate(filesize, true);
    AttributesHelper.updateModifyTime(update().getAttrs());
    try {
      _saveToZk();
    } catch (Exception e) {
      LOGGER.error("failed to save to ZK after truncate", e);
      res = false;
    } finally {
      fileLockHolder.release();
      rangeLockHolder.release();
    }

    return res;
  }

  private long roundUp(long offset) {
    return ((offset + blockSize - 1) >> blockShift) << blockShift;
  }

  private long roundDown(long offset) {
    return (offset >> blockShift) << blockShift;
  }

  public ObjectAttributes setAttrs(ObjectAttributes newAttrs) {
    rwlock.writeLock().lock();
    try {
      ObjectAttributes attrs = update().getAttrs();
      ObjectAttributes oldAttrs = attrs.deepCopy();
      if (!AttributesHelper.setAttrs(update().getAttrs(), newAttrs)) {
        LOGGER.error("failed to set attributes of {}", getOid());
        return null;
      }
      return oldAttrs;
    } finally {
      rwlock.writeLock().unlock();
    }
  }

  public long getFileSize() {
    rwlock.readLock().lock();
    try {
      return _getFileSize();
    } finally {
      rwlock.readLock().unlock();
    }
  }

  private long _getFileSize() {
    return get().attrs.isSetFilesize() ? get().attrs.filesize : 0;
  }

  private void _setFileSize(long filesize) {
    update().getAttrs().setFilesize(filesize);
  }

  public long getDataSize() {
    rwlock.readLock().lock();
    try {
      return _getDataSize();
    } finally {
      rwlock.readLock().unlock();
    }
  }

  private long _getDataSize() {
    return get().getAttrs().getDatasize();
  }

  private void _setDataSize(long datasize) {
    update().getAttrs().setDatasize(datasize);
  }

  private long _getNBlocks() {
    return get().getAttrs().getNblocks();
  }

  private void _setNBlocks(long nblocks) {
    update().getAttrs().setNblocks(nblocks);
  }

  public String getTomestoneZpath() {
    return getZpath() + "/" + TOMESTONE_NAME;
  }

  public String getBlockMapZpath(long offset) {
    long bi = offset >> blockShift;
    long i = bi >> MAX_BLOCK_MAP_LEN_SHIFT;
    return String.format("%s/%s%08d", getZpath(), BLOCKMAP_PREFIX, i);
  }

  public byte[] getBlockKey(long offsetInFile, long version, short creator) {
    return blockKeyGen.getBlockKey(offsetInFile, version, creator);
  }

  public String getKeyMapZpath() {
    return getZpath() + "/" + KEYMAP_NAME;
  }

  public File getFile() {
    return get();
  }

  protected void setBlockShift(int blockShift) {
    if (blockShift > 0) {
      if (get().attrs != null) {
        get().attrs.setBlock_shift(blockShift);
      }
      this.blockShift = blockShift;
      this.blockSize = (1 << blockShift);
      this.blockMapShift = blockShift + MAX_BLOCK_MAP_LEN_SHIFT;
    }
  }

  protected void setBlockSizeIfNeeded(long length) {
    if (!isBlockSet()) {
      int shift = 64 - Long.numberOfLeadingZeros(length - 1);
      shift = AlgorithmUtils.clamp(shift, MIN_BLOCK_SHIFT, MAX_BLOCK_SHIFT);
      setBlockShift(shift);
    }
  }

  public int getBlockSize() {
    return blockSize;
  }

  public int getBlockShift() {
    return blockShift;
  }

  public boolean isBlockSet() {
    return blockShift > 0;
  }

  public ObjectID getOid() {
    // ObjectID is read only and need not to be protected.
    return get().getOid();
  }

  public String getName() {
    rwlock.readLock().lock();
    try {
      return get().getName();
    } finally {
      rwlock.readLock().unlock();
    }
  }

  public int getNlinks() {
    rwlock.readLock().lock();
    try {
      return get().attrs.getNlinks();
    } finally {
      rwlock.readLock().unlock();
    }
  }

  public int unlink() {
    rwlock.writeLock().lock();
    try {
      ObjectAttributes attrs = update().attrs;
      int n = attrs.getNlinks() - 1;
      attrs.setNlinks(n);
      return n;
    } finally {
      rwlock.writeLock().unlock();
    }
  }

  public int ref() {
    return refcount.incrementAndGet();
  }

  public int unref() {
    return refcount.decrementAndGet();
  }

  public int getRefCount() {
    return refcount.get();
  }

  @Override
  public List<String> getSubpaths() {
    ArrayList<String> subpaths = new ArrayList<>();
    subpaths.add(getKeyMapZpath());
    int count = getBlockMapCount();
    for (int i = 1; i <= count; ++i) {
      subpaths.add(String.format("%s/%s%08d", getZpath(), BLOCKMAP_PREFIX, i));
    }
    return subpaths;
  }

  public int getBlockMapCount() {
    return (int) ((getFile().attrs.nblocks + MAX_BLOCK_MAP_LENGTH - 1) >> MAX_BLOCK_MAP_LEN_SHIFT);
  }

  public boolean isLoaded() {
    return loaded;
  }

  public void setLoaded(boolean loaded) {
    this.loaded = loaded;
  }

  /**
   * Get file attributes.
   *
   * @return a deepcopy of the file's attributes.
   */
  public ObjectAttributes getAttrsCopy() {
    rwlock.readLock().lock();
    try {
      return get().getAttrs().deepCopy();
    } finally {
      rwlock.readLock().unlock();
    }
  }

  /**
   * Get file attributes.
   *
   * @return a reference of the file's attributes.
   */
  public ObjectAttributes getAttrsRef() {
    return get().getAttrs();
  }

  @Override
  public String toString() {
    rwlock.readLock().lock();
    try {
      return String.format("Regular file: %s, Object ID: %s", getName(),
          ObjectIdHelper.getShortId(getOid()));
    } finally {
      rwlock.readLock().unlock();
    }
  }



  /**
   * Create a new snapshot using the current state of the file.
   *
   * @param name
   * @param description
   * @return A SnapshotInfo of the newly created snapshot.
   * @throws Exception
   */
  private SnapshotInfo _takeSnapshot(final String name, final String description) throws Exception {
    // Create snapshot Znode
    int ssid = _getNextSnapshotId();
    Snapshot snapshot = new Snapshot();
    snapshot.setSaved_file(getFile());
    // TODO support files with multiple BlockMaps
    snapshot.setBlocks(_getBlockMap(0, false).get());
    long ts = System.currentTimeMillis();
    snapshot.setCreate_time(ts);
    snapshot.setUpdate_time(ts);
    snapshot.setDescription(description);
    snapshot.setId(ssid);
    String zpath = getSnapshotZpath(name);

    ObjectAttributes attrs = update().getAttrs();
    ObjectAttributes oldAttrs = AttributesHelper.duplicateAttributes(attrs);
    boolean hasSnapshot = AttributesHelper.hasSnapshot(attrs);
    AttributesHelper.setSnapshot(update().getAttrs(), true);

    /*
     * Put two ZK operations into one ZK transaction for better performance.
     */
    if (!hasSnapshot) {
      KurmaTransaction txn = zkClient.newTransaction();
      TWrapper<Snapshot> snapshotWrapper = new TWrapper<>(zpath, snapshot, false);
      if (wrapper.write(txn) && txn.create(getSnapshotZpath(null), null) && snapshotWrapper.create(txn)) {
        zkClient.submitTransaction(txn);
      } else {
        return null;
      }
    } else {
      SnapshotHelper.writeSnapshot(zkClient, zpath, snapshot);
    }
    LOGGER.info("Snapshot {} (ID-{}) written znode {}", name, ssid, zpath);

    // Update in-memory data structures about snapshots
    SnapshotInfo info = new SnapshotInfo();
    info.name = name;
    info.createTime = ts;
    info.updateTime = ts;
    info.description = description;
    info.id = ssid;
    info.attrs = oldAttrs;
    if (snapshots == null) {
      snapshots = new HashMap<>();
      snapshotsById = new HashMap<>();
    }
    snapshots.put(name, info);
    snapshotsById.put(ssid, info);
    // Update snapshotBlocks
    int nblocks = (int) ((_getFileSize() + (1L << blockShift) - 1) >> blockShift);
    if (snapshotBlocks == null) {
      snapshotBlocks = new BitSet(nblocks);
    }
    snapshotBlocks.set(0, nblocks);

    return info;
  }

  /**
   * Return the next available snapshot ID. Snapshot IDs 0 and 1 are reserved.
   */
  private int _getNextSnapshotId() {
    int ssid = maxSnapshotId + 1;
    for (int i = 1; i != 0; ++i) {
      if (ssid != 0 && ssid != 1
          && (snapshotsById == null || !snapshotsById.containsKey(Integer.valueOf(ssid)))) {
        maxSnapshotId = ssid;
        break;
      }
      ssid += 1;
    }
    return ssid;
  }

  /**
   * Take a snapshot of the file.
   *
   * @param name of the snapshot
   * @param description optional descriptions of the snapshot
   * @return The SnapshotInfo of the newly created snapshot.
   */
  public SnapshotInfo takeSnapshot(final String name, final String description) {
    Preconditions.checkNotNull(name);
    if (!loaded) {
      load();
    }
    RangeLockHolder rangeLockHolder = lockReadRange(0, getFileSize());
    FileLockHolder fileLockHolder = lockFileWrite();
    SnapshotInfo info = null;
    try {
      if (snapshots != null && snapshots.containsKey(name)) {
        LOGGER.error("snapshot {} already exists", name);
        return null;
      }
      info = _takeSnapshot(name, description);
    } catch (Exception e) {
      e.printStackTrace();
      LOGGER.error(String.format("Unknown error when taking snapshot %s", name), e);
      return null;
    } finally {
      fileLockHolder.release();
      rangeLockHolder.release();
    }
    return info;
  }

  private SnapshotInfo _restoreSnapshot(final String name) throws Exception {
    // Set file blocks, size, and attributes.
    // long oldSize = _getFileSize();
    Snapshot snapshot = SnapshotHelper.readSnapshot(zkClient, getSnapshotZpath(name));
    long savedSize = snapshot.getSaved_file().getAttrs().getFilesize();
    _truncate(savedSize, false);
    AttributesHelper.setAttrs(update().getAttrs(), snapshot.getSaved_file().getAttrs());

    // Set block maps.
    // TODO support multiple block maps
    TWrapper<BlockMap> blockMapWrapper = new TWrapper<>(getBlockMapZpath(0), snapshot.getBlocks());
    KurmaTransaction txn = zkClient.newTransaction();
    if (wrapper.write(txn) && blockMapWrapper.write(txn)) {
      zkClient.submitTransaction(txn);
    } else {
      return null;
    }

    // Rebuild snapshots and snapshotBlocks
    _loadSnapshots();
    blockMaps.refresh(Long.valueOf(0L));

    return snapshotsById.get(snapshot.getId());

  }

  private void _autoSnapshot(String name) throws Exception {
    Date now = new Date();
    String ssName =
        String.format("AutoSave-%1$tF-%1$tH-%1$tM-%1$tS-Before-Restore-%2$s", now, name);
    String ssDesc =
        String.format("Auto snapshot taken before restoring snapshot %1s at %2tc", name, now);
    if (!snapshots.containsKey(name)) {
      LOGGER.warn("snapshot %s does not exist for file %s", name, get().getName());
      return;
    }
    while (snapshots.containsKey(ssName)) {
      ssName += "X";
    }
    _takeSnapshot(ssName, ssDesc);
  }

  /**
   * Restore the file to a previous-taken snapshot.
   *
   * The current state will be saved as another snapshot identified by timestamp.
   *
   * @param name The snapshot name.
   * @return The SnapInfo of the restored snapshot; or 0 in case of failure.
   */
  public SnapshotInfo restoreSnapshot(final String name) {
    Preconditions.checkNotNull(name);
    RangeLockHolder rangeLockHolder = lockWriteRange(0, getFileSize());
    FileLockHolder fileLockHolder = lockFileWrite();
    if (!snapshots.containsKey(name)) {
      LOGGER.warn("snapshot %s does not exist for file %s", name, get().getName());
      return null;
    }
    SnapshotInfo restoredSnapshot = null;
    try {
      if (config.getAutoSnapshotBeforeRestore()) {
        _autoSnapshot(name);
      }
      restoredSnapshot = _restoreSnapshot(name);
    } catch (Exception e) {
      e.printStackTrace();
      LOGGER.error(String.format("Failed to restore snapshot %s", name), e);
      return null;
    } finally {
      fileLockHolder.release();
      rangeLockHolder.release();
    }
    return restoredSnapshot;
  }

  public long updateSnapshot(final String name, final String description) {
    return 0;
  }

  /**
   * Delete the specified snapshot
   *
   * @param name -- The snapshot name
   * @return The SnapshotInof of the deleted snapshot; or null on failure.
   */
  public SnapshotInfo deleteSnapshot(final String name) {
    RangeLockHolder rangeLockHolder = lockReadRange(0, getFileSize());
    FileLockHolder fileLockHolder = lockFileWrite();
    SnapshotInfo info = null;
    try {
      if (!snapshots.containsKey(name)) {
        LOGGER.warn("Snapshot %s does not exist", name);
        return null;
      }
      info = snapshots.get(name);
      _deleteSnapshot(name);
    } catch (CuratorException e) {
      e.printStackTrace();
      LOGGER.error(String.format("cannot delete snapshot %s", name), e);
      return null;
    } finally {
      fileLockHolder.release();
      rangeLockHolder.release();
    }
    return info;
  }

  /**
   * TODO: remove the snapshot directory when the last snapshot is deleted.
   *
   * @param name
   * @throws CuratorException
   */
  private void _deleteSnapshot(String name) throws CuratorException {
    try {
      // Load the snapshot data blocks and find out which of them need to
      // be deleted.
      Snapshot target = SnapshotHelper.readSnapshot(zkClient, getSnapshotZpath(name));
      int blkcnt = target.getBlocks().getVersionsSize();
      BitSet blocksToDelete = new BitSet(blkcnt);
      blocksToDelete.set(0, blkcnt);

      // Mark out the blocks used by the current file content
      // TODO Make it work for multiple block maps
      long limit = Long.min(blkcnt, ((_getFileSize() + (1 << blockShift) - 1) >> blockShift));
      Iterator<Long> ver_it = target.getBlocks().getVersionsIterator();
      Iterator<Short> gw_it = target.getBlocks().getLast_modifierIterator();
      for (long i = 0; i < limit; ++i) {
        Entry<Long, Short> ver_gw = _getBlockVersion(i << blockShift, false);
        Long ver = ver_it.next();
        Short gw = gw_it.next();
        if (ver.equals(ver_gw.getKey()) && gw.equals(ver_gw.getValue())) {
          blocksToDelete.clear((int) i);
        }
      }

      // Mark out the blocks used by other snapshots
      List<String> snapshotNames = SnapshotHelper.listSnapshots(zkClient, getSnapshotZpath(null));
      assert (snapshotNames != null); // It should at least contains the
                                      // one we are deleting.
      if (snapshotNames.size() == 1) {
        assert (snapshotNames.get(0).equals(name));
        AttributesHelper.setSnapshot(update().getAttrs(), false);
      } else {
        for (String sn : snapshotNames) {
          if (sn.equals(name)) {
            continue; // ignore the target snapshot itself
          }
          Snapshot snapshot = SnapshotHelper.readSnapshot(zkClient, getSnapshotZpath(sn));
          ver_it = target.getBlocks().getVersionsIterator();
          gw_it = target.getBlocks().getLast_modifierIterator();
          Iterator<Long> ver_it2 = snapshot.getBlocks().getVersionsIterator();
          Iterator<Short> gw_it2 = snapshot.getBlocks().getLast_modifierIterator();
          for (int i = 0; i < Math.min(blkcnt, snapshot.getBlocks().getVersionsSize()); ++i) {
            Long ver = ver_it.next();
            Short gw = gw_it.next();
            Long ver2 = ver_it2.next();
            Short gw2 = gw_it2.next();
            if (ver.equals(ver2) && gw.equals(gw2)) {
              blocksToDelete.clear(i);
            }
          }
        }
      }

      List<FileBlock> gcBlocks = new ArrayList<>();
      for (int i = blocksToDelete.nextSetBit(0); i >= 0; i = blocksToDelete.nextSetBit(i + 1)) {
        gcBlocks.add(new FileBlock(this, (i + 0L) << blockShift, blockSize,
            target.getBlocks().getVersions().get(i), target.getBlocks().getLast_modifier().get(i)));
      }
      volumeHandler.getGarbageCollector().collectBlocks(gcBlocks);

      if (snapshotNames.size() == 1) {
        zkClient.getCuratorClient().delete().deletingChildrenIfNeeded().forPath(getSnapshotZpath(null));
      } else {
        zkClient.getCuratorClient().delete().forPath(getSnapshotZpath(name));
      }
      snapshots.clear();
      snapshotsById.clear();
      snapshotBlocks.clear();
      _loadSnapshots();
    } catch (Exception e) {
      throw new CuratorException(String.format("cannot delete snapshot %s", name), e);
    }
  }

  public List<DirEntry> listSnapshots() {
    List<DirEntry> contents = new ArrayList<>();
    ObjectID oid = getOid();
    RangeLockHolder rangeLockHolder = lockReadRange(0, getFileSize());
    FileLockHolder fileLockHolder = lockFileRead();
    try {
      if (snapshots != null) {
        snapshots.forEach((name, info) -> {
          DirEntry dentry = new DirEntry();
          dentry.setName(name);
          dentry.setOid(oid);
          dentry.setTimestamp(info.createTime);
          contents.add(dentry);
        });
      }
    } finally {
      fileLockHolder.release();
      rangeLockHolder.release();
    }
    return contents;
  }

  public SnapshotInfo lookupSnapshotById(int id) {
    if (!loaded) {
      load();
    }
    SnapshotInfo res = null;
    FileLockHolder fileLockHolder = lockFileRead();
    try {
      if (snapshotsById != null) {
        res = snapshotsById.get(Integer.valueOf(id));
      }
    } finally {
      fileLockHolder.release();
    }
    return res;
  }

  public SnapshotInfo lookupSnapshotByName(String name) {
    if (!loaded) {
      load();
    }
    SnapshotInfo res = null;
    FileLockHolder fileLockHolder = lockFileRead();
    try {
      if (snapshots != null) {
        res = snapshots.get(name);
      }
    } finally {
      fileLockHolder.release();
    }
    return res;
  }

  public SnapshotInfo lookupSnapshot(String name, int id) {
    return id != 0 ? lookupSnapshotById(id) : lookupSnapshotByName(name);
  }
  @Override
  protected TWrapper<File> getWrapper() {
    return wrapper;
  }
}
