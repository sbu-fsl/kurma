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

import java.util.Collection;
import java.util.Iterator;

import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.framework.api.transaction.OperationType;
import org.apache.thrift.TBase;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import edu.stonybrook.kurma.KurmaException;
import edu.stonybrook.kurma.KurmaException.CuratorException;
import edu.stonybrook.kurma.KurmaException.NoZNodeException;
import edu.stonybrook.kurma.transaction.KurmaTransaction;
import edu.stonybrook.kurma.transaction.ZkClient;
import edu.stonybrook.kurma.util.ThriftUtils;

/**
 * A wrapper of Thrift metadata we save in Zookeeper.
 *
 * TODO add state of the TWrapper
 *
 * - UNINITIALIZED (no data yet, either not filled in, or not loaded from ZK) - NEW (new data in
 * ram, not in ZK yet) - CLEAN (data in ram synced with ZK) - DIRTY (data changed in ram, not
 * written to the existing Znode yet) - DISCARDED (the znode is discarded by Kurma, is to be
 * deleted, but not yet) - DELETED (there is still data in ram, but the znode has been deleted)
 *
 * @author mchen
 *
 * @param <T>
 */
@SuppressWarnings("rawtypes")
public class TWrapper<T extends TBase> {
  private static Logger LOGGER = LoggerFactory.getLogger(TWrapper.class);
  private final String zpath;
  private T meta;
  private Stat zkStat;
  private boolean dirty = false;
  private boolean compress;

  // whether the zpath exists
  private boolean exist = true;

  public TWrapper(String zpath, T t) {
    this(zpath, t, true, true);
  }

  public TWrapper(String zpath, T t, boolean isNew) {
    this(zpath, t, true, !isNew);
  }

  public TWrapper(String zpath, T t, boolean compress, boolean exist) {
    Preconditions.checkNotNull(t);
    this.zpath = zpath;
    this.meta = t;
    this.compress = compress;
    this.exist = exist;
    zkStat = new Stat();
  }

  public boolean read(ZkClient client) throws CuratorException {
    Preconditions.checkState(!dirty, "read will overwrite dirty metadata");
    try {
      byte[] data = client.read(zpath, zkStat);
      if (data == null || data.length == 0) {
        return false;
      }
      ThriftUtils.decode(data, meta, compress);
    } catch (Exception e) {
      LOGGER.trace(String.format("failed to read %s", zpath), e);
      if (e instanceof KeeperException.NoNodeException) {
        exist = false;
        throw new NoZNodeException(zpath, e);
      } else {
        throw new CuratorException(String.format("could not read Znode %s", zpath), e);
      }
    }
    dirty = false;
    return true;
  }

  public boolean create(ZkClient client, boolean sync) throws CuratorException {
    byte[] data = ThriftUtils.encode(meta, compress);
    try {
      if (sync) {
        client.flush();
      }
      client.createRecursive(zpath, data);
    } catch (Exception e) {
      throw new CuratorException(String.format("could not create Znode %s", zpath), e);
    }
    dirty = false;
    exist = true;
    return true;
  }

  public boolean delete(ZkClient client, boolean sync) throws CuratorException {
    try {
      if (sync) {
        client.flush();
      }
      client.deleteRecursive(zpath);
    } catch (Exception e) {
      throw new CuratorException(String.format("could not delete Znode %s", zpath), e);
    }
    dirty = false;
    exist = false;
    return true;
  }

  public boolean write(ZkClient client) throws CuratorException {
    return write(client, false);
  }

  public boolean persist(ZkClient client, boolean sync) throws CuratorException {
    if (sync) {
      client.flush();
    }
    KurmaTransaction txn = client.newTransaction();
    return persist(txn) && client.submitTransaction(txn);
  }

  public boolean persist(KurmaTransaction txn) throws CuratorException {
    return exist ? write(txn) : create(txn);
  }

  public boolean write(ZkClient client, boolean ignoreVersion) throws CuratorException {
    byte[] data = ThriftUtils.encode(meta, compress);
    int version = ignoreVersion ? -1 : zkStat.getVersion();
    Stat newStat = null;
    try {
      newStat = client.getCuratorClient().setData().withVersion(version).forPath(zpath, data);
    } catch (Exception e) {
      throw new CuratorException(String.format("could not write to Znode %s", zpath), e.getCause());
    }
    assert (newStat != null);
    zkStat = newStat;
    dirty = false;
    exist = true;
    return true;
  }

  public boolean create(KurmaTransaction txn) {
    boolean res = txn.create(getZpath(), getData());
    if (res) {
      dirty = false;
      exist = true;
    }
    return res;
  }

  public boolean delete(KurmaTransaction txn) {
    boolean res = txn.delete(getZpath());
    if (res) {
      dirty = false;
      exist = false;
    }
    return res;
  }

  public boolean write(KurmaTransaction txn) {
    boolean res = txn.update(getZpath(), getData());
    if (res) {
      dirty = false;
      exist = true;
    }
    return res;
  }

  public interface TransactionResultProcessor {
    public abstract void process(Object ctx, Iterator<CuratorTransactionResult> it)
        throws CuratorException;

    public static void commit(CuratorTransactionFinal txn,
        Collection<TransactionResultProcessor> processors) throws CuratorException {
      Iterator<CuratorTransactionResult> results = null;
      try {
        results = txn.commit().iterator();
      } catch (Exception e) {
        throw new CuratorException("curator transaction failed", e);
      }
      for (TransactionResultProcessor p : processors) {
        p.process(null, results);
      }
    }
  }

  /**
   * Usage example:
   *
   * CuratorTransaction txn = client.inTransaction(); ArrayList<TransactionResultProcessor>
   * processors = new ArrayList<>(); txn = wrapper.create(txn, processors);
   * TransactionResultProcessor.commit(keyMap.create(txn, processors), processors);
   *
   * @param txn
   * @param processors
   * @return
   * @throws CuratorException
   */
  public CuratorTransactionFinal create(CuratorTransaction txn,
      Collection<TransactionResultProcessor> processors) throws CuratorException {
    byte[] data = ThriftUtils.encode(meta, compress);

    processors.add(new TransactionResultProcessor() {
      @Override
      public void process(Object ctx, Iterator<CuratorTransactionResult> it)
          throws CuratorException {
        Preconditions.checkArgument(it.hasNext());
        Preconditions.checkArgument(it.next().getType() == OperationType.CREATE);
        Preconditions.checkArgument(it.hasNext());
        zkStat = it.next().getResultStat();
        if (zkStat == null) {
          throw new CuratorException(String.format("zpath '%s' does not exist", zpath));
        }
        dirty = false;
        exist = true;
      }
    });

    CuratorTransactionFinal result = null;
    try {
      result = txn.create().forPath(zpath).and().setData().forPath(zpath, data).and();
    } catch (Exception e) {
      throw new KurmaException.CuratorException("could not create znodes", e);
    }
    return result;
  }

  public String getZpath() {
    return zpath;
  }

  public Stat getZkStat() {
    return zkStat;
  }

  public void setZkStat(Stat zkStat) {
    this.zkStat = zkStat;
  }

  public boolean isDirty() {
    return dirty;
  }

  public void setDirty(boolean dirty) {
    this.dirty = dirty;
  }

  public T get() {
    return meta;
  }

  public void set(T t) {
    dirty = true;
    meta = t;
  }

  public T update() {
    dirty = true;
    return meta;
  }

  public boolean isExist() {
    return exist;
  }

  public void setExist(boolean e) {
    exist = e;
  }

  @Override
  protected void finalize() throws Throwable {
    if (dirty) {
      throw new RuntimeException("dirty data are being discarded.");
    }
    super.finalize();
  }

  public boolean isCompress() {
    return compress;
  }

  public byte[] getData() {
    return  ThriftUtils.encode(meta, compress);
  }
}
