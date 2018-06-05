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

package org.ehcache.impl.internal.store.basic;

import org.ehcache.core.spi.store.Store;
import org.terracotta.statistics.MappedOperationStatistic;
import org.terracotta.statistics.StatisticType;
import org.terracotta.statistics.StatisticsManager;
import org.terracotta.statistics.observer.OperationObserver;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.terracotta.statistics.StatisticBuilder.operation;

/**
 * Base class to most stores. It provides functionality common to stores in general. A given store implementation is not required to extend
 * it but the implementor might find it easier to do so.
 */
public abstract class BaseStore<K, V> implements Store<K, V> {

  protected <T extends Enum<T>> OperationObserver<T> createObserver(String name, Class<T> outcome) {
    return operation(outcome).named(name).of(this).tag(getStatisticsTag()).build();
  }

  protected <T extends Serializable> void registerStatistics(String name, Set<String> tags, StatisticType type, Supplier<T> valueSupplier) {
    StatisticsManager.createPassThroughStatistic(this, name, tags, type, valueSupplier);
  }

  protected abstract String getStatisticsTag();

  protected static abstract class BaseStoreProvider implements Store.Provider {

    protected  <K, V, S extends Enum<S>, T extends Enum<T>> MappedOperationStatistic<S, T> createTranslatedStatistics(BaseStore<K, V> store, String statisticName, int tierHeight, Map<T, Set<S>> translation, String targetName) {
      MappedOperationStatistic<S, T> stat = new MappedOperationStatistic<>(store, translation, statisticName, tierHeight, targetName, store.getStatisticsTag());
      StatisticsManager.associate(stat).withParent(store);
      return stat;
    }
  }
}