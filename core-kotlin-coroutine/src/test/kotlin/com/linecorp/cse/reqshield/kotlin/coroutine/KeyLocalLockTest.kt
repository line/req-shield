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

package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.BaseKeyLockTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class KeyLocalLockTest : BaseKeyLockTest {
    @AfterEach
    fun cleanup() {
        // Ensure monitor is stopped after each test to prevent memory leaks
        KeyLocalLock.stopMonitoring()
    }

    @Test
    fun `should share global lockMap across multiple instances`() =
        runBlocking {
            val instance1 = KeyLocalLock(lockTimeoutMillis)
            val instance2 = KeyLocalLock(lockTimeoutMillis)
            val key = "shared-key"
            val lockType = LockType.CREATE

            assertTrue(instance1.tryLock(key, lockType))
            assertTrue(!instance2.tryLock(key, lockType))

            instance1.unLock(key, lockType)
        }

    @Test
    fun `should maintain request collapsing across multiple instances`() =
        runBlocking {
            val instance1 = KeyLocalLock(lockTimeoutMillis)
            val instance2 = KeyLocalLock(lockTimeoutMillis)
            val instance3 = KeyLocalLock(lockTimeoutMillis)
            val key = "collapsing-key"
            val lockType = LockType.CREATE

            val acquired = listOf(instance1, instance2, instance3).map { it.tryLock(key, lockType) }.count { it }
            assertEquals(1, acquired)

            // cleanup whoever acquired
            listOf(instance1, instance2, instance3).forEach { it.unLock(key, lockType) }
        }

    @Test
    override fun testConcurrencyWithOneKey() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "myKey"
            val lockType = LockType.CREATE
            val lockAcquiredCount = AtomicInteger(0)
            val tasksCompletedCount = AtomicInteger(0)

            val jobs =
                List(20) {
                    launch {
                        withContext(Dispatchers.IO) {
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
                }
            jobs.joinAll()

            assertEquals(20, tasksCompletedCount.get())
            assertEquals(1, lockAcquiredCount.get())

            delay(100)

            assertTrue(keyLock.tryLock(key, lockType))
        }

    @Test
    override fun testConcurrencyWithTwoKey() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val lockType = LockType.CREATE
            val lockAcquiredCount = AtomicInteger(0)
            val tasksCompletedCount = AtomicInteger(0)

            val jobs =
                List(20) { i ->
                    val key = if (i % 2 == 0) "myKey1" else "myKey2"
                    launch {
                        withContext(Dispatchers.IO) {
                            if (keyLock.tryLock(key, lockType)) {
                                try {
                                    lockAcquiredCount.incrementAndGet()
                                    doWork()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                } finally {
                                    keyLock.unLock(key, lockType)
                                }
                            }
                            tasksCompletedCount.incrementAndGet()
                        }
                    }
                }
            jobs.joinAll()

            assertTrue(lockAcquiredCount.get() <= 4)

            delay(100)

            assertTrue(keyLock.tryLock("myKey1", lockType))
            assertTrue(keyLock.tryLock("myKey2", lockType))
        }

    @Test
    override fun testLockExpiration() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "myKey"
            val lockType = LockType.CREATE

            assertTrue(keyLock.tryLock(key, lockType))

            // Wait for lock timeout + cleanup interval + buffer
            // lockTimeoutMillis = 3000ms, cleanup interval = 1000ms
            delay(lockTimeoutMillis + 1000L + 500L) // 4.5 seconds total

            val result =
                withContext(Dispatchers.IO) {
                    keyLock.tryLock(key, lockType)
                }

            assertTrue(result)
            assertTrue(keyLock.unLock(key, lockType))
        }

    @Test
    fun `should not over-release semaphore on multiple unlock calls`() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "over-release-test"
            val lockType = LockType.CREATE

            // Acquire lock
            assertTrue(keyLock.tryLock(key, lockType))

            // First unlock should succeed
            assertTrue(keyLock.unLock(key, lockType), "First unlock should succeed")

            // Second unlock should return false (over-release prevention)
            assertFalse(keyLock.unLock(key, lockType), "Second unlock should fail (over-release prevention)")

            // Verify semaphore is not over-released: can acquire once, not twice
            assertTrue(keyLock.tryLock(key, lockType), "Should acquire lock after proper unlock")
            assertFalse(keyLock.tryLock(key, lockType), "Should not acquire lock twice (semaphore intact)")

            // Cleanup
            keyLock.unLock(key, lockType)
            keyLock.cancel()
        }

    @Test
    fun `should prevent concurrent lock acquisition after over-release attempt`() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "concurrent-over-release-test"
            val lockType = LockType.CREATE
            val successfulAcquisitions = AtomicInteger(0)

            // Simulate over-release attempt
            assertTrue(keyLock.tryLock(key, lockType))
            keyLock.unLock(key, lockType)
            // Multiple unlock attempts should all return false (not over-release)
            repeat(5) { assertFalse(keyLock.unLock(key, lockType)) }

            // Try to acquire lock concurrently - only ONE should succeed
            val attempts =
                (1..10).map {
                    async(Dispatchers.IO) {
                        if (keyLock.tryLock(key, lockType)) {
                            successfulAcquisitions.incrementAndGet()
                        }
                    }
                }

            attempts.awaitAll()

            // Only one should have acquired the lock
            assertEquals(1, successfulAcquisitions.get(), "Only one should acquire the lock")

            // Cleanup
            keyLock.unLock(key, lockType)
            keyLock.cancel()
        }

    @Test
    fun `should not over-release when tryLock and unLock race on expired lock`() =
        runBlocking {
            // Use a very short lock timeout to trigger expiration quickly
            val shortLockTimeout = 50L
            val keyLock = KeyLocalLock(shortLockTimeout)
            val key = "race-condition-test"
            val lockType = LockType.CREATE

            repeat(100) { iteration ->
                // Step 1: Acquire lock
                assertTrue(keyLock.tryLock(key, lockType), "Iteration $iteration: Initial lock should succeed")

                // Step 2: Wait for lock to expire (but not be cleaned up by monitor)
                delay(shortLockTimeout + 10L)

                // Step 3: Simulate race condition - tryLock and unLock concurrently
                // tryLock will detect expiration and try to force-release
                // unLock will also try to release
                // Without CAS fix, both would call semaphore.release() causing over-release
                val tryLockResult =
                    async(Dispatchers.IO) {
                        keyLock.tryLock(key, lockType)
                    }
                val unLockResult =
                    async(Dispatchers.IO) {
                        keyLock.unLock(key, lockType)
                    }

                tryLockResult.await()
                unLockResult.await()

                // Step 4: Verify no over-release by checking lock behavior
                // If over-release occurred, permits > 1, allowing multiple acquisitions
                val acquisitions = AtomicInteger(0)
                val attempts =
                    (1..5).map {
                        async(Dispatchers.IO) {
                            if (keyLock.tryLock(key, lockType)) {
                                acquisitions.incrementAndGet()
                            }
                        }
                    }
                attempts.awaitAll()

                // At most 1 should succeed (0 if tryLock already holds it, 1 if it released)
                assertTrue(
                    acquisitions.get() <= 1,
                    "Iteration $iteration: Over-release detected! " +
                        "Expected at most 1 acquisition, got ${acquisitions.get()}",
                )

                // Cleanup for next iteration
                repeat(3) { keyLock.unLock(key, lockType) }
            }

            keyLock.cancel()
        }

    @Test
    fun `should handle high contention tryLock and unLock without over-release`() =
        runBlocking {
            val shortLockTimeout = 30L
            val keyLock = KeyLocalLock(shortLockTimeout)
            val key = "high-contention-test"
            val lockType = LockType.CREATE
            val overReleaseDetected = AtomicInteger(0)

            repeat(50) { iteration ->
                // Acquire lock and let it expire
                assertTrue(keyLock.tryLock(key, lockType))
                delay(shortLockTimeout + 5L)

                // High contention: many concurrent tryLock and unLock calls
                val jobs =
                    (1..20).map { i ->
                        if (i % 2 == 0) {
                            async(Dispatchers.IO) { keyLock.tryLock(key, lockType) }
                        } else {
                            async(Dispatchers.IO) { keyLock.unLock(key, lockType) }
                        }
                    }
                jobs.awaitAll()

                // Verify: try to acquire lock multiple times concurrently
                val acquisitions = AtomicInteger(0)
                val verifyJobs =
                    (1..10).map {
                        async(Dispatchers.IO) {
                            if (keyLock.tryLock(key, lockType)) {
                                acquisitions.incrementAndGet()
                            }
                        }
                    }
                verifyJobs.awaitAll()

                if (acquisitions.get() > 1) {
                    overReleaseDetected.incrementAndGet()
                }

                // Cleanup
                repeat(15) { keyLock.unLock(key, lockType) }
            }

            assertEquals(
                0,
                overReleaseDetected.get(),
                "Over-release detected in ${overReleaseDetected.get()} iterations",
            )

            keyLock.cancel()
        }

    private suspend fun doWork() = delay(1000)
}
