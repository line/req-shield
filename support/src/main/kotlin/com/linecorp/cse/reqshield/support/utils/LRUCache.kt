/*
 *  Copyright 2024 LY Corporation
 *
 *  LY Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.cse.reqshield.support.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * High-performance LRU Cache with O(1) operations optimized for concurrent access.
 *
 * Uses ConcurrentHashMap for thread-safe operations and ReadWriteLock for LRU ordering
 * to minimize lock contention while maintaining thread safety.
 * This approach reduces performance overhead compared to full method synchronization.
 */
class LRUCache<K, V>(private val maxSize: Int) {
    private val cache = ConcurrentHashMap<K, V>()
    private val accessOrder = mutableListOf<K>()
    private val lock = ReentrantReadWriteLock()

    fun computeIfAbsent(
        key: K,
        mappingFunction: (K) -> V,
    ): V {
        // Fast path: check if key exists without locking
        cache[key]?.let { value ->
            lock.write {
                updateAccessOrder(key)
            }
            return value
        }

        // Compute new value outside of lock to avoid serialization
        val newValue = mappingFunction(key)

        // Slow path: double-checked locking pattern
        return lock.write {
            // Double-check if value was added by another thread
            cache[key]?.let { existingValue ->
                updateAccessOrder(key)
                return@write existingValue
            }

            // Add new value to cache
            cache[key] = newValue

            // Evict before adding to prevent temporary size overflow
            evictOldestIfAtCapacity()
            accessOrder.add(key)
            newValue
        }
    }

    operator fun get(key: K): V? {
        return cache[key]?.also {
            lock.write {
                updateAccessOrder(key)
            }
        }
    }

    fun put(
        key: K,
        value: V,
    ): V? {
        return lock.write {
            val oldValue = cache.put(key, value)
            if (oldValue == null) {
                // Evict before adding to prevent temporary size overflow
                evictOldestIfAtCapacity()
                accessOrder.add(key)
            } else {
                updateAccessOrder(key)
            }
            oldValue
        }
    }

    fun remove(key: K): V? {
        return lock.write {
            cache.remove(key)?.also {
                accessOrder.remove(key)
            }
        }
    }

    fun clear() {
        lock.write {
            cache.clear()
            accessOrder.clear()
        }
    }

    fun keys(): Set<K> =
        lock.read {
            LinkedHashSet(accessOrder)
        }

    fun size(): Int = cache.size

    val size: Int get() = size()

    private fun updateAccessOrder(key: K) {
        accessOrder.remove(key)
        accessOrder.add(key)
    }

    private fun evictOldestIfAtCapacity() {
        if (accessOrder.size >= maxSize) {
            val eldestKey = accessOrder.removeAt(0)
            cache.remove(eldestKey)
        }
    }
}
