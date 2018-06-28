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

import edu.stonybrook.kurma.meta.ObjectAttributes;

public class AttributesHelper {
  /*
   * Copied from http://lxr.free-electrons.com/source/include/uapi/linux/stat.h#L21
   */
  public static int S_IFMT = 00170000;
  public static int S_IFSOCK = 0140000;
  public static int S_IFLNK = 0120000;
  public static int S_IFREG = 0100000;
  public static int S_IFBLK = 0060000;
  public static int S_IFDIR = 0040000;
  public static int S_IFCHR = 0020000;
  public static int S_IFIFO = 0010000;
  public static int S_ISUID = 0004000;
  public static int S_ISGID = 0002000;
  public static int S_ISVTX = 0001000;
  public static int S_IRWXU = 00700;
  public static int S_IRUSR = 00400;
  public static int S_IWUSR = 00200;
  public static int S_IXUSR = 00100;
  public static int S_IRWXG = 00070;
  public static int S_IRGRP = 00040;
  public static int S_IWGRP = 00020;
  public static int S_IXGRP = 00010;
  public static int S_IRWXO = 00007;
  public static int S_IROTH = 00004;
  public static int S_IWOTH = 00002;
  public static int S_IXOTH = 00001;

  // bits for hints in attribute
  public static int ATTR_HINT_SYMLINK = 0X0001;
  public static int ATTR_HINT_SNAPSHOT = 0X0002;
  public static int ATTR_HINT_NAME_SUFFIX = 0x0004;
  public static int ATTR_HINT_NO_REPL = 0x0008;

  public static ObjectAttributes newDirAttributes() {
    ObjectAttributes dirAttrs = new ObjectAttributes();
    dirAttrs.change_time = System.currentTimeMillis();
    dirAttrs.unsetNlinks();
    dirAttrs.unsetRawdev();
    dirAttrs.setMode(0755);
    dirAttrs.setHints(0);
    return dirAttrs;
  }

  public static ObjectAttributes duplicateAttributes(ObjectAttributes attrs) {
    ObjectAttributes dup = new ObjectAttributes();
    dup.setFilesize(attrs.getFilesize());
    dup.setOwner_id(attrs.getOwner_id());
    dup.setGroup_id(attrs.getGroup_id());
    dup.setMode(attrs.getMode());
    dup.setNlinks(attrs.getNlinks());
    dup.setCreate_time(attrs.getCreate_time());
    dup.setAccess_time(attrs.getAccess_time());
    dup.setModify_time(attrs.getModify_time());
    dup.setChange_time(attrs.getChange_time());
    dup.setNblocks(attrs.getNblocks());
    dup.setBlock_shift(attrs.getBlock_shift());
    dup.setRawdev(attrs.getRawdev());
    dup.setAcl(attrs.getAcl());
    dup.setDatasize(attrs.getDatasize());
    dup.setHints(attrs.getHints());
    return dup;
  }

  public static ObjectAttributes newFileAttributes() {
    ObjectAttributes attrs = new ObjectAttributes();
    long timestamp = System.currentTimeMillis();
    attrs.setCreate_time(timestamp);
    attrs.setAccess_time(timestamp);
    attrs.setChange_time(timestamp);
    attrs.setModify_time(timestamp);
    attrs.setNlinks(1);
    attrs.setFilesize(0);
    attrs.setNblocks(0);
    attrs.setMode(0644);
    attrs.setHints(0);
    return attrs;
  }

  /**
   * Change only access time. Used for read and exec.
   *
   * See Table15-2 of Linux Programming Interface.
   *
   * @param attrs
   */
  public static void updateAccessTime(ObjectAttributes attrs) {
    attrs.setAccess_time(System.currentTimeMillis());
  }

  /**
   * Change only ctime but not atime or mtime. Used for rename, setxattr, unlink, chmod, and chown.
   *
   * @param attrs
   */
  public static void updateChangeTime(ObjectAttributes attrs) {
    attrs.setChange_time(System.currentTimeMillis());
  }

  /**
   * Whenever mtime is update, ctime will be updated as well. Used for write, truncate, open
   * (truncating existing file), msync.
   *
   * @param attrs
   */
  public static void updateModifyTime(ObjectAttributes attrs) {
    long time = System.currentTimeMillis();
    attrs.setChange_time(time);
    attrs.setModify_time(time);
  }

  /**
   * Used during file creation (mkdir, mkfifo, open, mknod, symlink), mmap, utimes, and pipe.
   *
   * @param attrs
   */
  public static void updateAllTimestamps(ObjectAttributes attrs) {
    long time = System.currentTimeMillis();
    attrs.setAccess_time(time);
    attrs.setChange_time(time);
    attrs.setModify_time(time);
  }

  public static void addBlocks(ObjectAttributes attrs, int n) {
    attrs.nblocks += n;
  }

  public static boolean setAttrs(ObjectAttributes attrs, ObjectAttributes newSettings) {
    boolean changed = false;
    ObjectAttributes oldAttrs = new ObjectAttributes();

    if (newSettings.isSetMode()) {
      oldAttrs.setMode(attrs.getMode());
      if (AttributesHelper.chmod(attrs, newSettings.getMode())) {
        changed = true;
      } else {
        return false;
      }
    }

    if (newSettings.isSetOwner_id()) {
      oldAttrs.setOwner_id(attrs.getOwner_id());
      if (AttributesHelper.chown(attrs, newSettings.getOwner_id())) {
        changed = true;
      } else {
        if (oldAttrs.isSetMode()) {
          AttributesHelper.chmod(attrs, oldAttrs.getMode());
        }
        return false;
      }
    }

    if (newSettings.isSetGroup_id()) {
      oldAttrs.setGroup_id(attrs.getGroup_id());
      if (AttributesHelper.chgrp(attrs, newSettings.getGroup_id())) {
        changed = true;
      } else {
        if (oldAttrs.isSetMode()) {
          AttributesHelper.chmod(attrs, oldAttrs.getMode());
        }
        if (oldAttrs.isSetOwner_id()) {
          AttributesHelper.chown(attrs, oldAttrs.getOwner_id());
        }
        return false;
      }
    }

    // TODO: check if needed
    if (newSettings.isSetCreate_time()) {
      attrs.setCreate_time(attrs.getCreate_time());
      changed = true;
    }

    if (newSettings.isSetAccess_time()) {
      attrs.setAccess_time(attrs.getAccess_time());
      changed = true;
    }

    if (newSettings.isSetModify_time()) {
      attrs.setModify_time(attrs.getModify_time());
      changed = true;
    }

    if (newSettings.isSetChange_time()) {
      attrs.setChange_time(attrs.getChange_time());
    } else if (changed) {
      attrs.setChange_time(System.currentTimeMillis());
    }

    return true;
  }

  public static boolean chmod(ObjectAttributes attrs, int mode) {
    // TODO need permission check?
    attrs.setMode(mode);
    return true;
  }

  public static boolean chown(ObjectAttributes attrs, int owner) {
    attrs.setOwner_id(owner);
    return true;
  }

  public static boolean chgrp(ObjectAttributes attrs, int group) {
    attrs.setGroup_id(group);
    return true;
  }

  /**
   * Used to check if file is symlink LSB of 'hints' will be set in case of symlink
   *
   * @param attrs
   */
  public static boolean isSymlink(ObjectAttributes attrs) {
    return (attrs.getHints() & ATTR_HINT_SYMLINK) != 0;
  }

  public static boolean isShared(ObjectAttributes attrs) {
    int mode = attrs.getMode();
    return (mode & S_IRWXG) != 0 || (mode & S_IRWXO) != 0;
  }

  public static boolean hasSnapshot(ObjectAttributes attrs) {
    return (attrs.getHints() & ATTR_HINT_SNAPSHOT) != 0;
  }

  public static void setSnapshot(ObjectAttributes attrs, boolean has) {
    if (has) {
      attrs.setHints(attrs.getHints() | ATTR_HINT_SNAPSHOT);
    } else {
      attrs.setHints(attrs.getHints() & ~ATTR_HINT_SNAPSHOT);
    }
  }

  /**
   * Check if file name is suffixed name as a part of conflict resolution
   *
   * @param attrs
   */
  public static boolean isSuffixedName(ObjectAttributes attrs) {
    return (attrs.getHints() & ATTR_HINT_NAME_SUFFIX) != 0;
  }

  /**
   * Check if replication is required for given objectID
   *
   * @param attrs
   */
  public static boolean isNoReplicationSet(ObjectAttributes attrs) {
    return (attrs.getHints() & ATTR_HINT_NO_REPL) != 0;
  }

  /**
   * Set bit for suffixName hint
   *
   * @param attrs
   */
  public static void setSuffixNameBit(ObjectAttributes attrs) {
    attrs.setHints(attrs.getHints() | ATTR_HINT_NAME_SUFFIX);
  }

  /**
   * Set bit for NoReplication Required hint
   *
   * @param attrs
   */
  public static void setNoReplicationBit(ObjectAttributes attrs) {
    attrs.setHints(attrs.getHints() | ATTR_HINT_NO_REPL);
  }
}
