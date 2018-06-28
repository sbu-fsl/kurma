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

import static edu.stonybrook.kurma.helpers.HedwigMessageHelper.toHedwigMessage;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map.Entry;

import javax.crypto.SecretKey;

import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.junit.Before;
import org.junit.Test;

import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.helpers.Int128Helper;
import edu.stonybrook.kurma.helpers.ObjectIdHelper;
import edu.stonybrook.kurma.message.GatewayMessage;
import edu.stonybrook.kurma.meta.Directory;
import edu.stonybrook.kurma.meta.File;
import edu.stonybrook.kurma.meta.KeyMap;
import edu.stonybrook.kurma.meta.ObjectID;
import edu.stonybrook.kurma.util.KurmaKeyGenerator;

public class GatewayMessageManagerTest {
  private static Long OID_MAJOR = 2222L;
  private IGatewayConfig gatewayConfig;
  private GatewayMessageBuilder messageBuilder;
  private KurmaKeyGenerator keyGenerator;

  @Before
  public void setUp() throws Exception {
    gatewayConfig = new GatewayConfig(GatewayConfig.KURMA_TEST_CONFIG_FILE);
    messageBuilder = new GatewayMessageBuilder(gatewayConfig);
    keyGenerator = new KurmaKeyGenerator(gatewayConfig);
  }

  private ObjectID getDirOidFromInt(int v) {
    short gwid = gatewayConfig.getGatewayId();
    ObjectID oid = null;
    if (v == 0) {
      oid = ObjectIdHelper.getRootOid(gwid);
    } else {
      oid = ObjectIdHelper.newDirectoryOid(Int128Helper.newId(v, OID_MAJOR), gwid);
    }
    return oid;
  }

  private Message buildCreateDirMessage(String volId, int parentId, int dirId) {
    short gwid = gatewayConfig.getGatewayId();
    Directory dir = new Directory();
    ObjectID parentOid = getDirOidFromInt(parentId);
    dir.setParent_oid(parentOid);
    dir.setOid(ObjectIdHelper.newDirectoryOid(Int128Helper.newId(dirId, OID_MAJOR), gwid));
    GatewayMessage gm = messageBuilder.buildDirCreation("volume1", dir);
    gm.setVolumeid(volId);
    return toHedwigMessage(gm);
  }

  private Message buildCreateFileMessage(String volId, int parentId, int fileId,
      String fileName, SecretKey fileKey, KeyMap keymap) throws Exception {
    File file = new File();
    ObjectID parentOid = getDirOidFromInt(parentId);
    file.setParent_oid(parentOid);
    file.setOid(ObjectIdHelper.newFileOid(Int128Helper.newId(fileId, OID_MAJOR),
        gatewayConfig.getGatewayId()));
    file.setName(fileName);
    if (fileKey == null) {
      Entry<SecretKey, KeyMap> p = keyGenerator.generateKey();
      fileKey = p.getKey();
      keymap = p.getValue();
    }
    GatewayMessage gm = messageBuilder.buildFileCreation(volId, file, fileKey, keymap);
    return toHedwigMessage(gm);
  }

  private Message buildRenameMessage(String volId, int srcDir, int srcFileId, String srcName,
      int dstDir, String dstName) {
    ObjectID srcDirOid = getDirOidFromInt(srcDir);
    ObjectID dstDirOid = getDirOidFromInt(dstDir);
    ObjectID fileOid = ObjectIdHelper.newFileOid(Int128Helper.newId(srcFileId, OID_MAJOR),
        gatewayConfig.getGatewayId());
    GatewayMessage gm =
        messageBuilder.buildRename(volId, srcDirOid, fileOid, srcName, dstDirOid, dstName);
    gm.setVolumeid(volId);
    return toHedwigMessage(gm);
  }

  @Test
  public void testDirectoryCreatedBeforeChildren() {
    Message cdMsg1 = buildCreateDirMessage("volume1", 0, 1);
    Message cdMsg2 = buildCreateDirMessage("volume1", 1, 2);
    Message cdMsg3 = buildCreateDirMessage("volume1", 2, 3);

    GatewayMessageManager manager = new GatewayMessageManager();
    assertTrue(manager.isEmpty());
    assertNull(manager.pollMessage());

    manager.putMessage(cdMsg1);
    manager.putMessage(cdMsg2);
    manager.putMessage(cdMsg3);

    assertFalse(manager.isEmpty());
    Message msg = manager.pollMessage();
    assertTrue(msg == cdMsg1);

    // cdMsg1 should block cdMsg2 and cdMsg3. Therefore, there are still
    // messages in the manager, but none of them can be polled.
    assertFalse(manager.isEmpty());
    assertNull(manager.pollMessage());

    // After cdMsg1 is processed, cdMsg2 should be unblocked, but cdMsg3
    // should still be blocked.
    manager.endMessage(cdMsg1);
    msg = manager.pollMessage();
    assertTrue(msg == cdMsg2);
    assertFalse(manager.isEmpty());
    assertNull(manager.pollMessage());

    // After cdMsg2 is processed, cdMsg3 will be unblocked. The manager is
    // still
    // not empty because cdMsg3 is under process.
    manager.endMessage(cdMsg2);
    msg = manager.pollMessage();
    assertTrue(msg == cdMsg3);
    assertFalse(manager.isEmpty());
    assertNull(manager.pollMessage());

    // Finally, after cdMsg3 is processed, the manager becomes empty.
    manager.endMessage(cdMsg3);
    assertTrue(manager.isEmpty());
    assertNull(manager.pollMessage());
  }

  @Test
  public void testUnrelatedOperationCanGoParallel() {
    Message cdMsgA1 = buildCreateDirMessage("volume1", 10, 11);
    Message cdMsgA2 = buildCreateDirMessage("volume1", 10, 12);
    Message cdMsgB = buildCreateDirMessage("volume1", 20, 21);
    Message cdMsgC = buildCreateDirMessage("volume1", 30, 31);

    GatewayMessageManager manager = new GatewayMessageManager();
    manager.putMessage(cdMsgA1);
    manager.putMessage(cdMsgA2);
    manager.putMessage(cdMsgB);
    manager.putMessage(cdMsgC);
    assertFalse(manager.isEmpty());

    assertTrue(cdMsgA1 == manager.pollMessage());
    assertTrue(cdMsgB == manager.pollMessage());

    manager.endMessage(cdMsgA1);
    assertTrue(cdMsgA2 == manager.pollMessage());
    assertTrue(cdMsgC == manager.pollMessage());

    manager.endMessage(cdMsgC);
    manager.endMessage(cdMsgB);
    manager.endMessage(cdMsgA2);

    assertTrue(manager.isEmpty());
    assertNull(manager.pollMessage());
  }

  @Test
  public void testMultipleVolumesGoParallel() {
    Message cdMsgA = buildCreateDirMessage("volume1", 10, 1);
    Message cdMsgB = buildCreateDirMessage("volume2", 10, 1);
    Message cdMsgC = buildCreateDirMessage("volume3", 10, 1);

    GatewayMessageManager manager = new GatewayMessageManager();
    manager.putMessage(cdMsgA);
    manager.putMessage(cdMsgB);
    manager.putMessage(cdMsgC);
    assertFalse(manager.isEmpty());

    assertTrue(cdMsgA == manager.pollMessage());
    assertTrue(cdMsgB == manager.pollMessage());
    assertTrue(cdMsgC == manager.pollMessage());
    assertNull(manager.pollMessage());

    manager.endMessage(cdMsgC);
    manager.endMessage(cdMsgB);
    manager.endMessage(cdMsgA);
    assertTrue(manager.isEmpty());
  }

  @Test
  public void testRenameANewlyCreatedFile() throws Exception {
    Message fileCreationMsg = buildCreateFileMessage("volume1", 1, 2, "foo", null, null);
    Message fileRenameMsg = buildRenameMessage("volume1", 1, 2, "foo", 1, "bar");
    GatewayMessageManager manager = new GatewayMessageManager();
    manager.putMessage(fileCreationMsg);
    manager.putMessage(fileRenameMsg);
    assertTrue(fileCreationMsg == manager.pollMessage());
    assertNull(manager.pollMessage());
    manager.endMessage(fileCreationMsg);
    assertTrue(fileRenameMsg == manager.pollMessage());
    manager.endMessage(fileRenameMsg);
    assertTrue(manager.isEmpty());
  }
}
