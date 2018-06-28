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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public final class IntrusiveList<E extends IntrusiveListElement<E>> implements List<E> {
  private E sentinel;
  private int size = 0;
  private Class<E> eClass = null;

  @SuppressWarnings("unchecked")
  public IntrusiveList(E sentinel) {
    eClass = (Class<E>) sentinel.getClass();
    this.sentinel = sentinel;
    sentinel.setNext(sentinel);
    sentinel.setPrev(sentinel);
    sentinel.setList(this);
  }

  public E getFirst() {
    return sentinel.getNext();
  }

  public E getLast() {
    return sentinel.getPrev();
  }

  private void reset(E e) {
    if (e != null) {
      e.setList(null);
      e.setPrev(e);
      e.setNext(e);
    }
  }

  private void replace(E a, E b) {
    assert (a != null);
    E prev = a.getPrev();
    E next = a.getNext();
    assert (prev != null && next != null);
    if (b != null) {
      prev.setNext(b);
      next.setPrev(b);
      b.setList(a.getList());
      b.setPrev(prev);
      b.setNext(next);
    } else {
      prev.setNext(next);
      next.setPrev(prev);
      --size;
    }
    reset(a);
  }

  private void insert(E prev, E next, E e) {
    assert (prev != null && next != null && e != null);
    assert (prev.getList() == next.getList());
    prev.setNext(e);
    next.setPrev(e);
    e.setPrev(prev);
    e.setNext(next);
    e.setList(prev.getList());
    ++size;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  public boolean containsElement(E e) {
    if (e == null || e == sentinel) {
      return false;
    }
    return e.getList() == this;
  }

  public boolean removeElement(E e) {
    if (containsElement(e)) {
      replace(e, null);
      return true;
    } else {
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  public boolean isElement(Object o) {
    if (o == null) {
      return false;
    }
    E e = null;
    try {
      e = (E) o;
    } catch (ClassCastException ex) {
      return false;
    }
    return e.getList() == this;
  }

  /**
   * Use containsElement() for membership test.
   */
  @Override
  public boolean contains(Object o) {
    return isElement(o) || findForward(o).index >= 0;
  }

  @Override
  public Iterator<E> iterator() {
    return new Iterator<E>() {
      private E cursor = sentinel;

      @Override
      public boolean hasNext() {
        return cursor.getNext() != sentinel;
      }

      @Override
      public E next() {
        E next = cursor.getNext();
        cursor = next;
        return next;
      }

      @Override
      public void remove() {
        E newCursor = cursor.getPrev();
        replace(cursor, null);
        cursor = newCursor;
      }

    };
  }

  @Override
  public ListIterator<E> listIterator(int index) {
    if (index < 0 || index > size) {
      throw new IndexOutOfBoundsException();
    }
    ListIterator<E> it = listIterator();
    for (int i = 0; i < index; ++i) {
      it.next();
    }
    return it;
  }

  @Override
  public Object[] toArray() {
    Object[] arr = new Object[size];
    int i = 0;
    for (E e = sentinel; e.getNext() != sentinel; e = e.getNext()) {
      arr[i++] = e.getNext();
    }
    return arr;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T[] toArray(T[] a) {
    if (a.length < size) {
      a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
    }
    int i = 0;
    Object[] result = a;
    for (E e = sentinel; e.getNext() != sentinel; e = e.getNext()) {
      result[i++] = e.getNext();
    }

    if (a.length > size) {
      a[size] = null;
    }

    return a;
  }

  public void addFirst(E e) {
    insert(sentinel, sentinel.getNext(), e);
  }

  public void addLast(E e) {
    insert(sentinel.getPrev(), sentinel, e);
  }

  public void insertAt(E pos, E e) {
    insert(pos, pos.getNext(), e);
  }

  @Override
  public boolean add(E e) {
    insert(sentinel.getPrev(), sentinel, e);
    return true;
  }

  @Override
  public boolean remove(Object o) {
    Position pos = findForward(o);
    if (pos.index < 0) {
      return false;
    }
    replace(pos.entry, null);
    return true;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    for (Object o : c) {
      if (contains(o)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    return addAll(size - 1, c);
  }

  @Override
  public boolean addAll(int index, Collection<? extends E> c) {
    E prev = null;
    if (index == 0) {
      prev = sentinel;
    } else {
      prev = get(index - 1);
    }
    E next = prev.getNext();
    boolean changed = false;
    for (E e : c) {
      if (e == null) {
        continue;
      }
      insert(prev, next, e);
      prev = e;
      changed = true;
    }
    return changed;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    boolean changed = false;
    for (Object o : c) {
      if (contains(o)) {
        remove(o);
        changed = true;
      }
    }
    return changed;
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    boolean changed = false;
    for (E e = sentinel.getNext(); e != sentinel; e = e.getNext()) {
      if (!c.contains(e)) {
        removeElement(e);
        changed = true;
      }
    }
    return changed;
  }

  @Override
  public void clear() {
    E cur = sentinel.getNext();
    while (cur != sentinel) {
      E next = cur.getNext();
      reset(cur);
      cur = next;
    }
    reset(sentinel);
  }

  @Override
  public E get(int index) {
    if (index < 0 || index > size) {
      throw new IndexOutOfBoundsException(
          String.format("index %d into list of size %d", index, size));
    }
    E e = null;
    if (2 * index < size) {
      e = sentinel.getNext();
      for (int i = 0; i < index; ++i) {
        e = e.getNext();
      }
    } else {
      e = sentinel.getPrev();
      for (int i = size - 1; i > index; --i) {
        e = e.getPrev();
      }
    }
    return e;
  }

  @Override
  public E set(int index, E element) {
    E old = get(index);
    replace(old, element);
    return old;
  }

  @Override
  public void add(int index, E element) {
    if (index == 0) {
      insert(sentinel, sentinel.getNext(), element);
    }
    E after = get(index);
    insert(after.getPrev(), after, element);
  }

  @Override
  public E remove(int index) {
    E e = get(index);
    replace(e, null);
    return e;
  }

  class Position {
    public int index = -1;
    public E entry = null;
  }

  public Position findForward(Object o) {
    Position pos = new Position();
    if (o == null) {
      return pos;
    }
    int i = 0;
    for (E e = sentinel.getNext(); e != sentinel && i < size; e = e.getNext()) {
      if (o.equals(e)) {
        pos.index = i;
        pos.entry = e;
        return pos;
      }
      ++i;
    }
    return pos;
  }

  public Position findBackward(Object o) {
    Position pos = new Position();
    if (o == null) {
      return pos;
    }
    int i = size - 1;
    for (E e = sentinel.getPrev(); e != sentinel && i >= 0; e = e.getPrev()) {
      if (o.equals(e)) {
        pos.index = i;
        pos.entry = e;
        return pos;
      }
      --i;
    }
    return pos;
  }

  @Override
  public int indexOf(Object o) {
    return findForward(o).index;
  }

  @Override
  public int lastIndexOf(Object o) {
    return findBackward(o).index;
  }

  @Override
  public List<E> subList(int fromIndex, int toIndex) {
    if (fromIndex < 0 || fromIndex > size || toIndex < 0 || toIndex > size || fromIndex > toIndex) {
      throw new IndexOutOfBoundsException();
    }
    IntrusiveList<E> sub = null;
    try {
      sub = new IntrusiveList<E>(eClass.newInstance());
      E e = get(fromIndex);
      for (int i = fromIndex; i < toIndex; ++i) {
        sub.add(e);
        e = e.getNext();
      }
    } catch (InstantiationException | IllegalAccessException e) {
      e.printStackTrace();
    }
    return sub;
  }

  @Override
  public ListIterator<E> listIterator() {
    return new ListIterator<E>() {
      private E cursor = sentinel;
      private int index = 0;
      private E lastReturned = null;

      @Override
      public boolean hasNext() {
        return cursor.getNext() != sentinel;
      }

      @Override
      public E next() {
        E next = cursor.getNext();
        cursor = next;
        ++index;
        lastReturned = next;
        return next;
      }

      @Override
      public boolean hasPrevious() {
        return cursor.getPrev() != sentinel;
      }

      @Override
      public E previous() {
        E prev = cursor.getPrev();
        cursor = prev;
        --index;
        lastReturned = prev;
        return prev;
      }

      @Override
      public int nextIndex() {
        return index;
      }

      @Override
      public int previousIndex() {
        return index - 1;
      }

      @Override
      public void remove() {
        lastReturned.getList().removeElement(lastReturned);
      }

      @Override
      public void set(E e) {
        lastReturned.getList().replace(lastReturned, e);
      }

      @Override
      public void add(E e) {
        insert(cursor.getPrev(), cursor.getNext(), e);
      }

    };
  }
}
