/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.dora.worker.block;

import alluxio.dora.resource.CloseableResource;

import java.util.function.Consumer;

/**
 * A resource lock for block.
 */
public class BlockLock extends CloseableResource<Long> {

  private final long mLockId;
  private final Consumer<Long> mUnlock;

  /**
   * @param lockId lockId
   * @param unlock unlock function
   */
  public BlockLock(long lockId, Consumer<Long> unlock) {
    super(lockId);
    mLockId = lockId;
    mUnlock = unlock;
  }

  @Override
  public void closeResource() {
    mUnlock.accept(mLockId);
  }
}