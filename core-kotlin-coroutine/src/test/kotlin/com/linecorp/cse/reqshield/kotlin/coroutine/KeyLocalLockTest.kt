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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

            val token = instance1.tryLock(key, lockType)
            assertNotNull(token)
            assertNull(instance2.tryLock(key, lockType))

            // Any instance may release the lock as long as it presents the owning token.
            assertTrue(instance2.unLock(key, lockType, token))
        }

    @Test
    fun `should maintain request collapsing across multiple instances`() =
        runBlocking {
            val instances = List(3) { KeyLocalLock(lockTimeoutMillis) }
            val key = "collapsing-key"
            val lockType = LockType.CREATE

            val tokens = instances.mapNotNull { it.tryLock(key, lockType) }
            assertEquals(1, tokens.size)

            // cleanup whoever acquired
            tokens.forEach { instances.first().unLock(key, lockType, it) }
        }

    @Test
    fun `should reject an unlock presenting a token of another owner`() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "foreign-token-test"
            val lockType = LockType.CREATE

            val token = keyLock.tryLock(key, lockType)
            assertNotNull(token)

            assertFalse(keyLock.unLock(key, lockType, "someone-elses-token"), "A foreign token must not release the lock")
            // The lock is still held, so nobody else can acquire it.
            assertNull(keyLock.tryLock(key, lockType))

            assertTrue(keyLock.unLock(key, lockType, token), "The owning token must release the lock")
            keyLock.cancel()
        }

    @Test
    fun `should not let a stale token release the lock of the next owner`() =
        runBlocking {
            val shortLockTimeout = 50L
            val keyLock = KeyLocalLock(shortLockTimeout)
            val key = "stale-token-test"
            val lockType = LockType.CREATE

            val staleToken = keyLock.tryLock(key, lockType)
            assertNotNull(staleToken)

            // Let the lock time out so that the next caller can force-acquire it.
            delay(shortLockTimeout + 10L)
            val newToken = keyLock.tryLock(key, lockType)
            assertNotNull(newToken)
            assertTrue(staleToken != newToken)

            // The timed-out owner must not be able to release the new owner's lock.
            assertFalse(keyLock.unLock(key, lockType, staleToken))
            assertNull(keyLock.tryLock(key, lockType), "The new owner must still hold the lock")

            assertTrue(keyLock.unLock(key, lockType, newToken))
            keyLock.cancel()
        }

    @Test
    override fun testConcurrencyWithOneKey() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "myKey-concurrency-one"
            val lockType = LockType.CREATE
            val lockAcquiredCount = AtomicInteger(0)
            val tasksCompletedCount = AtomicInteger(0)

            val jobs =
                List(20) {
                    launch {
                        withContext(Dispatchers.IO) {
                            val token = keyLock.tryLock(key, lockType)
                            if (token != null) {
                                try {
                                    println("${Thread.currentThread().name} acquired the lock")
                                    lockAcquiredCount.incrementAndGet()
                                    doWork()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                } finally {
                                    keyLock.unLock(key, lockType, token)
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

            assertTrue(keyLock.tryLock(key, lockType) != null, "The lock must be free again")
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
                            val token = keyLock.tryLock(key, lockType)
                            if (token != null) {
                                try {
                                    lockAcquiredCount.incrementAndGet()
                                    doWork()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                } finally {
                                    keyLock.unLock(key, lockType, token)
                                }
                            }
                            tasksCompletedCount.incrementAndGet()
                        }
                    }
                }
            jobs.joinAll()

            assertTrue(lockAcquiredCount.get() <= 4)

            delay(100)

            assertTrue(keyLock.tryLock("myKey1", lockType) != null)
            assertTrue(keyLock.tryLock("myKey2", lockType) != null)
        }

    @Test
    override fun testLockExpiration() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "myKey-lock-expiration"
            val lockType = LockType.CREATE

            assertNotNull(keyLock.tryLock(key, lockType))

            // Wait for lock timeout + cleanup interval + buffer
            // lockTimeoutMillis = 3000ms, cleanup interval = 1000ms
            delay(lockTimeoutMillis + 1000L + 500L) // 4.5 seconds total

            val token =
                withContext(Dispatchers.IO) {
                    keyLock.tryLock(key, lockType)
                }

            assertNotNull(token)
            assertTrue(keyLock.unLock(key, lockType, token))
        }

    @Test
    fun `should not over-release semaphore on multiple unlock calls`() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "over-release-test"
            val lockType = LockType.CREATE

            // Acquire lock
            val token = keyLock.tryLock(key, lockType)
            assertNotNull(token)

            // First unlock should succeed
            assertTrue(keyLock.unLock(key, lockType, token), "First unlock should succeed")

            // Second unlock should return false (over-release prevention)
            assertFalse(keyLock.unLock(key, lockType, token), "Second unlock should fail (over-release prevention)")

            // Verify semaphore is not over-released: can acquire once, not twice
            val reacquiredToken = keyLock.tryLock(key, lockType)
            assertNotNull(reacquiredToken, "Should acquire lock after proper unlock")
            assertNull(keyLock.tryLock(key, lockType), "Should not acquire lock twice (semaphore intact)")

            // Cleanup
            keyLock.unLock(key, lockType, reacquiredToken)
            keyLock.cancel()
        }

    @Test
    fun `should prevent concurrent lock acquisition after over-release attempt`() =
        runBlocking {
            val keyLock = KeyLocalLock(lockTimeoutMillis)
            val key = "concurrent-over-release-test"
            val lockType = LockType.CREATE
            val acquiredTokens = ConcurrentLinkedQueue<String>()

            // Simulate over-release attempt
            val token = keyLock.tryLock(key, lockType)
            assertNotNull(token)
            assertTrue(keyLock.unLock(key, lockType, token))
            // Multiple unlock attempts should all return false (not over-release)
            repeat(5) { assertFalse(keyLock.unLock(key, lockType, token)) }

            // Try to acquire lock concurrently - only ONE should succeed
            val attempts =
                (1..10).map {
                    async(Dispatchers.IO) {
                        keyLock.tryLock(key, lockType)?.let { acquiredTokens.add(it) }
                    }
                }

            attempts.awaitAll()

            // Only one should have acquired the lock
            assertEquals(1, acquiredTokens.size, "Only one should acquire the lock")

            // Cleanup
            acquiredTokens.forEach { keyLock.unLock(key, lockType, it) }
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
                val token = keyLock.tryLock(key, lockType)
                assertNotNull(token, "Iteration $iteration: Initial lock should succeed")

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
                        keyLock.unLock(key, lockType, token)
                    }

                val raceToken = tryLockResult.await()
                unLockResult.await()

                // Step 4: Verify no over-release by checking lock behavior
                // If over-release occurred, permits > 1, allowing multiple acquisitions
                val acquiredTokens = ConcurrentLinkedQueue<String>()
                val attempts =
                    (1..5).map {
                        async(Dispatchers.IO) {
                            keyLock.tryLock(key, lockType)?.let { acquiredTokens.add(it) }
                        }
                    }
                attempts.awaitAll()

                // At most 1 should succeed (0 if tryLock already holds it, 1 if it released)
                assertTrue(
                    acquiredTokens.size <= 1,
                    "Iteration $iteration: Over-release detected! " +
                        "Expected at most 1 acquisition, got ${acquiredTokens.size}",
                )

                // Cleanup for next iteration
                raceToken?.let { keyLock.unLock(key, lockType, it) }
                acquiredTokens.forEach { keyLock.unLock(key, lockType, it) }
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
                val expiredToken = keyLock.tryLock(key, lockType)
                assertNotNull(expiredToken, "Iteration $iteration: Initial lock should succeed")
                delay(shortLockTimeout + 5L)

                // High contention: many concurrent tryLock and unLock calls.
                // Releases present whichever token is currently known - a stale one must be rejected.
                val liveTokens = ConcurrentLinkedQueue<String>()
                val jobs =
                    (1..20).map { i ->
                        if (i % 2 == 0) {
                            async(Dispatchers.IO) { keyLock.tryLock(key, lockType)?.let { liveTokens.add(it) } }
                        } else {
                            async(Dispatchers.IO) {
                                keyLock.unLock(key, lockType, liveTokens.poll() ?: expiredToken)
                            }
                        }
                    }
                jobs.awaitAll()

                // Verify: try to acquire lock multiple times concurrently
                val acquiredTokens = ConcurrentLinkedQueue<String>()
                val verifyJobs =
                    (1..10).map {
                        async(Dispatchers.IO) {
                            keyLock.tryLock(key, lockType)?.let { acquiredTokens.add(it) }
                        }
                    }
                verifyJobs.awaitAll()

                if (acquiredTokens.size > 1) {
                    overReleaseDetected.incrementAndGet()
                }

                // Cleanup
                (liveTokens + acquiredTokens).forEach { keyLock.unLock(key, lockType, it) }
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
