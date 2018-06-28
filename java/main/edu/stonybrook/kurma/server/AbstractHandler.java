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

import java.util.List;

import org.apache.thrift.TBase;
import org.apache.zookeeper.data.Stat;

import edu.stonybrook.kurma.helpers.TWrapper;

@SuppressWarnings("rawtypes")
public abstract class AbstractHandler<T extends TBase> {
  protected TWrapper<T> wrapper;

  protected TWrapper<T> getWrapper() {
    return wrapper;
  }

  protected void setWrapper(TWrapper<T> data) {
    this.wrapper = data;
  }

  public boolean isDirty() {
    return wrapper.isDirty();
  }

  protected T get() {
    return wrapper.get();
  }

  protected T update() {
    return wrapper.update();
  }

  /**
   * zpath is a const variable that is initialized in TWrapper's constructor. Therefore, this
   * function is thread safe.
   *
   * @return ZooKeeper path of the persistent object.
   */
  public String getZpath() {
    return wrapper.getZpath();
  }

  public Stat getZkStat() {
    return wrapper.getZkStat();
  }

  public abstract List<String> getSubpaths();
}
