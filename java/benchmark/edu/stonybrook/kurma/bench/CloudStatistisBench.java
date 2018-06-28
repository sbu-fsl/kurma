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
package edu.stonybrook.kurma.bench;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.math3.stat.descriptive.AbstractUnivariateStatistic;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.descriptive.rank.Max;
import org.apache.commons.math3.stat.descriptive.rank.Median;
import org.apache.commons.math3.stat.descriptive.rank.Min;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;

import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.cloud.KvsManager;
import edu.stonybrook.kurma.config.GatewayConfig;
import edu.stonybrook.kurma.config.ProviderAccount;
import edu.stonybrook.kurma.util.ByteBufferBackedInputStream;
import edu.stonybrook.kurma.util.RandomBuffer;

/**
 * A benchmark of cloud providers report not only average, but also percentiles, standard
 * deviations.
 *
 * @author mchen
 *
 */
public class CloudStatistisBench {

  private static String DEFAULT_CONFIG_FILE = "clouds.properties";

  @SuppressWarnings("static-access")
  public static void main(String[] args) {
    CommandLineParser parser = new PosixParser();

    Options options = new Options();
    options.addOption("h", "help", false, "print this help message");
    options.addOption("d", "duplicates", false, "use duplicate values or not");
    options.addOption(OptionBuilder.withLongOpt("iterations")
        .withDescription("iterations of each benchmark").hasArg().withArgName("Iter").create('i'));
    options.addOption(OptionBuilder.withLongOpt("key")
        .withDescription("key for differentiating different runs of benchmarks").hasArgs()
        .withArgName("KEY").isRequired().create('k'));
    options.addOption(OptionBuilder.withLongOpt("writing")
        .withDescription("is writing or otherwise reading").create('w'));
    options.addOption(OptionBuilder.withLongOpt("sizes").withValueSeparator(',')
        .withDescription("comma-separated size of value size in KB").hasArgs()
        .withArgName("SizesInKb").create("s"));
    options.addOption(OptionBuilder.withLongOpt("cloud-ids").withValueSeparator(',')
        .withDescription("comma-separated Ids of cloud storage instances").hasArgs()
        .withArgName("KvsIds").create("c"));

    HelpFormatter formatter = new HelpFormatter();
    CommandLine cmd = null;
    try {
      cmd = parser.parse(options, args);
    } catch (ParseException e1) {
      e1.printStackTrace(System.err);
      formatter.printHelp("CloudStatistisBench -k <key>", options);
      System.exit(1);
    }

    if (cmd.hasOption('h')) {
      formatter.printHelp("CloudStatistisBench -k <key>", options);
      System.exit(0);
    }

    int iters = 100;
    if (cmd.hasOption('i')) {
      iters = Integer.valueOf(cmd.getOptionValue('i'));
      System.err.printf("using specified iteration value: %d\n", iters);
    } else {
      System.err.printf("using default iteration value: %d\n", iters);
    }

    // String[] kvsIds = new String[]{"google0", "amazon0", "azure0",
    // "rackspace0"};
    String[] kvsIds = new String[] {"azure0"};
    if (cmd.hasOption('c')) {
      kvsIds = cmd.getOptionValues('c');
      System.err.printf("using specified clouds: %s\n", Arrays.toString(kvsIds));
    } else {
      System.err.printf("using default clouds: %s\n", Arrays.toString(kvsIds));
    }

    // int[] sizeKbs = new int[]{16, 64, 256, 1024, 4096};
    int[] sizeKbs = new int[] {16, 64};
    if (cmd.hasOption('s')) {
      String[] sizesStr = cmd.getOptionValues('s');
      sizeKbs = new int[sizesStr.length];
      for (int i = 0; i < sizesStr.length; ++i) {
        sizeKbs[i] = Integer.parseInt(sizesStr[i]);
      }
      System.err.printf("using specified sizes: %s\n", Arrays.toString(sizeKbs));
    } else {
      System.err.printf("using default sizes: %s\n", Arrays.toString(sizeKbs));
    }

    boolean hasDuplicate = cmd.hasOption('d');
    boolean isWrite = cmd.hasOption('w');
    String key = cmd.getOptionValue('k');
    final int trials = 3;

    List<AbstractUnivariateStatistic> statFns = new ArrayList<>();

    statFns.add(new Mean());
    statFns.add(new StandardDeviation());
    statFns.add(new Min());
    statFns.add(new Percentile(5));
    statFns.add(new Percentile(20));
    statFns.add(new Percentile(25));
    statFns.add(new Median());
    statFns.add(new Percentile(75));
    statFns.add(new Percentile(80));
    statFns.add(new Percentile(90));
    statFns.add(new Percentile(95));
    statFns.add(new Percentile(99));
    statFns.add(new Max());

    System.out.printf("Benchmark %s of '%s' %s duplicates (%d iterations)\n",
        (isWrite ? "writing" : "reading"), Arrays.toString(kvsIds),
        (hasDuplicate ? "with" : "without"), iters);
    System.out.printf(BenchmarkUtils.getContextString());
    System.out.printf("cloud-size:\tmean\tstd\tmin\tp5\tp20\tp25\tp50\tp80\tp90\tp95\tp99\tmax\tfailures\n");
    System.out
        .println("===========================================================================");
    try {
      AbstractConfiguration config = new PropertiesConfiguration(DEFAULT_CONFIG_FILE);
      for (String kvsId : kvsIds) {
        RandomBuffer rand = new RandomBuffer(8887);
        ProviderAccount account = GatewayConfig.parseProviderAccount(config, kvsId);
        Kvs kvs = KvsManager.newKvs(account.getType(), kvsId, account);
        for (int sizeKb : sizeKbs) {
          int size = sizeKb * 1024;
          double[] latencies = new double[iters];
          byte[] buf = null;
          if (hasDuplicate) {
            buf = rand.genRandomBytes(size);
          }
          int failure = 0;
          for (int i = 0; i < iters; ++i) {
            String k = String.format("benchmark-%s-%dKB-%d-%s", key, sizeKb, i,
                hasDuplicate ? "dup" : "nodup");
            if (!hasDuplicate) {
              buf = rand.genRandomBytes(size);
            }
            if (i > 0 && (i % 10) == 0) {
              System.err.println("sleeping for 1 minute...");
              Thread.sleep(1000 * 60);
            }
            int t = 0;
            for (t = 0; t < trials; ++t) {
              try {
                long start = System.nanoTime();
                if (isWrite) {
                  kvs.put(k, new ByteBufferBackedInputStream(ByteBuffer.wrap(buf)), size);
                } else {
                  InputStream ins = kvs.get(k);
                  ins.close();
                }
                long end = System.nanoTime();
                latencies[i] = (end - start) / 1000000.0; // to
                                                          // ms
                break;
              } catch (Exception e) {
                e.printStackTrace(System.err);
                ++failure;
              }
            }
            if (t == trials) {
              System.err.printf("benchmark failed after %d trials", trials);
              System.exit(1);
            }
          }
          System.out.printf("%s-%dKB:", kvsId, sizeKb);
          for (AbstractUnivariateStatistic st : statFns) {
            System.out.printf("\t%.1f", st.evaluate(latencies));
          }
          System.out.printf("\t%d\n", failure);
        }
        kvs.shutdown();
      }
    } catch (Exception e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }
}
