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

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.hedwig.client.api.MessageHandler;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import edu.stonybrook.kurma.helpers.GatewayHelper;
import edu.stonybrook.kurma.helpers.HedwigMessageHelper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.message.CreateDir;
import edu.stonybrook.kurma.message.CreateFile;
import edu.stonybrook.kurma.message.CreateVolume;
import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.message.RemoveDir;
import edu.stonybrook.kurma.message.Rename;
import edu.stonybrook.kurma.message.SetAttributes;
import edu.stonybrook.kurma.message.UnlinkFile;
import edu.stonybrook.kurma.message.UpdateFile;
import edu.stonybrook.kurma.meta.VolumeInfo;
import edu.stonybrook.kurma.server.KurmaHandler;
import edu.stonybrook.kurma.server.VolumeHandler;

/**
 * Handle message received from other gateways.
 *
 * @author mchen
 *
 */
public final class GatewayMessageHandler implements MessageHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayMessageHandler.class);
  public static final String ROOT_REPLICATOR_ID = "__KURMA_REPLICATOR__";
  private final KurmaHandler kurmaHandler;
  private int successCount = 0;
  private int receivedMsgCount = 0;
  private int replicationProcessDelay = 0;

  private final GatewayMessageManager messageManager;
  private ExecutorService executor;
  private CompletionService<Message> completeService;

  public GatewayMessageHandler(KurmaHandler kh, int nthreads) throws Exception {
    this(kh, 0, nthreads);
  }

  public GatewayMessageHandler(KurmaHandler kh, int replicationProcessDelay, int nthreads)
      throws Exception {
    this.kurmaHandler = kh;
    this.replicationProcessDelay = replicationProcessDelay;
    this.messageManager = new GatewayMessageManager();
    this.executor = Executors.newFixedThreadPool(nthreads);
    this.completeService = new ExecutorCompletionService<Message>(executor);
  }

  private class MessageHandleWorker implements Callable<Message> {
    Callback<Void> callback;
    Object context;

    public MessageHandleWorker(Callback<Void> cb, Object context) {
      this.callback = cb;
      this.context = context;
    }

    @Override
    public Message call() throws Exception {
      Message msg = null;
      synchronized (messageManager) {
        while (true) {
          msg = messageManager.pollMessage();
          if (msg == null) {
            messageManager.wait();
          } else {
            break;
          }
        }
      }

      boolean failed = false;
      GatewayMessage message = HedwigMessageHelper.fromHedwigMessage(msg);
      try {
        Thread.sleep(replicationProcessDelay);
        if (handle(message)) {
          successCount++;
          LOGGER.debug("gateway message processed");
        } else {
          failed = true;
        }
      } catch (Exception e) {
        LOGGER.error(String.format("could not process gateway message: %s",
            msg.getMsgId().getLocalComponent()), e);
        failed = true;
      } finally {
        if (failed) {
          callback.operationFailed(context, null);
        }
        callback.operationFinished(context, null);
      }

      if (failed) {
        LOGGER.error("gateway message {} {} processing failed, terminating system",
            msg.getMsgId().getLocalComponent(), message.getTimestamp());
        System.exit(1);
      }

      synchronized (messageManager) {
        messageManager.endMessage(msg);
        if (!messageManager.isEmpty()) {
          messageManager.notify();
        }
      }
      return msg;
    }
  }

  @Override
  public void deliver(ByteString topic, ByteString subscriberId, Message msg,
      Callback<Void> callback, Object context) {
    LOGGER.debug("new gateway message recevied: {}", msg.getMsgId().getLocalComponent());
    synchronized (messageManager) {
      receivedMsgCount++;
      messageManager.putMessage(msg);
      completeService.submit(new MessageHandleWorker(callback, context));
      messageManager.notify();
    }
  }

  protected boolean createDirectory(GatewayMessage gm, CreateDir cd) {
    LOGGER.debug("creating directory {} under {}", cd.getDir().getName(),
        cd.getDir().getParent_oid());
    boolean res = false;
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    while (volumeHandler == null) {
      if (ObjectIdHelper.isRootDirectory(cd.getDir().getOid())) {
        try {
          // We cannot find the volumeHandler probably because the message of
          // volume creation is still being processed.
          Thread.sleep(100);
          LOGGER.info("waiting for volume to be created");
          volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
        } catch (InterruptedException e) {
          LOGGER.error("sleeping interrupted", e);
          Thread.currentThread().interrupt();
          break;
        }
      } else {
        LOGGER.error("cannot find volume: {}", gm.getVolumeid());
        System.exit(1);
      }
    }
    res = kurmaHandler.getConflictResolver().resolveIfAnyCreateDirConflict(volumeHandler,
        gm.getGwid(), cd);
    return res;
  }

  protected boolean createFile(GatewayMessage gm, CreateFile cf) {
    boolean res = false;
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    LOGGER.debug("creating file {} at Gateway-{}", cf.getFile().getName(),
        volumeHandler.getConfig().getGatewayName());
    res = kurmaHandler.getConflictResolver().resolveIfAnyCreateFileConflict(volumeHandler,
        gm.getGwid(), cf);
    return res;
  }

  protected boolean removeDirectory(GatewayMessage gm, RemoveDir rd) {
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    LOGGER.debug("Gateway {}: remove directory {}", GatewayHelper.nameOf(gm.getGwid()),
        rd.getName());
    return kurmaHandler.getConflictResolver().resolveIfAnyRemoveDirectoryConflict(volumeHandler,
        rd);
  }

  protected boolean rename(GatewayMessage gm, Rename rn) {
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    LOGGER.debug("Gateway {}: rename {} to {}", GatewayHelper.nameOf(gm.getGwid()),
        rn.getSrc_name(), rn.getDst_name());
    return kurmaHandler.getConflictResolver().resolveIfAnyRenameConflict(volumeHandler, rn);
  }

  protected boolean setAttrs(GatewayMessage gm, SetAttributes sa) {
    LOGGER.debug("Gateway {}: set attributes to {}", GatewayHelper.nameOf(gm.getGwid()),
        sa.getNew_attrs());
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    return kurmaHandler.getConflictResolver().resolveIfAnySetAttrConflict(volumeHandler, sa);
  }

  protected boolean unlinkFile(GatewayMessage gm, UnlinkFile uf) {
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    return kurmaHandler.getConflictResolver().resolveIfAnyUnlinkFileConflict(volumeHandler, uf);
  }

  protected boolean updateFile(GatewayMessage gm, UpdateFile uf) {
    VolumeHandler volumeHandler = kurmaHandler.getVolumeHandler(gm.getVolumeid());
    boolean res = false;
    try {
      res = kurmaHandler.getConflictResolver().resolveIfAnyUpdateFileConflict(volumeHandler, uf,
          gm.getGwid());
    } catch (Exception e) {
      LOGGER.error("could not update block version numbers", e);
      res = false;
    }
    LOGGER.info("==========FILE UPDATED RECEVIED FROM GW {}=========", gm.getGwid());
    return res;
  }

  public boolean createVolume(GatewayMessage msg) {
    CreateVolume cv = msg.getOp().getCreate_volume();
    VolumeInfo vi = cv.getVolume();
    LOGGER.info("recevied creation of {}", vi.getId());
    /*
     * External synchronization will be used to ensure that this never fails
     */
    VolumeHandler vh = kurmaHandler.createVolume(vi);
    if (vh == null) {
      LOGGER.error("failed to create volume {}", vi.getId());
      return false;
    }
    LOGGER.info("new volume {} synced", vi.getId());
    return true;
  }

  public boolean handle(GatewayMessage msg) {
    boolean res = false;
    switch (msg.getOp_type()) {
      case ACK_MASTER:
        break;
      case CLAIM_MASTER:
        break;
      case COMMIT:
        break;
      case CREATE_DIR:
        res = createDirectory(msg, msg.getOp().getCreate_dir());
        break;
      case CREATE_FILE:
        res = createFile(msg, msg.getOp().getCreate_file());
        break;
      case CREATE_VOLUME:
        res = createVolume(msg);
        break;
      case REMOVE_DIR:
        res = removeDirectory(msg, msg.getOp().getRemove_dir());
        break;
      case REMOVE_VOLUME:
        // TODO remove volume
        LOGGER.error("volume removal not supported yet");
        break;
      case RENAME:
        res = rename(msg, msg.getOp().getRename());
        break;
      case RENOUNCE_MASTER:
        break;
      case SET_ATTRIBUTES:
        res = setAttrs(msg, msg.getOp().getSet_attrs());
        break;
      case UNLINK_FILE:
        res = unlinkFile(msg, msg.getOp().getUnlink_file());
        break;
      case UPDATE_FILE:
        res = updateFile(msg, msg.getOp().getUpdate_file());
        break;
      default:
        break;
    }
    return res;
  }

  public int getSuccessCount() {
    return successCount;
  }

  public int getReceivedMsgCount() {
    return receivedMsgCount;
  }

  /* Used for testing purpose */
  public void setSuccessCount(int count) {
    successCount = count;
  }

  /* Used for testing purpose */
  public void setReceivedMsgCount(int count) {
    receivedMsgCount = count;
  }
}
