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
package org.ehcache.transactions.configuration;

import org.ehcache.CacheManager;
import org.ehcache.CacheManagerBuilder;
import org.ehcache.config.CacheManagerConfiguration;
import org.ehcache.spi.service.ServiceCreationConfiguration;

/**
 * @author Ludovic Orban
 */
public class TxCacheManagerConfiguration<T extends CacheManager> implements ServiceCreationConfiguration<TxService>, CacheManagerConfiguration<T> {
  @Override
  public CacheManagerBuilder<T> builder(CacheManagerBuilder<? extends CacheManager> other) {
    return (CacheManagerBuilder<T>) other.using(this);
  }

  @Override
  public Class<TxService> getServiceType() {
    return TxService.class;
  }
}
