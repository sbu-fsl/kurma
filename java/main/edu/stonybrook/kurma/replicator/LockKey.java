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
package edu.stonybrook.kurma.replicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.meta.ObjectID;

/**
 * An object to indicate locking of a Kurma file-system object.
 * @author mchen
 *
 */
public class LockKey {
  private String volume;
  private ObjectID objId;

  public LockKey(String volume, ObjectID objId) {
    this.volume = volume;
    this.objId = objId;
  }

  @Override
  public int hashCode() {
    return volume.hashCode() * 13 + objId.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof LockKey) {
      LockKey other = (LockKey) obj;
      return volume.equals(other.volume) && objId.equals(other.objId);
    }
    return false;
  }

  public static List<LockKey> lockKeysFor(String volume, ObjectID... objIds) {
    // sort objects to lock to avoid deadlocks
    Arrays.sort(objIds, new Comparator<ObjectID>() {
      @Override
      public int compare(ObjectID o1, ObjectID o2) {
        return ObjectIdHelper.compare(o1, o2);
      }
    });
    ArrayList<LockKey> keys = new ArrayList<>(objIds.length);
    for (ObjectID oid : objIds) {
      keys.add(new LockKey(volume, oid));
    }
    return keys;
  }
}
