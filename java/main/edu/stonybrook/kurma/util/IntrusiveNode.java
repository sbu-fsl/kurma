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

public class IntrusiveNode<T> implements IntrusiveListElement<IntrusiveNode<T>> {
  private IntrusiveNode<T> prev;
  private IntrusiveNode<T> next;
  private IntrusiveList<IntrusiveNode<T>> list;
  private T data;

  public IntrusiveNode() {
    this(null);
  }

  public IntrusiveNode(T data) {
    this.setData(data);
  }

  @Override
  public void setNext(IntrusiveNode<T> next) {
    this.next = next;
  }

  @Override
  public IntrusiveNode<T> getNext() {
    return next;
  }

  @Override
  public void setPrev(IntrusiveNode<T> prev) {
    this.prev = prev;
  }

  @Override
  public IntrusiveNode<T> getPrev() {
    return prev;
  }

  @Override
  public void setList(IntrusiveList<IntrusiveNode<T>> list) {
    this.list = list;
  }

  @Override
  public IntrusiveList<IntrusiveNode<T>> getList() {
    return list;
  }

  public T getData() {
    return data;
  }

  public void setData(T data) {
    this.data = data;
  }

  public boolean removeFromList() {
    if (list != null) {
      return list.removeElement(this);
    }
    return false;
  }
}
