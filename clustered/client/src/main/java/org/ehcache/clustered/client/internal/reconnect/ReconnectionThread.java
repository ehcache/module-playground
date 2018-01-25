/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehcache.clustered.client.internal.reconnect;

import org.ehcache.clustered.client.internal.store.ClusterTierClientEntity;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReconnectionThread extends Thread {

  private final AtomicBoolean complete = new AtomicBoolean();
  private final ReconnectHandle reconnectHandle;
  private final Collection<ClusterTierClientEntity> entities;

  public ReconnectionThread(ReconnectHandle reconnectHandle, Collection<ClusterTierClientEntity> entities) {
    this.reconnectHandle = reconnectHandle;
    this.entities = entities;
  }

  @Override
  public void run() {
    boolean interrupted = false;
    while (true) {
      if (entities.stream().noneMatch(ClusterTierClientEntity::isConnected)) {
        reconnectHandle.onReconnect();
        complete.set(true);
        break;
      } else {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public boolean isComplete() {
    return complete.get();
  }

}
