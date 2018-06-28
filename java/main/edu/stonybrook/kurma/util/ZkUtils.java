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
package edu.stonybrook.kurma.util;

import java.io.IOException;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.utils.EnsurePath;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.KurmaException.CuratorException;

@SuppressWarnings("deprecation")
public class ZkUtils {
  private static final Logger LOGGER = LoggerFactory.getLogger(ZkUtils.class);

  public static byte[] compress(byte[] data) {
    ByteArrayOutputStream baos = null;
    Deflater dfl = new Deflater(Deflater.BEST_SPEED, true);
    dfl.setInput(data);
    dfl.finish();
    baos = new ByteArrayOutputStream();
    byte[] tmp = new byte[4 * 1024];
    try {
      while (!dfl.finished()) {
        int size = dfl.deflate(tmp);
        baos.write(tmp, 0, size);
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      return data;
    } finally {
      try {
        if (baos != null)
          baos.close();
      } catch (Exception ex) {
      }
    }
    return baos.toByteArray();
  }

  public static byte[] decompress(byte[] data) throws IOException, DataFormatException {
    Inflater inflater = new Inflater(true);
    inflater.setInput(data);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
    byte[] buffer = new byte[1024];
    while (!inflater.finished()) {
      int count = inflater.inflate(buffer);
      outputStream.write(buffer, 0, count);
    }
    outputStream.close();
    byte[] output = outputStream.toByteArray();

    inflater.end();

    return output;
  }

  /**
   * Ensure the given zpath exists in Zookeeper.
   *
   * @param client Curator client
   * @param zpath target zpath
   * @param excludeLast whether to exclude the last component in the target zpath
   * @return the zpath that have been created to ensure the existence of the target zpath.
   */
  public static void ensurePath(CuratorFramework client, String zpath, boolean excludeLast)
      throws Exception {
    if (excludeLast) {
      zpath = ZKPaths.getPathAndNode(zpath).getPath();
    }
    String ns = client.getNamespace();
    String targetPath = ZKPaths.makePath(ns, zpath);

    EnsurePath ep = new EnsurePath(targetPath);
    ep.ensure(client.getZookeeperClient());
  }

  public static String getParentZpath(String zpath) {
    return ZKPaths.getPathAndNode(zpath).getPath();
  }

  /**
   * Delete the empty parent directory of the specified zpath.
   *
   * For example, with a zpath of "/a/b/c/d/e", and a stop of "/a/", the following directories will
   * be deleted if they are empty:
   *
   * 1. /a/b/c/d 2. /a/b/c 3. /a/b
   *
   * Note that zpath itself ("/a/b/c/d/e" in the example) does not have to exist.
   *
   * @param client
   * @param zpath Target zpath whose empty parent directories will be trimmed.
   * @param stop The stopping zpath of the trim.
   * @return
   * @throws Exception
   */
  public static String trimPath(CuratorFramework client, String zpath, String stop)
      throws Exception {
    Stat stat = new Stat();
    // get parent zpath
    zpath = ZKPaths.getPathAndNode(zpath).getPath();
    while (!zpath.equals(stop)) {
      List<String> children = client.getChildren().storingStatIn(stat).forPath(zpath);
      if (!children.isEmpty()) {
        break;
      }
      client.delete().withVersion(stat.getVersion()).forPath(zpath);
      // get parent zpath
      zpath = ZKPaths.getPathAndNode(zpath).getPath();
    }
    return zpath;
  }

  public static CuratorTransactionFinal buildAtomicRecursiveDelete(final CuratorFramework zkClient,
      final CuratorTransaction tx, final String path) throws Exception {
    final List<String> children = zkClient.getChildren().forPath(path);
    for (String child : children) {
      LOGGER.info("Recirsive delete - path {}", path + "/" + child);
      buildAtomicRecursiveDelete(zkClient, tx, path + "/" + child);
    }
    return tx.delete().forPath(path).and();
  }

  public static void addDeleteChildrenIfNeeded(CuratorFramework client, String zkPath,
      boolean retry) throws CuratorException {
    CuratorTransaction txn = client.inTransaction();
    try {
      txn = buildAtomicRecursiveDelete(client, txn, zkPath);
    } catch (Exception e) {
      if (e.getClass().equals(NoNodeException.class) && retry) {
        LOGGER.info("No Node Exception, commiting transaction and retrying {}", zkPath);
        return;
      } else {
        e.printStackTrace();
        throw new CuratorException("could not add delete to transaction");
      }
    }
  }

}
