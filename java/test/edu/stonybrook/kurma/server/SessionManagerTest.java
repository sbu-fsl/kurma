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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.stonybrook.kurma.TestBase;

public class SessionManagerTest extends TestBase {
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    startTestServer(DUMMY_GARBAGE_COLLECTOR);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    closeTestServer();
  }

  private SessionManager sessionManager;

  @Before
  public void setUp() throws Exception {
    sessionManager = new SessionManager(kh, 10000);
  }

  @Test
  public void testCreateAndGet() throws Exception {
    final String clientName = "testCreateAndGetClient1";
    ByteBuffer clientId = ByteBuffer.wrap(clientName.getBytes());
    KurmaSession sess1 = sessionManager.createSession(clientId, vh);
    ByteBuffer sessionId = sess1.getSessionId();
    sessionId.mark();
    int length = sessionId.remaining();
    KurmaSession sess2 = sessionManager.getSession(sessionId);
    assertNotNull(sess2);
    assertEquals(sessionId, sess2.getSessionId());

    // try different ByteBuffer of the same value
    byte[] data = new byte[length + 1 + 1];
    sessionId.reset();
    for (int i = 0; i < length; ++i) {
      data[i + 1] = sessionId.get(i);
    }
    ByteBuffer sidCopy = ByteBuffer.wrap(data, 1, length);
    assertEquals(sessionId, sidCopy);
    KurmaSession sess3 = sessionManager.getSession(sidCopy);
    assertNotNull(sess3);
  }

  @Test
  public void testReclaimSessions() throws Exception {
    org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.DEBUG);
    final int N = 10;
    for (int i = 0; i < N; ++i) {
      sessionManager.createSession(ByteBuffer.wrap(String.format("client%d", i).getBytes()), vh);
    }
    assertEquals(0, sessionManager.reclaimSessions());
    assertEquals(N, sessionManager.getSessionCount());
    Thread.sleep(config.getSessionTimeout() * 1000 + 10000);
    // The timer should have reclaimed all sessions by now.
    assertEquals(0, sessionManager.getSessionCount());
  }
}
