/*
 * Copyright (c) 2013-2018 Ming Chen
 * Copyright (c) 2016-2016 Praveen Kumar Morampudi
 * Copyright (c) 2016-2016 Harshkumar Patel
 * Copyright (c) 2017-2017 Rushabh Shah
 * Copyright (c) 2013-2014 Arun Olappamanna Vasudevan
 * Copyright (c) 2013-2014 Kelong Wang
 * Copyright (c) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
package edu.stonybrook.zookeepertest;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Date;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

public class App implements Watcher {

  private Integer znodenumber;
  private CreateMode znodetype;
  private String hostport;

  App(String conn, Integer znum, Integer ztype)
  {
    if (znum > 0) {
      this.znodenumber = znum;
    } else {
      this.znodenumber = 10000;
    }
    this.hostport = conn;
    if (ztype == 1) {
      znodetype = CreateMode.EPHEMERAL;
    } else if (ztype == 2) {
      znodetype = CreateMode.PERSISTENT;
    } else if (ztype == 3) {
      znodetype = CreateMode.PERSISTENT_SEQUENTIAL;
    } else {
      System.out.println("Bad znode type. Falling back to Ephemeral.");
      znodetype = CreateMode.EPHEMERAL;
    }
  }

  public Integer getZnodenumber() {
    return znodenumber;
  }

  /* The main function */
  public static void main(String[] args) {

    if ((args == null) || (args.length < 4)) {
      System.out.println("Usage: java edu.stonybrook.zookeepertest.App <test run prefix> <action> <Num of znodes> <type of Znodes>");
      System.out.println("Actions:\n1. Create\n2. Read\n3. Update\n4. Delete\n");
      System.out.println("Types:\n1. Ephemeral\n2. Persistent\n3. Persistent Sequential\n");
      System.exit(0);
    }

    String testrun_prefix = args[0];
    Integer action = Integer.parseInt(args[1]);
    Integer znum = Integer.parseInt(args[2]);
    Integer ztype = Integer.parseInt(args[3]);

    String connectstr = "127.0.0.1:2181";
    int[] ret = null;
    App obj = new App(connectstr, znum, ztype);

    try {
      ret = obj.zkCreator(testrun_prefix, action);
    } catch (IOException e) {
      e.printStackTrace();
    }

    double count = (double) (ret[1] - ret[0]) / 1000;
    double rate = ((double)(obj.getZnodenumber() * 1000)) / ((double)(ret[1] - ret[0]));
    System.out.print("\nRunning the operation on " + obj.getZnodenumber() + " znodes took ");
    System.out.format("%5.3f", count);
    System.out.print(" seconds. This is a rate of ");
    System.out.format("%5.3f", rate);
    System.out.print(" znodes per second.\n");
  }

  /* Creates large number of Znodes and monitors
   * memory consumption and creation time. */
  public int[] zkCreator(String pref, Integer action) throws IOException
  {
    int[] ret = new int[5];
    ZooKeeper zk = new ZooKeeper(this.hostport, 10000, this);
    ret[0] = (int) new Date().getTime();
    FileOutputStream outf = null;

    try {
      Integer i = 0;
      String ofname = "";
      if (action == 1) {
        ofname = "log_creator_" + (new Date().getTime());
      } else if (action == 2) {
        ofname = "log_reader_" + (new Date().getTime());
      }  else if (action == 3) {
        ofname = "log_modifier_" + (new Date().getTime());
      } else if (action == 4) {
        ofname = "log_deleter_" + (new Date().getTime());
      }
      String cpath = pref + "_";
      byte[] data = new byte[600];
      byte[] data1 = new byte[800];

      outf = new FileOutputStream(ofname);

      for (i = 0; i < this.znodenumber; i++) {
        String cp = cpath + i.toString();
        if (action == 1) {
          zk.create(cp, data, Ids.OPEN_ACL_UNSAFE, this.znodetype);
        } else if (action == 2) {
          zk.getData(cp, false, null, this);
        } else if (action == 3) {
          zk.setData(cp, data1, -1);
        } else if (action == 4) {
          zk.delete(cp, -1);
        }

        // Take every 1000th iteration for sampling
        if (i % 1000 == 0) {
          String msgstart = "Start of new record: " + i.toString() + "\n";
          outf.write(msgstart.getBytes(Charset.forName("UTF-8")));
          outf.write(Long.toString(new Date().getTime()).getBytes(Charset.forName("UTF-8")));
          msgstart = "\n";
          outf.write(msgstart.getBytes(Charset.forName("UTF-8")));

          Runtime R = Runtime.getRuntime();
          Process p = R.exec("ps -uf");
          p.waitFor();

          BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
          while ((msgstart = reader.readLine()) != null) {
            if (msgstart.matches(".*Dzookeeper.*")) {
              outf.write(msgstart.substring(0, 100).getBytes(Charset.forName("UTF-8")));
              msgstart = "\n";
              outf.write(msgstart.getBytes(Charset.forName("UTF-8")));
            }
          }
          reader.close();
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (outf != null) {
        outf.close();
      }
    }

    ret[1] = (int) new Date().getTime();
    return ret;
  }

  @Override
  public void process(WatchedEvent event) {

  }
}
