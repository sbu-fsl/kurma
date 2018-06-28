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

/**
 * 
 * Adapt from http://stackoverflow.com/questions/3726681/intrusive-list-implementation-for-java
 * 
 * @author mchen
 *
 * @param <E> E should be DefaultConstructible, i.e, has a Constructor without any argument.
 */
public interface IntrusiveListElement<E extends IntrusiveListElement<E>> {
  public void setNext(E next);

  public E getNext();

  public void setPrev(E prev);

  public E getPrev();

  public void setList(IntrusiveList<E> list);

  public IntrusiveList<E> getList();
}
