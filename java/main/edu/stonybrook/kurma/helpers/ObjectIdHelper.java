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

import java.nio.ByteBuffer;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.stonybrook.kurma.meta.Int128;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.meta.ObjectType;

public class ObjectIdHelper {
  public static ObjectID newOid(Int128 id, short gwid, int type, int flag) {
    ObjectID dirOid = new ObjectID();
    dirOid.setId(id);
    dirOid.setCreator(gwid);
    dirOid.setType((byte) type);
    dirOid.setFlags((byte) 0);
    return dirOid;
  }

  public static byte[] encode(ObjectID oid) {
    return ByteBuffer.allocate(20).putLong(oid.id.getId1()).putLong(oid.id.getId2())
        .putShort(oid.creator).put(oid.type).put(oid.flags).array();
  }

  public static boolean equals(ObjectID oid1, ObjectID oid2) {
    return oid1.id.id1 == oid2.id.id1 && oid1.id.id2 == oid2.id.id2 && oid1.creator == oid2.creator;
  }

  public static String hashToString(ObjectID oid) {
    HashFunction hf = Hashing.sha1();
    return hf.hashBytes(encode(oid)).toString();
  }

  /**
   * Get short string to identify objects for logging.
   *
   * @param oid
   * @return a 8-length hex string
   */
  public static String getShortId(ObjectID oid) {
    return Hashing.murmur3_32().hashBytes(encode(oid)).toString();
  }

  public static ObjectID newDirectoryOid(Int128 id, short gwid) {
    return newOid(id, gwid, ObjectType.DIRECTORY.getValue(), 0);
  }

  public static ObjectID newFileOid(Int128 id, short gwid) {
    return newOid(id, gwid, ObjectType.REGULAR_FILE.getValue(), 0);
  }

  public static boolean isDirectory(ObjectID oid) {
    return oid.type == ObjectType.DIRECTORY.getValue();
  }

  public static ObjectID getRootOid(short gwid) {
    return ObjectIdHelper.newDirectoryOid(Int128Helper.getRootId(), gwid);
  }

  public static boolean isRootDirectory(ObjectID oid) {
    return isDirectory(oid) && Int128Helper.isRootId(oid.getId());
  }

  public static boolean isFile(ObjectID oid) {
    return oid.type == ObjectType.REGULAR_FILE.getValue();
  }

  public static String toString(ObjectID oid) {
    return String.format("id1 = %d", oid.getId().getId1());
  }

  /**
   * Compare two ObjectIDs
   * 
   * @param oid1 first oid to compare
   * @param oid2 second oid to compare
   * @return '0' if equal, '1' if oid1 is greater than oid2, '-1' if oid1 is less than oid2
   */
  public static int compare(ObjectID oid1, ObjectID oid2) {
    Int128 oidInt1 = oid1.getId();
    Int128 oidInt2 = oid1.getId();
    int res = oidInt1.compareTo(oidInt2);
    if (res != 0) {
      return res;
    } else {
      short creator1 = oid1.getCreator();
      short creator2 = oid2.getCreator();
      return (creator1 >= creator2) ? ((creator1 > creator2) ? 1 : 0) : -1;
    }
  }
}
