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
package edu.stonybrook.kurma.cloud.drivers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStores;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.openstack.swift.v1.blobstore.RegionScopedBlobStoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stonybrook.kurma.cloud.Kvs;

public class RackspaceKvs extends Kvs {

  private static final Logger logger = LoggerFactory.getLogger(RackspaceKvs.class);

  private final static String RACKSPACE_US_ID = "rackspace-cloudfiles-us";
  /*
   * Supported regions for Cloud Files US: - Dallas (DFW) - Chicago (ORD) - North Virginia (IAD) -
   * Hong Kong (HKG) - Sydney, Australia (SYD)
   */
  private final static String REGION_ID = "IAD";

  private transient final BlobStore blobStore;

  // Note: this is an estimation of bytes used, not necessary accurate.
  private AtomicLong bytesUsed;
  private AtomicLong count;

  public RackspaceKvs(String id, String accessKey, String secretKey, String container,
      boolean enabled, int cost) throws IOException {
    super(id, container, enabled, cost);

    try {
      blobStore = ContextBuilder.newBuilder(RACKSPACE_US_ID).credentials(accessKey, secretKey)
          .buildView(RegionScopedBlobStoreContext.class).getBlobStore(REGION_ID);
    } catch (NoSuchElementException e) {
      logger.error("Could not initialize {} KvStore", id, e);
      throw new IOException(e);
    }

    this.createContainer();

    bytesUsed = new AtomicLong(0);
    count = new AtomicLong(0);
  }

  @Override
  public void put(String key, InputStream value, int size) throws IOException {
    try {
      Blob blob = this.blobStore.blobBuilder(key).payload(value).contentLength(size).build();
      this.blobStore.putBlob(this.rootContainer, blob);
      count.incrementAndGet();
      bytesUsed.addAndGet(key.getBytes().length + size);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public InputStream get(String key) throws IOException {
    try {
      Blob blob = this.blobStore.getBlob(this.rootContainer, key);
      if (blob == null)
        return null;
      return blob.getPayload().openStream();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    try {
      this.blobStore.removeBlob(this.rootContainer, key);
      long cnt = count.decrementAndGet();
      if (cnt == 0) {
        bytesUsed.set(0);
      } else {
        bytesUsed.addAndGet(-bytesUsed.get() / cnt);
      }
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  @Override
  public List<String> list() throws IOException {
    try {
      List<String> keys = new ArrayList<String>();
      for (StorageMetadata resourceMd : BlobStores.listAll(this.blobStore, this.rootContainer,
          ListContainerOptions.NONE))
        keys.add(resourceMd.getName());
      return keys;
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  private void createContainer() throws IOException {
    try {
      this.blobStore.createContainerInLocation(null, this.rootContainer);
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  @Override
  public void shutdown() throws IOException {}

  @Override
  public long bytes() throws IOException {
    return bytesUsed.get();
  }
}
