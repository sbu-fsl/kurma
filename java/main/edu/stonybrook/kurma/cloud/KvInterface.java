/**
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
import java.io.InputStream;
import java.util.List;

/**
 * An interface of key-value store.
 * 
 * @author mchen
 *
 */
public interface KvInterface {
  /**
   * Put/upload a key/value pair.
   *
   * @param key
   * @param value The stream provides the value.
   * @param size The size of the value;
   * @throws IOException
   */
  public void put(String key, InputStream value, int size) throws IOException;

  /**
   * Get the stream of the value associated with the key.
   *
   * NOTE: Caller has to close the stream when done. Otherwise, resource may get exhausted.
   *
   * @param key
   * @return
   * @throws IOException
   */
  public InputStream get(String key) throws IOException;

  public List<String> list() throws IOException;

  public void delete(String key) throws IOException;

  public long bytes() throws IOException;

  /**
   * @return The unique ID of the key-value store.
   */
  public String getId();
}
