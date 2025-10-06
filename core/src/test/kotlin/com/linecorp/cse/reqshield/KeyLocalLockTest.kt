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
import org.junit.jupiter.api.Assertions.assertFalse
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
        // Given: KeyLocalLock instance with timeout that allows cleanup to run
        // LOCK_MONITOR_INTERVAL_MILLIS is 1000ms, so we need timeout > interval
        val lockTimeout = 1500L
        val keyLock = KeyLocalLock(lockTimeout)
        val key = "testKey"
        val lockType = LockType.CREATE

        // When: Acquire lock and wait for expiration + cleanup interval
        assertTrue(keyLock.tryLock(key, lockType))

        // Then: Cleanup should work efficiently
        // Wait for: lockTimeout + cleanup interval (1000ms) + buffer
        Thread.sleep(lockTimeout + 1500L)

        // Expired locks should be cleaned up, allowing new lock acquisition
        await().atMost(Duration.ofSeconds(3)).untilAsserted {
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
                val instance =
                    when (index) {
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
    fun `should allow different instances to unlock the same key via global lockMap`() {
        // Given
        val instance1 = KeyLocalLock(lockTimeoutMillis)
        val instance2 = KeyLocalLock(lockTimeoutMillis)
        val key = "unlock-shared-key"
        val lockType = LockType.CREATE

        // When - Instance1 acquires lock, Instance2 can also unlock (global lockMap shared)
        assertTrue(instance1.tryLock(key, lockType))
        // Instance2 can unlock because isHeld state is global
        assertTrue(instance2.unLock(key, lockType), "Global unlock should succeed from any instance")

        // Then - New lock acquisition should succeed
        val newLockResult = instance2.tryLock(key, lockType)
        assertTrue(newLockResult, "Should be able to acquire lock after global unlock")

        // Cleanup
        instance2.unLock(key, lockType)
        instance1.shutdown()
        instance2.shutdown()
    }

    @Test
    fun `should not over-release semaphore on multiple unlock calls`() {
        // Given
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "over-release-test"
        val lockType = LockType.CREATE

        // When - Acquire lock
        assertTrue(keyLock.tryLock(key, lockType))

        // Then - First unlock should succeed
        assertTrue(keyLock.unLock(key, lockType), "First unlock should succeed")

        // Second unlock should return false (lock not held)
        assertFalse(keyLock.unLock(key, lockType), "Second unlock should fail (over-release prevention)")

        // Verify semaphore is not over-released: can acquire once, not twice
        assertTrue(keyLock.tryLock(key, lockType), "Should acquire lock after proper unlock")
        assertFalse(keyLock.tryLock(key, lockType), "Should not acquire lock twice (semaphore intact)")

        // Cleanup
        keyLock.unLock(key, lockType)
        keyLock.shutdown()
    }

    @Test
    fun `should prevent concurrent lock acquisition after over-release attempt`() {
        // Given
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "concurrent-over-release-test"
        val lockType = LockType.CREATE
        val executor = Executors.newFixedThreadPool(10)
        val successfulAcquisitions = AtomicInteger(0)
        val latch = CountDownLatch(10)

        // Simulate over-release attempt
        assertTrue(keyLock.tryLock(key, lockType))
        keyLock.unLock(key, lockType)
        // Multiple unlock attempts should all return false (not over-release)
        repeat(5) { assertFalse(keyLock.unLock(key, lockType)) }

        // When - Try to acquire lock concurrently
        repeat(10) {
            executor.submit {
                if (keyLock.tryLock(key, lockType)) {
                    successfulAcquisitions.incrementAndGet()
                }
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        // Then - Only ONE thread should succeed (semaphore not corrupted by over-release)
        assertEquals(1, successfulAcquisitions.get(), "Only one thread should acquire the lock")

        // Cleanup
        keyLock.unLock(key, lockType)
        keyLock.shutdown()
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
        assertTrue(
            operations.get() >= 10,
            "At least one operation per key should succeed (minimum 10)",
        )
        assertTrue(
            operations.get() <= 50,
            "No more operations than total attempts should succeed (maximum 50)",
        )

        println("Successful operations: ${operations.get()}/50 total attempts")

        // Cleanup
        instances.forEach { it.shutdown() }
    }

    @Test
    fun `should not remove lock that was just acquired during cleanup window`() {
        // This test verifies that compute() based cleanup and tryLock are mutually exclusive.
        // With compute(), cleanup and acquisition cannot race on the same key because
        // compute() provides per-key atomic execution.

        // Given: Lock with timeout matching cleanup interval to maximize cleanup opportunities
        val lockTimeout = 500L
        val keyLock = KeyLocalLock(lockTimeout)
        val key = "race-condition-test"
        val lockType = LockType.CREATE
        val errors = AtomicInteger(0)
        val successfulCycles = AtomicInteger(0)

        // When: Sequentially acquire, let expire, release, and reacquire
        // This validates that compute() atomicity prevents race conditions
        repeat(10) {
            // Acquire lock
            assertTrue(keyLock.tryLock(key, lockType), "Should acquire lock")

            // Hold until expiration
            Thread.sleep(lockTimeout + 200)

            // Release
            keyLock.unLock(key, lockType)

            // Immediately reacquire - compute() ensures this doesn't race with cleanup
            val reacquired = keyLock.tryLock(key, lockType)
            if (reacquired) {
                // Verify lock exclusivity - second acquire must fail
                if (keyLock.tryLock(key, lockType)) {
                    // This indicates lock was incorrectly removed during acquisition
                    errors.incrementAndGet()
                    keyLock.unLock(key, lockType)
                }
                successfulCycles.incrementAndGet()
                keyLock.unLock(key, lockType)
            }
        }

        // Then: No errors should occur due to compute() atomicity
        assertEquals(0, errors.get(), "No race condition errors should occur")
        assertTrue(successfulCycles.get() >= 5, "Most reacquisitions should succeed")

        keyLock.shutdown()
    }

    @Test
    fun `should verify compute atomicity prevents TOCTOU race condition during concurrent cleanup and acquisition`() {
        // Given: Lock with timeout to trigger cleanup
        // Using compute() for both cleanup and tryLock ensures mutual exclusion per key.
        val lockTimeout = 500L
        val keyLock = KeyLocalLock(lockTimeout)
        val lockType = LockType.CREATE
        val executor = Executors.newFixedThreadPool(5)
        val successfulCycles = AtomicInteger(0)
        val lockRemovedWhileHeld = AtomicInteger(0)
        val iterations = 10
        val latch = CountDownLatch(iterations)

        // When: Concurrently acquire, let expire, release, and re-acquire on different keys
        // compute() guarantees each operation is atomic per key
        repeat(iterations) { i ->
            val key = "toctou-key-$i"
            executor.submit {
                try {
                    // Acquire lock
                    if (keyLock.tryLock(key, lockType)) {
                        // Hold past expiration to trigger cleanup consideration
                        Thread.sleep(lockTimeout + 200)

                        // Release and immediately re-acquire
                        keyLock.unLock(key, lockType)

                        // With compute(), this operation is atomic with respect to cleanup
                        val reacquired = keyLock.tryLock(key, lockType)
                        if (reacquired) {
                            // Verify lock exclusivity
                            if (keyLock.tryLock(key, lockType)) {
                                // This should never happen - compute() ensures atomicity
                                lockRemovedWhileHeld.incrementAndGet()
                                keyLock.unLock(key, lockType)
                            }
                            successfulCycles.incrementAndGet()
                            keyLock.unLock(key, lockType)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Then: No lock corruption due to compute() atomicity
        println("Successful cycles: ${successfulCycles.get()}, Lock removed while held: ${lockRemovedWhileHeld.get()}")
        assertEquals(0, lockRemovedWhileHeld.get(), "No lock should be removed while still held")

        keyLock.shutdown()
    }

    @Test
    fun `should cleanup expired lock even when unlock is never called`() {
        // This test verifies that cleanup properly handles the scenario where unlock() is missed
        // (e.g., due to exception). Previously, isHeld=true locks were never cleaned up,
        // causing memory leaks.

        // Given: Short-lived lock
        val lockTimeout = 500L
        val keyLock = KeyLocalLock(lockTimeout)
        val key = "missed-unlock-test"
        val lockType = LockType.CREATE

        // When: Acquire lock but never unlock (simulating exception scenario)
        assertTrue(keyLock.tryLock(key, lockType), "Should acquire lock")
        // DO NOT call unlock - simulating exception scenario

        // Wait for expiration + cleanup interval + buffer
        // Cleanup runs every 1000ms (LOCK_MONITOR_INTERVAL_MILLIS)
        Thread.sleep(lockTimeout + 1500L)

        // Then: Cleanup should have force-released and removed the expired lock
        // A new lock acquisition should succeed
        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            assertTrue(
                keyLock.tryLock(key, lockType),
                "Should acquire lock after cleanup removed expired held lock",
            )
        }

        // Verify lock is working normally
        assertFalse(keyLock.tryLock(key, lockType), "Second acquire should fail (lock is held)")
        assertTrue(keyLock.unLock(key, lockType), "Unlock should succeed")

        keyLock.shutdown()
    }

    @Test
    fun `should cleanup multiple expired held locks without memory leak`() {
        // This test verifies that cleanup prevents memory leaks when many locks expire
        // without being unlocked.

        // Given: Short-lived locks
        val lockTimeout = 300L
        val keyLock = KeyLocalLock(lockTimeout)
        val lockType = LockType.CREATE
        val keyCount = 20

        // When: Acquire many locks but never unlock them
        repeat(keyCount) { i ->
            assertTrue(keyLock.tryLock("leak-test-$i", lockType), "Should acquire lock $i")
        }

        // Wait for all locks to expire and be cleaned up
        // Cleanup interval is 1000ms, so we need to wait for expiration + cleanup cycle
        Thread.sleep(lockTimeout + 1500L)

        // Then: All expired locks should be cleaned up, allowing reacquisition
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            repeat(keyCount) { i ->
                assertTrue(
                    keyLock.tryLock("leak-test-$i", lockType),
                    "Should acquire lock $i after cleanup",
                )
                keyLock.unLock("leak-test-$i", lockType)
            }
        }

        keyLock.shutdown()
    }

    private fun doWork() = Thread.sleep(1000)
}
