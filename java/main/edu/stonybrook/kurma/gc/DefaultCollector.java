/**
 * Copyright Stony Brook University (2015-2018)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package edu.stonybrook.kurma.gc;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;

import edu.stonybrook.kurma.config.IGatewayConfig;
import edu.stonybrook.kurma.server.BlockExecutor;
import edu.stonybrook.kurma.server.DirectoryHandler;
import edu.stonybrook.kurma.server.FileBlock;
import edu.stonybrook.kurma.server.FileHandler;

public class DefaultCollector implements GarbageCollector {
  private static final int WORKER_THREAD_COUNT = 2;

  private DirectoryCollector dirCollector;
  private BlockCollector blockCollector;

  private FileCollector fileCollector;
  private ZnodeCollector znodeCollector;

  private final ExecutorService pool;

  public DefaultCollector(CuratorFramework client, IGatewayConfig config) {
    dirCollector = new DirectoryCollector();
    blockCollector = new BlockCollector(config.getDefaultKvsFacade(), config.getGatewayId());
    fileCollector = new FileCollector();
    znodeCollector = new ZnodeCollector(client);
    pool = Executors.newFixedThreadPool(WORKER_THREAD_COUNT);
  }

  @Override
  public void collectFile(FileHandler fh) {
    pool.submit(fileCollector.getWork(fh));
  }

  @Override
  public void collectDirectory(DirectoryHandler dh) {
    pool.submit(dirCollector.getWork(dh));
  }

  @Override
  public void collectZnode(String zpath) {
    pool.submit(znodeCollector.getWork(zpath));
  }

  @Override
  public void collectBlocks(Collection<FileBlock> blocks) {
    pool.submit(blockCollector.getWork(blocks));
  }

  @Override
  public void flush() {
    try {
      pool.shutdown();
      pool.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void setBlockExecutor(BlockExecutor blockExecutor) {
    blockCollector.setBlockExecutor(blockExecutor);

  }

}
