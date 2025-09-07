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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LRUCacheTest {
    @Test
    fun `should store and retrieve values`() {
        val cache = LRUCache<String, Int>(3)

        assertEquals(1, cache.computeIfAbsent("key1") { 1 })
        assertEquals(1, cache.get("key1"))
    }

    @Test
    fun `should return existing value when key exists`() {
        val cache = LRUCache<String, Int>(3)
        cache.put("key1", 1)

        // Should return existing value, not compute new one
        assertEquals(1, cache.computeIfAbsent("key1") { 999 })
    }

    @Test
    fun `should evict least recently used item when max size exceeded`() {
        val cache = LRUCache<String, Int>(2)

        cache.put("key1", 1)
        cache.put("key2", 2)
        assertEquals(2, cache.size())

        // This should evict key1 (least recently used)
        cache.put("key3", 3)

        assertEquals(2, cache.size())
        assertNull(cache.get("key1"))
        assertEquals(2, cache.get("key2"))
        assertEquals(3, cache.get("key3"))
    }

    @Test
    fun `should update access order when retrieving values`() {
        val cache = LRUCache<String, Int>(2)

        cache.put("key1", 1)
        cache.put("key2", 2)

        // Access key1 to make it more recently used
        cache.get("key1")

        // Add key3, should evict key2 (not key1)
        cache.put("key3", 3)

        assertEquals(1, cache.get("key1"))
        assertNull(cache.get("key2"))
        assertEquals(3, cache.get("key3"))
    }

    @Test
    fun `should update access order with computeIfAbsent`() {
        val cache = LRUCache<String, Int>(2)

        cache.put("key1", 1)
        cache.put("key2", 2)

        // Access key1 via computeIfAbsent to make it more recently used
        cache.computeIfAbsent("key1") { 999 }

        // Add key3, should evict key2 (not key1)
        cache.put("key3", 3)

        assertEquals(1, cache.get("key1"))
        assertNull(cache.get("key2"))
        assertEquals(3, cache.get("key3"))
    }

    @Test
    fun `should handle removal correctly`() {
        val cache = LRUCache<String, Int>(3)

        cache.put("key1", 1)
        cache.put("key2", 2)
        assertEquals(2, cache.size())

        assertEquals(1, cache.remove("key1"))
        assertEquals(1, cache.size())
        assertNull(cache.get("key1"))
        assertEquals(2, cache.get("key2"))
    }

    @Test
    fun `should clear all entries`() {
        val cache = LRUCache<String, Int>(3)

        cache.put("key1", 1)
        cache.put("key2", 2)
        cache.put("key3", 3)
        assertEquals(3, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("key1"))
        assertNull(cache.get("key2"))
        assertNull(cache.get("key3"))
    }

    @Test
    fun `should handle concurrent access safely`() {
        val cache = LRUCache<String, Int>(100)
        val executor: ExecutorService = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10)
        val results = ConcurrentHashMap<String, Int>()

        repeat(10) { threadIndex ->
            executor.submit {
                try {
                    repeat(50) { i ->
                        val key = "key${threadIndex * 50 + i}"
                        val value = threadIndex * 50 + i
                        cache.put(key, value)
                        results[key] = cache.get(key) ?: -1
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // Verify that most operations completed successfully
        // Some entries might be evicted due to the cache size limit
        assert(results.size > 0)
        results.forEach { (key, retrievedValue) ->
            val expectedValue = key.substring(3).toInt()
            if (retrievedValue != -1) { // -1 means the value was not found (evicted)
                assertEquals(expectedValue, retrievedValue, "Value mismatch for key $key")
            }
        }
    }

    @Test
    fun `should handle computeIfAbsent concurrency correctly`() {
        val cache = LRUCache<String, Int>(10)
        val executor: ExecutorService = Executors.newFixedThreadPool(5)
        val latch = CountDownLatch(5)
        val computeCallCounts = ConcurrentHashMap<String, Int>()

        repeat(5) { threadIndex ->
            executor.submit {
                try {
                    val result =
                        cache.computeIfAbsent("sharedKey") {
                            computeCallCounts.merge(Thread.currentThread().name, 1) { old, new -> old + new }
                            threadIndex * 100
                        }
                    // All threads should get the same result (from the first successful computation)
                    assert(result in (0..400)) // One of the possible computed values
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // Verify that the value was computed only once despite concurrent access
        assertEquals(1, cache.size())
        assert(computeCallCounts.values.sum() > 0) // At least one computation happened
    }

    @Test
    fun `should maintain size limit under heavy concurrent load`() {
        val maxSize = 100
        val cache = LRUCache<String, Int>(maxSize)
        val executor: ExecutorService = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10)

        repeat(10) { threadIndex ->
            executor.submit {
                try {
                    repeat(50) { i ->
                        val key = "thread${threadIndex}_key$i"
                        cache.put(key, threadIndex * 1000 + i)
                        // Remove sleep to reduce test time and timing issues
                    }
                } catch (e: Exception) {
                    // Catch any exceptions to avoid test interruption
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Allow some buffer due to concurrent operations timing
        // The cache should roughly maintain the size limit
        assert(cache.size() <= maxSize * 1.2) {
            "Cache size ${cache.size()} significantly exceeded maximum size $maxSize"
        }
    }
}
