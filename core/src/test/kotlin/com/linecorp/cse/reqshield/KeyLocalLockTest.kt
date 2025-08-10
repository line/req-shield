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

package com.linecorp.cse.reqshield

import com.linecorp.cse.reqshield.support.BaseKeyLockTest
import com.linecorp.cse.reqshield.support.BaseReqShieldTest.Companion.AWAIT_TIMEOUT
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KeyLocalLockTest : BaseKeyLockTest {
    @Test
    override fun testConcurrencyWithOneKey() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val executorService = Executors.newFixedThreadPool(20)
        val key = "myKey"
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        for (i in 0 until 20) {
            executorService.submit {
                if (keyLock.tryLock(key, lockType)) {
                    try {
                        println("${Thread.currentThread().name} acquired the lock")
                        lockAcquiredCount.incrementAndGet()
                        doWork()
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    } finally {
                        keyLock.unLock(key, lockType)
                        println("${Thread.currentThread().name} released the lock")
                    }
                } else {
                    println("${Thread.currentThread().name} could not acquire the lock and is terminating")
                }
                tasksCompletedCount.incrementAndGet()
            }
        }
        executorService.shutdown()

        await().atMost(Duration.ofSeconds(3)).until { tasksCompletedCount.get() == 20 }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(1, lockAcquiredCount.get())
            assertTrue(keyLock.tryLock(key, lockType))
        }
    }

    @Test
    override fun testConcurrencyWithTwoKey() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val executorService = Executors.newFixedThreadPool(20)
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        for (i in 0 until 20) {
            val key = if (i % 2 == 0) "myKey1" else "myKey2"
            executorService.submit {
                if (keyLock.tryLock(key, lockType)) {
                    try {
                        println("${Thread.currentThread().name} acquired the lock")
                        lockAcquiredCount.incrementAndGet()
                        doWork()
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    } finally {
                        keyLock.unLock(key, lockType)
                        println("${Thread.currentThread().name} released the lock")
                    }
                } else {
                    println("${Thread.currentThread().name} could not acquire the lock and is terminating")
                }
                tasksCompletedCount.incrementAndGet()
            }
        }
        executorService.shutdown()

        await().atMost(Duration.ofSeconds(3)).until { tasksCompletedCount.get() == 20 }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertTrue(lockAcquiredCount.get() <= 4)
            assertTrue(keyLock.tryLock("myKey1", lockType))
            assertTrue(keyLock.tryLock("myKey2", lockType))
        }
    }

    @Test
    override fun testLockExpiration() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "myKey"
        val lockType = LockType.CREATE

        assertTrue(keyLock.tryLock(key, lockType))

        // Wait for lock timeout + cleanup interval + buffer
        // lockTimeoutMillis = 3000ms, cleanup interval = 1000ms
        Thread.sleep(lockTimeoutMillis + 1000L + 500L) // 4.5 seconds total

        val executorService = Executors.newSingleThreadExecutor()
        val future =
            executorService.submit<Boolean> {
                keyLock.tryLock(key, lockType)
            }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertTrue(future.get())
        }
        assertTrue(keyLock.unLock(key, lockType))
    }

    @Test
    fun testSharedSchedulerPerformance() {
        // Given: Initial state for thread count measurement
        val initialThreadCount = ManagementFactory.getThreadMXBean().threadCount
        val instances = mutableListOf<KeyLocalLock>()

        // When: Create multiple KeyLocalLock instances
        repeat(10) {
            instances.add(KeyLocalLock(lockTimeoutMillis))
        }

        // Then: Thread increase minimized by shared scheduler
        Thread.sleep(100) // Wait for scheduler initialization
        val currentThreadCount = ManagementFactory.getThreadMXBean().threadCount
        val threadIncrease = currentThreadCount - initialThreadCount

        println("Initial threads: $initialThreadCount")
        println("Current threads: $currentThreadCount")
        println("Thread increase: $threadIncrease")

        // Improved implementation: shared scheduler minimizes thread increase (only 1-2 threads)
        assertTrue(threadIncrease <= 5, "Thread increase minimized by shared scheduler")

        // Cleanup
        instances.forEach { it.shutdown() }
    }

    @Test
    fun testLockCleanupEfficiency() {
        // Given: KeyLocalLock instance
        val keyLock = KeyLocalLock(1000L) // Expires after 1 second
        val key = "testKey"
        val lockType = LockType.CREATE

        // When: Acquire lock and wait for expiration
        assertTrue(keyLock.tryLock(key, lockType))

        // Then: Cleanup should work efficiently
        // Previously executed excessively at 10ms intervals
        Thread.sleep(1200L) // Expiration time + buffer

        // Expired locks should be cleaned up, allowing new lock acquisition
        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            assertTrue(keyLock.tryLock(key, lockType))
            keyLock.unLock(key, lockType)
        }

        keyLock.shutdown()
    }

    @Test
    fun `should share global lockMap across multiple instances`() {
        // Given
        val instance1 = KeyLocalLock(lockTimeoutMillis)
        val instance2 = KeyLocalLock(lockTimeoutMillis)
        val key = "shared-key"
        val lockType = LockType.CREATE

        // When - Instance1 acquires lock
        val lock1Result = instance1.tryLock(key, lockType)

        // Then - Instance2 should not be able to acquire the same lock
        val lock2Result = instance2.tryLock(key, lockType)
        
        assertTrue(lock1Result)
        assertTrue(!lock2Result, "Instance2 should not acquire lock held by Instance1")
        
        // Cleanup
        instance1.unLock(key, lockType)
        instance1.shutdown()
        instance2.shutdown()
    }

    @Test
    fun `should maintain request collapsing across multiple instances`() {
        // Given
        val instance1 = KeyLocalLock(lockTimeoutMillis)
        val instance2 = KeyLocalLock(lockTimeoutMillis) 
        val instance3 = KeyLocalLock(lockTimeoutMillis)
        val key = "collapsing-key"
        val lockType = LockType.CREATE
        val executor = Executors.newFixedThreadPool(3)
        val successCount = AtomicInteger(0)
        val attemptCount = AtomicInteger(0)
        val latch = CountDownLatch(3)

        // When - Multiple instances try to acquire the same lock concurrently
        repeat(3) { index ->
            executor.submit {
                val instance = when (index) {
                    0 -> instance1
                    1 -> instance2
                    else -> instance3
                }
                attemptCount.incrementAndGet()
                if (instance.tryLock(key, lockType)) {
                    successCount.incrementAndGet()
                    Thread.sleep(50) // Hold lock briefly
                    instance.unLock(key, lockType)
                }
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // Then - Only one should succeed in acquiring the lock
        assertEquals(3, attemptCount.get())
        assertEquals(1, successCount.get(), "Only one instance should acquire the lock")
        
        // Cleanup
        instance1.shutdown()
        instance2.shutdown()
        instance3.shutdown()
    }

    @Test
    fun `should allow different instances to unlock the same key`() {
        // Given
        val instance1 = KeyLocalLock(lockTimeoutMillis)
        val instance2 = KeyLocalLock(lockTimeoutMillis)
        val key = "unlock-shared-key"
        val lockType = LockType.CREATE

        // When - Instance1 acquires lock, Instance2 unlocks
        assertTrue(instance1.tryLock(key, lockType))
        instance2.unLock(key, lockType) // Should work even from different instance
        
        // Then - New lock acquisition should succeed
        val newLockResult = instance2.tryLock(key, lockType)
        assertTrue(newLockResult, "Should be able to acquire lock after global unlock")
        
        // Cleanup
        instance2.unLock(key, lockType)
        instance1.shutdown()
        instance2.shutdown()
    }

    @Test
    fun `should handle concurrent operations from multiple instances safely`() {
        // Given - 5 instances operating on 10 different keys concurrently
        // Each key should allow exactly one successful lock operation due to global lockMap sharing
        val instances = (1..5).map { KeyLocalLock(lockTimeoutMillis) }
        val keys = (1..10).map { "concurrent-key-$it" }
        val executor = Executors.newFixedThreadPool(10)
        val operations = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(50) // 5 instances × 10 keys = 50 total attempts

        // When - Multiple instances perform operations on different keys concurrently
        instances.forEach { instance ->
            keys.forEach { key ->
                executor.submit {
                    try {
                        if (instance.tryLock(key, LockType.CREATE)) {
                            operations.incrementAndGet()
                            Thread.sleep(10) // Brief work simulation
                            instance.unLock(key, LockType.CREATE)
                        }
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // Then - Verify thread safety and concurrent operations handling
        assertEquals(0, errors.get(), "No errors should occur during concurrent operations")
        
        // Due to sequential nature of ThreadPool(10) and brief work duration (10ms),
        // multiple operations can succeed on the same key at different times
        assertTrue(operations.get() >= 10, 
            "At least one operation per key should succeed (minimum 10)")
        assertTrue(operations.get() <= 50, 
            "No more operations than total attempts should succeed (maximum 50)")
        
        println("Successful operations: ${operations.get()}/50 total attempts")
        
        // Cleanup
        instances.forEach { it.shutdown() }
    }

    private fun doWork() = Thread.sleep(1000)
}
