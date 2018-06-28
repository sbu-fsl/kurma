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

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.SecretKey;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import edu.stonybrook.kurma.blockmanager.BlockManager;
import edu.stonybrook.kurma.cloud.FacadeManager;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.gc.GarbageCollector;
import edu.stonybrook.kurma.helpers.GatewayHelper;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.helpers.TWrapper;
import edu.stonybrook.kurma.journal.JournalManager;
import edu.stonybrook.kurma.journal.MetaJournal;
import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.meta.Int128;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectAttributes;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.VolumeInfo;
import edu.stonybrook.kurma.transaction.InTransactionZnodes;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.KurmaTransactionManager;
import edu.stonybrook.kurma.transaction.ZkClient;
import edu.stonybrook.kurma.util.KurmaKeyGenerator;

// TODO add volume information such as used space, quota etc.
public class VolumeHandler extends AbstractHandler<VolumeInfo> {
  public static String VOLUME_ID_CURSOR_ZPATH = "ID_CURSOR";
  public static String VOLUME_LOCK_ZPATH = "LOCK";
  public static String VOLUME_ROOT_ZPATH = "ROOT";
  public static String VOLUME_ROOT_NAME = "__ROOT__";

  private static final Logger LOGGER = LoggerFactory.getLogger(VolumeHandler.class);
  private BlockExecutor blockExecutor;
  private TWrapper<Int128> ids;

  private InTransactionZnodes zkCache = new InTransactionZnodes();
  private ZkClient zkClient;

  private final IGatewayConfig config;

  private GarbageCollector garbageCollector;

  private KurmaKeyGenerator keyGenerator;

  // next allocated unused id
  private Int128 nextId;
  private int nAllocatedIds = 0;

  // root directory of this volume
  private DirectoryHandler root;

  // inter-gateway message seqid
  // TODO: save it in ZK
  private long seqid = 0;
  // journal helper to manage and access journals
  private JournalManager journalManager;
  // block manager to manage the cloud objects
  private BlockManager blockManager;

  private KurmaTransactionManager transactionManager;
  /**
   * Map of all open files in all active sessions.
   */
  private LoadingCache<ObjectID, Optional<FileHandler>> files;

  // TODO: save it in ZK
  private AtomicLong objectCount;

  private FacadeManager facadeManager;

  // TODO remove negative cache so that changes by remote gateways can be seen
  private LoadingCache<ObjectID, Optional<DirectoryHandler>> directories;

  /**
   * Negative of removed or not existing objects.
   *
   * The key is a pair of parent directory id and object name. The object name may be absent; in the
   * case, the cached object is the object itself (identified by ObjectID) instead of a child with a
   * given name within the object. The boolean tell if the object is recently removed.
   */
  private Cache<java.util.Map.Entry<ObjectID, String>, Boolean> negativeObjects;

  public VolumeHandler(VolumeInfo vi, String zpath, CuratorFramework client, GarbageCollector gc,
      IGatewayConfig config, BlockExecutor blockExecutor) throws NoSuchAlgorithmException, IOException {
    setWrapper(new TWrapper<>(zpath, vi));
    this.config = config;
    this.blockExecutor = blockExecutor;
    ids = new TWrapper<>(getIdCursorZpath(), new Int128(), false, true);
    garbageCollector = gc;
    garbageCollector.setBlockExecutor(blockExecutor);
    keyGenerator = new KurmaKeyGenerator(config);

    String journalDir = new File(config.getJournalDirectory(), vi.getId()).getAbsolutePath();
    journalManager = new JournalManager(journalDir, config.getJournalCleanFrequency());
    transactionManager = new KurmaTransactionManager(client,
        journalManager.getMetaJournal(), config.getTransactionCommitInterval(),
        config.getTransactionCommitCount());
    zkClient = new ZkClient(client, transactionManager, zkCache);

    RemovalListener<ObjectID, Optional<DirectoryHandler>> dirEvictListener =
        new RemovalListener<ObjectID, Optional<DirectoryHandler>>() {
          @Override
          public void onRemoval(
              RemovalNotification<ObjectID, Optional<DirectoryHandler>> notification) {
            Optional<DirectoryHandler> value = notification.getValue();
            if (value.isPresent()) {
              DirectoryHandler dh = value.get();
              if (notification.getCause() == RemovalCause.SIZE
                  || notification.getCause() == RemovalCause.COLLECTED
                  || notification.getCause() == RemovalCause.EXPIRED) {
                if (dh != null && dh.isDirty()) {
                  try {
                    LOGGER.info("saving dirty directory data to Zookeeper");
                    if (!dh.save()) {
                      LOGGER.error("could not write dirty directory data to Zookeeper");
                    }
                  } catch (Exception e) {
                    LOGGER.error("could not save dirty directory", e);
                  }
                }
              }
            }
          }
        };

    CacheLoader<ObjectID, Optional<DirectoryHandler>> dirLoader =
        new CacheLoader<ObjectID, Optional<DirectoryHandler>>() {
          @Override
          public Optional<DirectoryHandler> load(ObjectID oid) {
            Preconditions.checkArgument(ObjectIdHelper.isDirectory(oid));
            DirectoryHandler dh = new DirectoryHandler(oid, VolumeHandler.this);
            if (dh.load()) {
              LOGGER.info("directory {} loaded from ZK by CacheLoader", zpath);
              return Optional.of(dh);
            }
            return Optional.empty(); // negative cache
          }
        };

    directories = CacheBuilder.newBuilder().maximumSize(config.getDirectoryCacheSize())
        .removalListener(dirEvictListener).build(dirLoader);

    RemovalListener<ObjectID, Optional<FileHandler>> fileEvictListener =
        new RemovalListener<ObjectID, Optional<FileHandler>>() {
          @Override
          public void onRemoval(RemovalNotification<ObjectID, Optional<FileHandler>> notification) {
            Optional<FileHandler> value = notification.getValue();
            if (value.isPresent()) {
              FileHandler fh = value.get();
              if (notification.getCause() == RemovalCause.SIZE
                  || notification.getCause() == RemovalCause.COLLECTED
                  || notification.getCause() == RemovalCause.EXPIRED) {
                if (fh != null && fh.isDirty()) {
                  try {
                    LOGGER.info("saving dirty file to Zookeeper");
                    if (!fh.save()) {
                      LOGGER.error("could not write dirty file to Zookeeper");
                    }
                  } catch (Exception e) {
                    LOGGER.error("could not save dirty file", e);
                  }
                }
              }
            }
          }
        };

    CacheLoader<ObjectID, Optional<FileHandler>> fileLoader =
        new CacheLoader<ObjectID, Optional<FileHandler>>() {
          @Override
          public Optional<FileHandler> load(ObjectID oid) {
            Preconditions.checkArgument(ObjectIdHelper.isFile(oid));
            FileHandler fh = new FileHandler(oid, VolumeHandler.this);
            if (fh.load()) {
              LOGGER.info("file {} loaded from ZK by CacheLoader", zpath);
              return Optional.of(fh);
            }
            return Optional.empty(); // negative cache
          }
        };

    files = CacheBuilder.newBuilder().maximumSize(config.getFileCacheSize())
        .removalListener(fileEvictListener).build(fileLoader);

    negativeObjects = CacheBuilder.newBuilder().maximumSize(config.getNegativeCacheSize()).build();

    objectCount = new AtomicLong(0);

    facadeManager = new FacadeManager(config.getKvsManager(), config);

    // We need to redo old journal records before we accept new transactions.
    if (getMetaJournal().redoOldRecords(this) < 0) {
      LOGGER.error("failed to redo old journal records");
      System.exit(1);
    }

  }

  public FacadeManager getFacadeManager() {
    return facadeManager;
  }

  /**
   * Create the ZK metadata of a new volume.
   *
   * @throws Exception
   */
  public boolean create() throws Exception {
    LOGGER.info("creating new volume {} at Gateway-{}", get().id, config.getGatewayName());
    // create volume znode
    update().setCreate_time(System.currentTimeMillis());
    update().setCreator(config.getGatewayId());
    wrapper.create(zkClient, true);

    // create ID
    if (initializeIds() == null) {
      return false;
    }

    // create root dir of this volume
    ObjectID oid = ObjectIdHelper.getRootOid(config.getGatewayId());
    root = new DirectoryHandler(oid, this);

    // root's parent is itself
    KurmaTransaction txn = zkClient.newTransaction();
    boolean res = root.create(oid, VOLUME_ROOT_NAME, new ObjectAttributes(), txn);
    zkClient.submitTransaction(txn);
    zkClient.flush();
    return res;
  }

  public boolean delete() throws Exception {
    return wrapper.delete(zkClient, true);
  }

  public boolean load() throws Exception {
    LOGGER.info("loading volume {} from Zookepper at Gateway-{}",
        get().id, config.getGatewayName());
    // load volume znode
    if (!wrapper.read(zkClient)) {
      LOGGER.error("could not load VolumeHandler {} at Gateway-{}", wrapper.getZpath(),
          config.getGatewayName());
      return false;
    }

    // TODO check if we are loading a volume that newly created by a remote
    // gateway
    // load ID
    nextId = allocateIds(config.getIdAllocationUnit());
    if (nextId == null) {
      return false;
    }

    objectCount = new AtomicLong(wrapper.get().getObject_count());

    // / load root dir of this volume
    root = new DirectoryHandler(ObjectIdHelper.getRootOid(config.getGatewayId()), this);
    return root.load();
  }

  public void addDirectoryHandler(DirectoryHandler dh) {
    directories.put(dh.get().getOid(), Optional.of(dh));
  }

  public void addFileHandler(FileHandler fh) {
    files.put(fh.getOid(), Optional.of(fh));
  }

  public DirectoryHandler getRootDirectory() {
    return root;
  }

  public DirectoryHandler getDirectoryHandler(ObjectID oid) {
    Preconditions.checkArgument(ObjectIdHelper.isDirectory(oid), "not a directory oid");
    if (Int128Helper.isRootId(oid.getId())) {
      // All roots created by different gateways are the same.
      return root;
    }

    Optional<DirectoryHandler> value = null;

    try {
      value = directories.get(oid);
    } catch (ExecutionException e) {
      LOGGER.error("directory cache lookup failed", e);
      e.printStackTrace();
      return null;
    }

    return value.isPresent() ? value.get() : null;
  }

  /**
   * Get the DirectoryHandler if only it is cached.
   *
   * @param oid
   * @return
   */
  public DirectoryHandler getDirectoryHandlerIfPresent(ObjectID oid) {
    Optional<DirectoryHandler> dh = directories.getIfPresent(oid);
    return (dh != null && dh.isPresent()) ? dh.get() : null;
  }

  public void removeDirectoryHandler(DirectoryHandler dh) {
    LOGGER.debug("remove directory {} from VolumeHandler", ObjectIdHelper.getShortId(dh.getOid()));
    directories.invalidate(dh.getOid());
    garbageCollector.collectDirectory(dh);
  }

  public synchronized Int128 getNextId() {
    if (nAllocatedIds <= 0) {
      nAllocatedIds = config.getIdAllocationUnit();
      nextId = allocateIds(nAllocatedIds);
    }
    Int128 res = nextId;
    nextId = Int128Helper.increment(nextId);
    --nAllocatedIds;
    return res;
  }

  public ObjectID newDirectoryOid() {
    return ObjectIdHelper.newDirectoryOid(getNextId(), config.getGatewayId());
  }

  public ObjectID newFileOid() {
    return ObjectIdHelper.newFileOid(getNextId(), config.getGatewayId());
  }

  private Int128 initializeIds() {
    // create ID
    nextId = Int128Helper.getFirstId();
    nAllocatedIds = config.getIdAllocationUnit();
    ids.set(Int128Helper.add(nextId, nAllocatedIds));
    try {
      ids.create(zkClient, true);
    } catch (Exception e) {
      LOGGER.error("could not initailize ID", e);
      e.printStackTrace();
      return null;
    }

    return nextId;
  }

  private Int128 allocateIds(int count) {
    LOGGER.debug("allocation Ids");
    Int128 firstId = null;
    try {
      ids.read(zkClient);
      firstId = ids.get();
      ids.set(Int128Helper.add(ids.get(), count));
      ids.persist(zkClient, true);
    } catch (Exception e) {
      LOGGER.error("could not allocate IDs", e);
      e.printStackTrace();
      return null;
    }
    return firstId;
  }

  public String getCreatorZPath(short gwid) {
    return String.format("%s/%s", getZpath(), GatewayHelper.nameOf(gwid));
  }

  public String getObjectZpath(ObjectID oid) {
    if (ObjectIdHelper.isRootDirectory(oid)) {
      return getRootZpath();
    }
    return String.format("%s/%s/%016x/%012x/%04x", getZpath(),
        GatewayHelper.nameOf(oid.getCreator()), oid.id.id2, (oid.id.id1 >> 16),
        (oid.id.id1 & 0xFFFF));
  }

  /**
   * Get fully qualified znode path with the ZK namespace. More often, you should use
   * FileHandle.getSnapshotZpath() which does not contain the ZK namespace.
   *
   * @param oid -- ObjectID of the file this snapshot belongs to.
   * @param snapshot -- Name of the snapshot.
   * @return Znode path of the snapshot.
   */
  public String getSnapshotZpath(ObjectID oid, String snapshot) {
    String fileZpath = getObjectZpath(oid);
    if (snapshot == null) {
      return String.format("%s/SNAPSHOTS", fileZpath);
    }
    return String.format("%s/SNAPSHOTS/%s", fileZpath, snapshot);
  }

  public String getIdCursorZpath() {
    return ZKPaths.makePath(getZpath(), VOLUME_ID_CURSOR_ZPATH);
  }

  public String getRootZpath() {
    return ZKPaths.makePath(getZpath(), VOLUME_ROOT_ZPATH);
  }

  public VolumeInfo getVolumeInfo() {
    return get();
  }

  public String getVolumeId() {
    return get().getId();
  }

  public GarbageCollector getGarbageCollector() {
    return garbageCollector;
  }

  public ZkClient getZkClient() {
    return zkClient;
  }

  public void setClient(ZkClient client) {
    this.zkClient = client;
  }

  public Entry<SecretKey, KeyMap> generateKey() {
    return keyGenerator.generateKey();
  }

  public BlockExecutor getBlockExecutor() {
    return blockExecutor;
  }

  public FileHandler getLoadedFile(ObjectID oid) {
    FileHandler fh = null;
    try {
      Optional<FileHandler> ofh = files.get(oid);
      if (ofh.isPresent()) {
        fh = ofh.get();
        fh.ref();
      }
    } catch (ExecutionException e) {
      LOGGER.error("failed to get file", e);
    }
    // Ignore if the file is to be deleted.
    if (fh != null && fh.getAttrsCopy().getNlinks() <= 0) {
      putFile(fh);
      fh = null;
    }
    return fh;
  }

  /**
   * Put the file handle back.
   *
   * @param fh
   * @return whether the file is still being used.
   */
  public synchronized boolean putFile(FileHandler fh) {
    if (fh.unref() <= 0) {
      // files.remove(fh.getOid(), fh);
      return false;
    }
    return true;
  }

  @Override
  public List<String> getSubpaths() {
    ArrayList<String> arr = new ArrayList<>(2);
    arr.add(getIdCursorZpath());
    arr.add(getRootZpath());
    return arr;
  }

  public IGatewayConfig getConfig() {
    return config;
  }

  public GatewayMessage newGatewayMessage() {
    GatewayMessage msg = new GatewayMessage();
    msg.setSeq_number(seqid++);
    msg.setVolumeid(getVolumeId());
    msg.setTimestamp(System.currentTimeMillis());
    msg.setGwid(config.getGatewayId());
    return msg;
  }

  public void cacheRemovedObject(ObjectID oid) {
    negativeObjects.put(new AbstractMap.SimpleEntry<ObjectID, String>(oid, ""),
        Boolean.valueOf(true));
  }

  public void cacheLookupNotFound(ObjectID dirOid, String name) {
    negativeObjects.put(new AbstractMap.SimpleEntry<ObjectID, String>(dirOid, name),
        Boolean.valueOf(false));
  }

  public void invalidateNegativeObject(ObjectID dirOid, String name) {
    negativeObjects.invalidate(new AbstractMap.SimpleEntry<ObjectID, String>(dirOid, name));
  }

  public boolean isNegative(ObjectID oid, String name) {
    return negativeObjects
        .getIfPresent(new AbstractMap.SimpleEntry<ObjectID, String>(oid, name)) != null;
  }

  public long incrementObjectCount() {
    return objectCount.incrementAndGet();
  }

  public long decrementObjectCount() {
    return objectCount.decrementAndGet();
  }

  public long getObjectCount() {
    return objectCount.get();
  }

  public void setJournalManager(JournalManager jm) {
    this.journalManager = jm;
  }

  public JournalManager getJournalManager() {
    return journalManager;
  }

  public MetaJournal getMetaJournal() {
    return journalManager.getMetaJournal();
  }

  public BlockManager getBlockManager() {
	return blockManager;
  }

  public void setBlockManager(BlockManager blockManager) {
	this.blockManager = blockManager;
  }

  public KurmaTransactionManager getTransactionManager() {
    return transactionManager;
  }

  public void setTransactionManager(KurmaTransactionManager transactionManager) {
    this.transactionManager = transactionManager;
  }

}
