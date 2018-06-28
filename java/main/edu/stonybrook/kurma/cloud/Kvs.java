/**
 * Copyright (C) 2013 EURECOM (www.eurecom.fr)
 *
 * Copyright (C) 2015-2017 Ming Chen <v.mingchen@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package edu.stonybrook.kurma.cloud;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import edu.stonybrook.kurma.util.SynchronizedMovingAverage;
import edu.stonybrook.kurma.util.TimeWindowSum;

/**
 * Abstract base implementation of key-value store.
 * 
 * @author mchen
 *
 */
public abstract class Kvs implements KvInterface {

  protected final String id;
  protected transient boolean enabled;
  protected String rootContainer;

  protected List<KvsFilterInterface> filters;

  /* measures to compare the providers */
  protected SynchronizedMovingAverage writeLatency;
  protected SynchronizedMovingAverage readLatency;
  protected int cost; // $ cents per GB

  public static final int ReadTimeOutSeconds = 60;
  public static final int WriteTimeOutSeconds = 60;

  protected TimeWindowSum failures;

  /**
   * Static Comparator objects for ordering the Kvs list according to both read and write latencies.
   */
  public static final Comparator<Kvs> COMPARATOR_BY_READS = new Comparator<Kvs>() {
    @Override
    public int compare(Kvs kvs1, Kvs kvs2) {
      if (kvs1.getReadLatency() < kvs2.getReadLatency() || !kvs2.isEnabled())
        return -1;
      else if (kvs1.getReadLatency() > kvs2.getReadLatency() || !kvs1.isEnabled())
        return 1;
      else
        return 0;
    }
  };

  public static final Comparator<Kvs> COMPARATOR_BY_WRITES = new Comparator<Kvs>() {
    @Override
    public int compare(Kvs kvs1, Kvs kvs2) {
      if (kvs1.getWriteLatency() < kvs2.getWriteLatency() || !kvs2.isEnabled())
        return -1;
      else if (kvs1.getWriteLatency() > kvs2.getWriteLatency() || !kvs1.isEnabled())
        return 1;
      else
        return 0;
    }
  };

  public Kvs(String id, String container, boolean enabled, int cost) {
    this.id = id;
    this.enabled = enabled;
    this.cost = cost;
    this.rootContainer = container;

    this.readLatency = new SynchronizedMovingAverage();
    this.writeLatency = new SynchronizedMovingAverage();
    this.failures = new TimeWindowSum();
  }

  @Override
  public String getId() {
    return this.id;
  }

  public boolean isEnabled() {
    return this.enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public double getWriteLatency() {
    return writeLatency.get();
  }

  public double getReadLatency() {
    return readLatency.get();
  }

  public int getCost() {
    return this.cost;
  }

  public void setCost(int cost) {
    this.cost = cost;
  }

  public long countRecentFailures() {
    return failures.get();
  }

  public long logFailure() {
    readLatency.add(ReadTimeOutSeconds * 1000);
    writeLatency.add(WriteTimeOutSeconds * 1000);
    return failures.increment();
  }

  public void installFilters(List<KvsFilterInterface> filters) {
    this.filters = filters;
  }

  public void logReadLatency(long ms) {
    readLatency.add(ms);
  }

  public void logWriteLatency(long ms) {
    writeLatency.add(ms);
  }

  public void shutdown() throws IOException {};

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (!(obj instanceof Kvs))
      return false;
    Kvs other = (Kvs) obj;
    if (this.id == null) {
      if (other.id != null)
        return false;
    } else if (!this.id.equals(other.id))
      return false;
    return true;
  }

  @Override
  public String toString() {
    return this.id;
  }

  public String toVerboseString() {
    return "Kvs (" + this.id + ") [enabled=" + this.enabled + ", writeLatency=" + this.writeLatency
        + ", readLatency=" + this.readLatency + ", cost=" + this.cost + "]";
  }

  /**
   * Empty the data storage root container. ATTENTION: it erases all data stored in the root
   * container!
   *
   * @param kvStore the cloud storage provider
   * @throws IOException
   */
  public static long emptyStorageContainer(Kvs kvStore) throws IOException {
    List<String> keys = kvStore.list();
    long count = 0;
    for (String key : keys) {
      try {
        kvStore.delete(key);
        ++count;
      } catch (IOException e) {
      }
    }
    return count;
  }
}
