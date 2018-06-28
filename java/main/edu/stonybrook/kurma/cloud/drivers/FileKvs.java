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
package edu.stonybrook.kurma.cloud.drivers;

import static edu.stonybrook.kurma.util.LoggingUtils.debugIf;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsFilterInterface;
import edu.stonybrook.kurma.util.PathUtils;

public class FileKvs extends Kvs {
  private static Logger LOGGER = LoggerFactory.getLogger(FileKvs.class);
  private File directory;
  private AtomicLong bytesUsed;

  public FileKvs(String id) {
    this(id, id, true, 1);
  }

  public FileKvs(String id, String folder, boolean enabled, int cost) {
    super(id, folder, enabled, cost);
    directory = new File(folder);
    if (!directory.exists()) {
      if (!directory.mkdir()) {
        String errmsg = String.format("cannot create FileKvs directory: %s", folder);
        LOGGER.error(errmsg);
        throw new RuntimeException(errmsg);
      }
      bytesUsed = new AtomicLong(0);
    } else {
      if (!directory.isDirectory()) {
        throw new RuntimeException(String.format("Not a directory: %s", folder));
      }
      File[] kvfiles = directory.listFiles();
      long bytes = 0L;
      if (kvfiles != null) {
        for (File f : kvfiles) {
          bytes += f.length() + f.getName().getBytes().length;
        }
      }
      bytesUsed = new AtomicLong(bytes);
    }
  }

  @Override
  public void put(String key, InputStream value, int size) throws IOException {
    /* Apply filter only if they are installed */
    if (null != filters) {
      for (KvsFilterInterface filter : filters) {
        filter.beforePut(key, value);
      }
    }
    String encodedFileName = PathUtils.encodePath(key.getBytes("utf-8"));
    File file = new File(directory.getAbsolutePath() + "/" + encodedFileName);
    FileOutputStream out = new FileOutputStream(file);
    try {
      long copied = ByteStreams.copy(value, out);
      if (copied != size) {
        throw new IOException(String.format("writting %d bytes but copied %d bytes", size, copied));
      }
      bytesUsed.addAndGet(key.getBytes().length + copied);
    } catch (FileNotFoundException e) {
      LOGGER.error(String.format("FileKvs put failed for key: 0x%8x", key.hashCode()), e);
      e.printStackTrace();
    } finally {
      value.close();
      out.close();
    }
    if (null != filters) {
      for (KvsFilterInterface filter : filters) {
        filter.afterPut(key, value);
      }
    }
  }

  @Override
  public InputStream get(String key) throws IOException {
    byte[] value = null;
    InputStream valueInputStream = null;
    /* Apply filter only if they are installed */
    if (null != filters) {
      for (KvsFilterInterface filter : filters) {
        filter.beforeGet(key);
      }
    }

    String encodedFileName = PathUtils.encodePath(key.getBytes("utf-8"));
    File file = new File(directory.getAbsolutePath(), encodedFileName);
    try {
      FileInputStream in = new FileInputStream(file);
      value = new byte[(int) file.length()];
      ByteStreams.read(in, value, 0, (int) file.length());
      in.close();
      debugIf(LOGGER, "get key from {}: {}", file.getAbsolutePath(),
          ByteBuffer.wrap(value).hashCode());
    } catch (FileNotFoundException e) {
      LOGGER.error(String.format("file not found by FileKvs::get() for key: %s (%s)", key,
          file.getAbsolutePath()), e.getMessage());
    }

    if (null != value)
      valueInputStream = new ByteArrayInputStream(value);

    if (null != filters) {
      for (KvsFilterInterface filter : filters) {
        filter.afterGet(key, valueInputStream);
      }
    }

    return valueInputStream;
  }

  @Override
  public List<String> list() throws IOException {
    List<String> keyList = new ArrayList<String>();
    File[] files = new File(directory.getAbsolutePath() + "/").listFiles();
    if (files == null) {
      LOGGER.error("directory {} does not exist", directory.getAbsolutePath());
      return keyList;
    }
    for (File file : files) {
      keyList.add(new String(PathUtils.decodePath(file.getName())));
    }

    return keyList;
  }

  @Override
  public void delete(String key) throws IOException {
    String encodedFileName = PathUtils.encodePath(key.getBytes("utf-8"));
    File file = new File(directory.getAbsolutePath() + "/" + encodedFileName);
    bytesUsed.addAndGet(-key.getBytes().length - file.length());
    if (!file.delete()) {
      LOGGER.error("cannot delete file: {}", file.getAbsolutePath());
    }
  }

  @Override
  public long bytes() throws IOException {
    return bytesUsed.get();
  }
}
