/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Cache<K, V> {

  @Nullable
  V getIfPresent(Object key);

  V get(K key, Function<? super K, ? extends V> mappingFunction);

  Map<K, V> getAllPresent(Iterable<?> keys);

  void put(K key, V value);

  void putAll(Map<? extends K,? extends V> map);

  void invalidate(Object key);

  void invalidateAll(Iterable<?> keys);

  void invalidateAll();

  long size();

  CacheStats stats();

  ConcurrentMap<K, V> asMap();

  void cleanUp();
}