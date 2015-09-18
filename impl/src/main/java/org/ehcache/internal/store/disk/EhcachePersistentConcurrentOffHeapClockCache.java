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

package org.ehcache.internal.store.disk;

import org.ehcache.function.BiFunction;
import org.ehcache.function.Function;
import org.ehcache.internal.store.disk.factories.EhcachePersistentSegmentFactory;
import org.terracotta.offheapstore.disk.persistent.AbstractPersistentConcurrentOffHeapCache;

import java.io.IOException;
import java.io.ObjectInput;
import java.util.concurrent.atomic.AtomicLong;
import org.ehcache.internal.store.offheap.EhcacheOffHeapBackingMap;

/**
 *
 * @author Chris Dennis
 */
public class EhcachePersistentConcurrentOffHeapClockCache<K, V> extends AbstractPersistentConcurrentOffHeapCache<K, V> implements EhcacheOffHeapBackingMap<K, V> {

  private final AtomicLong[] counters;

  public EhcachePersistentConcurrentOffHeapClockCache(ObjectInput input, EhcachePersistentSegmentFactory<K, V> segmentFactory) throws IOException {
    this(segmentFactory, readSegmentCount(input));
  }
  
  public EhcachePersistentConcurrentOffHeapClockCache(EhcachePersistentSegmentFactory<K, V> segmentFactory, int concurrency) {
    super(segmentFactory, concurrency);
    counters = new AtomicLong[segments.length];
    for(int i = 0; i < segments.length; i++) {
      counters[i] = new AtomicLong();
    }
  }

  @Override
  public V compute(K key, BiFunction<K, V, V> mappingFunction, boolean pin) {
    EhcachePersistentSegmentFactory.EhcachePersistentSegment<K, V> segment = (EhcachePersistentSegmentFactory.EhcachePersistentSegment) segmentFor(key);
    return segment.compute(key, mappingFunction, pin);
  }

  @Override
  public V computeIfPresent(K key, BiFunction<K, V, V> mappingFunction) {
    EhcachePersistentSegmentFactory.EhcachePersistentSegment<K, V> segment = (EhcachePersistentSegmentFactory.EhcachePersistentSegment) segmentFor(key);
    return segment.computeIfPresent(key, mappingFunction);
  }

  @Override
  public V computeIfPresentAndPin(K key, BiFunction<K, V, V> mappingFunction) {
    EhcachePersistentSegmentFactory.EhcachePersistentSegment<K, V> segment = (EhcachePersistentSegmentFactory.EhcachePersistentSegment) segmentFor(key);
    return segment.computeIfPresentAndPin(key, mappingFunction);
  }

  @Override
  public boolean computeIfPinned(final K key, final BiFunction<K,V,V> remappingFunction, final Function<V,Boolean> pinningFunction) {
    EhcachePersistentSegmentFactory.EhcachePersistentSegment<K, V> segment = (EhcachePersistentSegmentFactory.EhcachePersistentSegment) segmentFor(key);
    return segment.computeIfPinned(key, remappingFunction, pinningFunction);
  }

  @Override
  public long nextIdFor(final K key) {
    return counters[getIndexFor(key.hashCode())].getAndIncrement();
  }
}
