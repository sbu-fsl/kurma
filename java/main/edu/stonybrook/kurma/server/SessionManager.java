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

import static edu.stonybrook.kurma.util.LoggingUtils.binary;
import static edu.stonybrook.kurma.util.LoggingUtils.hash;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.stonybrook.kurma.util.IntrusiveList;
import edu.stonybrook.kurma.util.IntrusiveNode;

public class SessionManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(SessionManager.class);

  private ConcurrentHashMap<ByteBuffer, IntrusiveNode<KurmaSession>> sessions;

  /**
   * A simple LRU list. The least recently used session is at the tail (Last) of the list.
   */
  private final IntrusiveList<IntrusiveNode<KurmaSession>> sessionList;
  private HashFunction hashFn;
  private Timer timer;
  private SecureRandom random;

  public SessionManager(KurmaHandler kh, int claimIntervalMs) {
    hashFn = Hashing.sha1();
    sessions = new ConcurrentHashMap<>();
    sessionList = new IntrusiveList<IntrusiveNode<KurmaSession>>(new IntrusiveNode<KurmaSession>());
    timer = new Timer();
    timer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        reclaimSessions();
      }
    }, claimIntervalMs, claimIntervalMs);
    random = new SecureRandom();
  }

  public KurmaSession createSession(ByteBuffer clientId, VolumeHandler vh) throws Exception {
    Preconditions.checkNotNull(vh);
    clientId.mark();
    KurmaSession session =
        new KurmaSession(clientId, vh, hashFn.newHasher().putLong(random.nextLong()));
    IntrusiveNode<KurmaSession> node = new IntrusiveNode<KurmaSession>(session);
    ByteBuffer sid = session.getSessionId();
    sessions.put(sid, node);
    synchronized (sessionList) {
      sessionList.addFirst(node);
    }
    clientId.reset();
    LOGGER.info("session {} created for clientId {}", hash(sid), hash(clientId));
    LOGGER.info("session created: {}", binary(sid));
    return session;
  }

  public KurmaSession getSession(ByteBuffer sessionId) {
    IntrusiveNode<KurmaSession> node = sessions.get(sessionId);
    if (node == null) {
      LOGGER.warn("Session {} does not exist or has timed out", hash(sessionId));
      LOGGER.info("session in use: {}", binary(sessionId));
      return null;
    }
    KurmaSession sess = node.getData();
    sess.updateTimestamp();
    synchronized (sessionList) {
      sessionList.removeElement(node);
      sessionList.addFirst(node);
    }
    return sess;
  }

  public int reclaimSessions() {
    List<IntrusiveNode<KurmaSession>> timedOutSessions = new ArrayList<>();
    synchronized (sessionList) {
      while (!sessionList.isEmpty()) {
        IntrusiveNode<KurmaSession> node = sessionList.getLast();
        KurmaSession sess = node.getData();
        if (!sess.hasTimedOut()) {
          break;
        }
        ByteBuffer sid = sess.getSessionId();
        sessions.remove(sid, node);
        timedOutSessions.add(node);
        sessionList.removeElement(node);
        LOGGER.debug("session {} reclaimed", hash(sid));
      }
    }

    int n = timedOutSessions.size();
    Iterator<IntrusiveNode<KurmaSession>> it = timedOutSessions.iterator();
    while (it.hasNext()) {
      IntrusiveNode<KurmaSession> node = it.next();
      KurmaSession sess = node.getData();
      if (!sess.closeAllFiles()) {
        LOGGER.error("could not close files of timed out session! Adding it back.");
        synchronized (sessionList) {
          sessionList.addLast(node);
        }
        sessions.put(sess.getSessionId(), node);
        --n;
      }
      it.remove();
    }

    if (n > 0) {
      LOGGER.info("reclaimed {} sessions.", n);
    }

    return n;
  }

  public int getSessionCount() {
    return sessions.size();
  }

  public VolumeHandler getVolumeHandler(ByteBuffer sessionId) {
    return getSession(sessionId).getVolumeHandler();
  }

  public ByteBuffer getClientId(ByteBuffer sessionId) {
    return getSession(sessionId).getClientId();
  }
}
