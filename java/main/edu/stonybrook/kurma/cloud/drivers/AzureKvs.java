/**
 * Copyright (C) 2013 EURECOM (www.eurecom.fr)
 *
 * Copyright (C) 2015 Ming Chen <v.mingchen@gmail.com>
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
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

import edu.stonybrook.kurma.cloud.Kvs;
import edu.stonybrook.kurma.util.ByteBufferOutputStream;

public class AzureKvs extends Kvs {

  private transient static Logger logger = LoggerFactory.getLogger(AzureKvs.class);

  private static final String ERROR_BLOB_NOT_FOUND = "BlobNotFound";

  private transient final CloudBlobClient blobClient;
  private transient CloudBlobContainer containerRef;
  private transient HashMap<String, CloudBlockBlob> blobRefs; // cached
                                                              // references of
                                                              // already used
                                                              // blobs
  private AtomicLong bytesUsed;

  public AzureKvs(String id, String accessKey, String secretKey, String container, boolean enabled,
      int cost) throws IOException {
    super(id, container, enabled, cost);

    String storageConnectionString =
        "DefaultEndpointsProtocol=http;" + "AccountName=" + accessKey + ";AccountKey=" + secretKey;
    logger.info("Azure connection String: '{}'", storageConnectionString);
    CloudStorageAccount storageAccount;
    try {
      storageAccount = CloudStorageAccount.parse(storageConnectionString);
      this.blobClient = storageAccount.createCloudBlobClient();
      // this.blobClient.setSingleBlobPutThresholdInBytes(30000000); // 30MB
      // this.blobClient.setConcurrentRequestCount(200);
    } catch (InvalidKeyException | URISyntaxException e) {
      logger.error("Could not initialize {} KvStore", id, e);
      throw new IOException(e);
    }

    this.createContainer();
    this.blobRefs = new HashMap<String, CloudBlockBlob>();

    long bytes = 0;
    for (ListBlobItem blobItem : containerRef.listBlobs()) {
      // If the item is a blob, not a virtual directory.
      if (blobItem instanceof CloudBlob) {
        CloudBlob blob = (CloudBlob) blobItem;
        bytes += blob.getName().getBytes().length;
        bytes += blob.getProperties().getLength();
      }
    }
    bytesUsed = new AtomicLong(bytes);
  }

  @Override
  public void put(String key, InputStream value, int size) throws IOException {
    try {
      CloudBlockBlob blob = this.containerRef.getBlockBlobReference(key);
      blob.getProperties().setContentMD5(null);
      blob.upload(value, size);
      this.blobRefs.put(key, blob);
      bytesUsed.addAndGet(key.getBytes().length + size);
    } catch (URISyntaxException | StorageException | IOException e) {
      throw new IOException(e);
    }
  }

  @Override
  public InputStream get(String key) throws IOException {
    try {
      CloudBlockBlob blob = this.blobRefs.get(key);
      if (blob == null)
        blob = this.containerRef.getBlockBlobReference(key);
      ByteBufferOutputStream bbos = new ByteBufferOutputStream();
      blob.download(bbos);
      return bbos.toInputStream();
    } catch (URISyntaxException | StorageException e) {

      if (e instanceof StorageException) {
        StorageException se = (StorageException) e;
        if (ERROR_BLOB_NOT_FOUND.equals(se.getErrorCode()))
          return null;
      }

      throw new IOException(e);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    try {
      CloudBlockBlob blob = this.blobRefs.get(key);
      if (blob == null)
        blob = this.containerRef.getBlockBlobReference(key);
      blob.delete();
      this.blobRefs.remove(key);
      bytesUsed.addAndGet(-key.getBytes().length - blob.getProperties().getLength());
    } catch (URISyntaxException | StorageException e) {

      if (e instanceof StorageException) {
        StorageException se = (StorageException) e;
        if (ERROR_BLOB_NOT_FOUND.equals(se.getErrorCode()))
          return;
      }

      throw new IOException(e);
    }
  }

  @Override
  public List<String> list() throws IOException {
    List<String> keys = new ArrayList<String>();
    for (ListBlobItem blobItem : this.containerRef.listBlobs()) { // TODO
                                                                  // useFlatBlobListing
                                                                  // flag
                                                                  // to
                                                                  // get
                                                                  // only
                                                                  // blobs
                                                                  // regardless
                                                                  // of
                                                                  // virtual
                                                                  // directories
      if (blobItem instanceof CloudBlob) {
        CloudBlob blob = (CloudBlob) blobItem;
        keys.add(blob.getName());
      }
    }
    return keys;
  }

  private void createContainer() throws IOException {
    try {
      this.containerRef = this.blobClient.getContainerReference(this.rootContainer);
      this.containerRef.createIfNotExists();
    } catch (StorageException | URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void shutdown() throws IOException {}

  @Override
  public long bytes() throws IOException {
    return bytesUsed.get();
  }
}
