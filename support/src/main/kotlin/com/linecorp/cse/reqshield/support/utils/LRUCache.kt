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

/**
 * High-performance LRU Cache with O(1) operations.
 * Based on LinkedHashMap with access-order for optimal LRU behavior.
 *
 * This implementation provides better performance than the previous AtomicLong-based version
 * by leveraging LinkedHashMap's built-in LRU behavior.
 */
class LRUCache<K, V>(private val maxSize: Int) {
    private val cache =
        object : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxSize
            }
        }

    @Synchronized
    fun computeIfAbsent(
        key: K,
        mappingFunction: (K) -> V,
    ): V {
        return cache.computeIfAbsent(key, mappingFunction)
    }

    @Synchronized
    operator fun get(key: K): V? {
        return cache[key]
    }

    @Synchronized
    fun put(
        key: K,
        value: V,
    ): V? {
        return cache.put(key, value)
    }

    @Synchronized
    fun remove(key: K): V? {
        return cache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun keys(): Set<K> = LinkedHashSet(cache.keys)

    @Synchronized
    fun size(): Int = cache.size

    val size: Int get() = cache.size
}
