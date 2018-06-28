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

import org.apache.bookkeeper.test.PortManager;
import org.apache.hedwig.client.HedwigClient;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.server.LoggingExceptionHandler;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.netty.PubSubServer;
import org.apache.hedwig.util.HedwigSocketAddress;

public class HedwigTestServer {
  protected class StandAloneServerConfiguration extends ServerConfiguration {
    final int port = PortManager.nextFreePort();
    final int sslPort = PortManager.nextFreePort();

    @Override
    public boolean isStandalone() {
      return true;
    }

    @Override
    public int getServerPort() {
      return port;
    }

    @Override
    public int getSSLServerPort() {
      return sslPort;
    }
  }

  public ServerConfiguration getStandAloneServerConfiguration() {
    return new StandAloneServerConfiguration();
  }

  protected PubSubServer server;
  protected ServerConfiguration conf;
  protected HedwigSocketAddress defaultAddress;

  public HedwigSocketAddress getDefaultHedwigAddress() {
    return defaultAddress;
  }

  public void start() throws Exception {
    conf = getStandAloneServerConfiguration();
    defaultAddress =
        new HedwigSocketAddress("localhost", conf.getServerPort(), conf.getSSLServerPort());
    server = new PubSubServer(conf, new ClientConfiguration(), new LoggingExceptionHandler());
    server.start();
  }

  public void close() throws Exception {
    server.shutdown();
  }

  public HedwigClient newHedwigClient() {
    return new HedwigClient(new ClientConfiguration() {
      @Override
      public HedwigSocketAddress getDefaultServerHedwigSocketAddress() {
        return getDefaultHedwigAddress();
      }

      @Override
      public boolean isSubscriptionChannelSharingEnabled() {
        return false;
      }
    });
  }
}
