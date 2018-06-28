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

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.helpers.HedwigMessageHelper;
import edu.stonybrook.kurma.message.CreateDir;
import edu.stonybrook.kurma.message.CreateFile;
import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.message.RemoveDir;
import edu.stonybrook.kurma.message.Rename;
import edu.stonybrook.kurma.message.SetAttributes;
import edu.stonybrook.kurma.message.UnlinkFile;
import edu.stonybrook.kurma.message.UpdateFile;

/**
 * A container of GatewayMessage to enable parallel handling of messages while ensuring the correct
 * order of dependent operations.
 *
 * Dependent operations are operations on the same file or directory. They need to be sorted to
 * ensure correctness. For example, when creating a file in a newly created directory, the message
 * of the directory creation need to be handled before the message of the file creation.
 *
 * For namespace operations (including creation and deletion of files and directories), this manager
 * synchronizes operations by the involved directories. When there are multiple directories involved
 * for one operation, such as creating a directory, the parent directory is locked before locking
 * the directory being created.
 *
 * For file operations (including read, write, and setattrs), this manager synchronizes operations
 * by the involved file.
 *
 * For rename operations across different directories, this manager use the per-volume lock of the
 * involved volume.
 *
 * Member methods of this class are NOT thread-safe; external synchronization is needed for multiple
 * threads.
 *
 * @author mchen
 *
 */
public class GatewayMessageManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMessageManager.class);

  class PendingMessage {
    public PendingMessage(Message msg) {
      this.msg = msg;
      checked = false;
    }

    public Message getMessage() {
      return msg;
    }

    public void setChecked() {
      checked = true;
    }

    public boolean isChecked() {
      return checked;
    }

    private Message msg;
    /**
     * Has the related lock been checked? If yes, this message will be in "checkedMessages".
     */
    private boolean checked;
  }

  /**
   * A list of received messages waiting to be handled.
   */
  private List<PendingMessage> pendingMessages;

  /**
   * A map of messages whose locks have been checked. For a lock key "lk", the first element of the
   * corresponding list, i.e., checkedMessages[lk], holds the lock. When the first element pops out
   * of the list, the lock is released and the following element in the list automatically acquires
   * the lock.
   */
  private Map<LockKey, List<Message>> checkedMessages;

  public GatewayMessageManager() {
    pendingMessages = new LinkedList<PendingMessage>();
    checkedMessages = new HashMap<LockKey, List<Message>>();
  }

  /**
   * Return whether there is any pending message in this manager, including messages that could not
   * be handled due to dependence, and messages that are being processed.
   *
   * @return true if not empty.
   */
  public boolean isEmpty() {
    return pendingMessages.isEmpty() && checkedMessages.isEmpty();
  }

  /**
   * Add a message (received from Hedwig) into this manager.
   *
   * @param msg The message to add.
   */
  public void putMessage(Message msg) {
    pendingMessages.add(new PendingMessage(msg));
  }

  /**
   * Return the list of files/directories that need to be locked when processing the specified
   * message. Note the order of the list is should be consistent (parent first) to avoid deadlock.
   *
   * @param msg
   * @return
   */
  private List<LockKey> getLockKeyOfMessage(Message msg) {
    List<LockKey> lockKeys = new LinkedList<>();
    try {
      GatewayMessage gm = HedwigMessageHelper.fromHedwigMessage(msg);
      switch (gm.getOp_type()) {
        case ACK_MASTER:
        case CLAIM_MASTER:
        case COMMIT:
        case CREATE_VOLUME:
          break;
        case CREATE_DIR:
          CreateDir cd = gm.getOp().getCreate_dir();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), cd.getDir().getParent_oid(),
              cd.getDir().getOid());
          break;
        case CREATE_FILE:
          CreateFile cf = gm.getOp().getCreate_file();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), cf.getFile().getParent_oid(),
              cf.getFile().getOid());
          break;
        case REMOVE_DIR:
          RemoveDir rd = gm.getOp().getRemove_dir();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), rd.getParent_oid(), rd.getOid());
          break;
        case RENAME:
          Rename rn = gm.getOp().getRename();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), rn.getDst_dir_oid(),
              rn.getSrc_dir_oid(), rn.getSrc_oid());
          break;
        case SET_ATTRIBUTES:
          SetAttributes sa = gm.getOp().getSet_attrs();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), sa.getOid());
          break;
        case UNLINK_FILE:
          UnlinkFile rf = gm.getOp().getUnlink_file();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), rf.getParent_oid(), rf.getOid());
          break;
        case UPDATE_FILE:
          UpdateFile uf = gm.getOp().getUpdate_file();
          lockKeys = LockKey.lockKeysFor(gm.getVolumeid(), uf.getFile_oid());
          break;
        case REMOVE_VOLUME:
        case RENOUNCE_MASTER:
        default:
          LOGGER.error("operation %s not supported yet", gm.getOp_type());
          break;
      }
    } catch (Exception e) {
      LOGGER.error("could not get LockKey of message", e);
      e.printStackTrace();
    }
    return lockKeys;
  }

  /**
   * Poll if there is a Message to handle.
   *
   * @return the next Message to handle, or null if not available.
   */
  public Message pollMessage() {
    Iterator<PendingMessage> it = pendingMessages.iterator();
    while (it.hasNext()) {
      PendingMessage pm = it.next();
      boolean checked = pm.isChecked();
      Message msg = pm.getMessage();

      // check whether all locks are acquired
      boolean allLocksAcquired = true;
      List<LockKey> lockKeys = getLockKeyOfMessage(msg);
      Iterator<LockKey> lkIt1 = lockKeys.iterator();
      while (lkIt1.hasNext()) {
        LockKey lk = lkIt1.next();
        List<Message> msgList = checkedMessages.get(lk);
        if (msgList == null) {
          msgList = new LinkedList<Message>();
          checkedMessages.put(lk, msgList);
        }
        if (!checked) {
          msgList.add(msg);
        }
        if (msgList.get(0) != msg) {
          allLocksAcquired = false;
        }
      }
      pm.setChecked();

      if (allLocksAcquired) {
        it.remove();
        return msg;
      }
    }

    return null;
  }

  /**
   * Mark the completion of the specified message.
   *
   * @param msg The completed message.
   */
  public void endMessage(Message msg) {
    List<LockKey> lockKeys = getLockKeyOfMessage(msg);
    Iterator<LockKey> it = lockKeys.iterator();
    while (it.hasNext()) {
      LockKey lk = it.next();
      List<Message> msgList = checkedMessages.get(lk);
      assert (msgList != null);
      if (!msgList.remove(msg)) {
        LOGGER.error("message does not exist: {}", lk);
      }
      if (msgList.isEmpty()) {
        checkedMessages.remove(lk);
      }
    }
  }
}
