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

import java.io.InputStream;
import java.util.AbstractMap;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker thread class in charge of asynchronously performing read operations on cloud stores.
 *
 * @author p.viotti
 */
public class KvsGetWorker implements Callable<Entry<Kvs, InputStream>> {
  private static final Logger LOGGER = LoggerFactory.getLogger(KvsGetWorker.class);

  private final Kvs kvStore;
  private final String key;

  public KvsGetWorker(Kvs kvStore, String key) {
    this.kvStore = kvStore;
    this.key = key;
  }

  @Override
  public Entry<Kvs, InputStream> call() {
    try {
      InputStream result = kvStore.get(key);
      if (result == null)
        throw new Exception();
      return new AbstractMap.SimpleEntry<Kvs, InputStream>(kvStore, result);
    } catch (Exception e) {
      kvStore.logFailure();
      LOGGER.error("failed to read {} from {}: {}", key, kvStore, e.getMessage());
      return null;
    }
  }
}
