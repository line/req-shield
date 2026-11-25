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

package com.linecorp.cse.reqshield.reactor

import com.linecorp.cse.reqshield.support.BaseKeyLockTest
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import com.linecorp.cse.reqshield.support.constant.ConfigValues.UNLIMITED_LOCK_ENTRIES
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

class KeyLocalLockTest : BaseKeyLockTest {
    @AfterEach
    fun cleanup() {
        // Ensure monitor can restart after each test to prevent test isolation issues
        KeyLocalLock.stopMonitoring()
    }

    @Test
    fun `should share global lockMap across multiple instances`() {
        val instance1 = KeyLocalLock(lockTimeoutMillis)
        val instance2 = KeyLocalLock(lockTimeoutMillis)
        val key = "shared-key"
        val lockType = LockType.CREATE

        val token = instance1.tryLock(key, lockType).block()
        assertNotNull(token)

        // Another instance sees the same lock as held: empty means "not acquired"
        StepVerifier.create(instance2.tryLock(key, lockType)).verifyComplete()

        StepVerifier.create(instance1.unLock(key, lockType, token)).expectNext(true).verifyComplete()
    }

    @Test
    fun `should maintain request collapsing across multiple instances`() {
        val instance1 = KeyLocalLock(lockTimeoutMillis)
        val instance2 = KeyLocalLock(lockTimeoutMillis)
        val instance3 = KeyLocalLock(lockTimeoutMillis)
        val key = "collapsing-key"
        val lockType = LockType.CREATE

        val attempts =
            listOf(instance1, instance2, instance3).map { inst ->
                inst
                    .tryLock(key, lockType)
                    .map { 1 }
                    .defaultIfEmpty(0)
            }

        StepVerifier
            .create(Mono.zip(attempts) { arr -> arr.sumOf { it as Int } })
            .expectNextMatches { it == 1 }
            .verifyComplete()
    }

    @Test
    fun `should not release a lock held by another owner`() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "foreign-token-test"
        val lockType = LockType.CREATE

        val token = keyLock.tryLock(key, lockType).block()
        assertNotNull(token)

        // A token that never owned this lock must not release it
        StepVerifier
            .create(keyLock.unLock(key, lockType, "someone-elses-token"))
            .expectNext(false)
            .verifyComplete()

        // The lock is therefore still held
        StepVerifier.create(keyLock.tryLock(key, lockType)).verifyComplete()

        StepVerifier.create(keyLock.unLock(key, lockType, token)).expectNext(true).verifyComplete()
    }

    @Test
    fun `should not let a stale token release the lock of the next owner`() {
        val shortTimeoutMillis = 300L
        val keyLock = KeyLocalLock(shortTimeoutMillis)
        val key = "stale-token-test"
        val lockType = LockType.CREATE

        val staleToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(staleToken)

        // Let the lock expire so that it can be force-released and handed to a new owner
        Thread.sleep(shortTimeoutMillis + 100L)

        val newToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(newToken)
        assertNotEquals(staleToken, newToken)

        StepVerifier.create(keyLock.unLock(key, lockType, staleToken)).expectNext(false).verifyComplete()
        StepVerifier.create(keyLock.unLock(key, lockType, newToken)).expectNext(true).verifyComplete()
    }

    @Test
    override fun testConcurrencyWithOneKey() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "one-key-test"
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        val tasks =
            (0 until 20).map {
                tasksCompletedCount.incrementAndGet()
                keyLock
                    .tryLock(key, lockType)
                    .flatMap { token ->
                        lockAcquiredCount.incrementAndGet()
                        doWork()
                            .publishOn(Schedulers.boundedElastic())
                            .doFinally { _ ->
                                keyLock.unLock(key, lockType, token).subscribe()
                            }
                    }.onErrorResume { Mono.just(Unit) }
            }

        StepVerifier
            .create(Mono.whenDelayError(tasks))
            .expectComplete()
            .verify()

        assertEquals(20, tasksCompletedCount.get())

        assertEquals(1, lockAcquiredCount.get())

        StepVerifier
            .create(
                Mono
                    .delay(Duration.ofMillis(100))
                    .then(keyLock.tryLock(key, lockType)),
            ).expectNextCount(1)
            .verifyComplete()
    }

    @Test
    override fun testConcurrencyWithTwoKey() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        val tasks =
            (0 until 20).map { i ->
                tasksCompletedCount.incrementAndGet()
                val key = if (i % 2 == 0) "two-key-test1" else "two-key-test2"
                keyLock
                    .tryLock(key, lockType)
                    .flatMap { token ->
                        lockAcquiredCount.incrementAndGet()
                        doWork()
                            .publishOn(Schedulers.boundedElastic())
                            .doFinally { _ ->
                                keyLock.unLock(key, lockType, token).subscribe()
                            }
                    }.onErrorResume { Mono.just(Unit) }
            }

        StepVerifier
            .create(Mono.whenDelayError(tasks))
            .expectComplete()
            .verify()

        assertEquals(20, tasksCompletedCount.get())

        assertTrue(lockAcquiredCount.get() <= 4)

        StepVerifier
            .create(
                Mono
                    .delay(Duration.ofMillis(100))
                    .then(keyLock.tryLock("two-key-test1", lockType)),
            ).expectNextCount(1)
            .verifyComplete()

        StepVerifier
            .create(
                Mono
                    .delay(Duration.ofMillis(100))
                    .then(keyLock.tryLock("two-key-test2", lockType)),
            ).expectNextCount(1)
            .verifyComplete()
    }

    @Test
    override fun testLockExpiration() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "expiration-test"
        val lockType = LockType.CREATE

        val expiredToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(expiredToken)

        // Wait for lock timeout + cleanup interval + buffer
        // lockTimeoutMillis = 3000ms, cleanup interval = 1000ms
        Thread.sleep(lockTimeoutMillis + 1000L + 500L) // 4.5 seconds total

        val newToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(newToken)

        // The expired holder must not be able to release the lock of the new holder
        StepVerifier.create(keyLock.unLock(key, lockType, expiredToken)).expectNext(false).verifyComplete()

        StepVerifier.create(keyLock.unLock(key, lockType, newToken)).expectNext(true).verifyComplete()
    }

    @Test
    fun `should not over-release semaphore on multiple unlock calls`() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "over-release-test"
        val lockType = LockType.CREATE

        // Acquire lock
        val token = keyLock.tryLock(key, lockType).block()
        assertNotNull(token)

        // First unlock should succeed
        StepVerifier
            .create(keyLock.unLock(key, lockType, token))
            .expectNext(true)
            .verifyComplete()

        // Second unlock should return false (over-release prevention)
        StepVerifier
            .create(keyLock.unLock(key, lockType, token))
            .expectNext(false)
            .verifyComplete()

        // Verify semaphore is not over-released: can acquire once, not twice
        val reacquiredToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(reacquiredToken)

        StepVerifier
            .create(keyLock.tryLock(key, lockType))
            .verifyComplete()

        // Cleanup
        keyLock.unLock(key, lockType, reacquiredToken).subscribe()
    }

    @Test
    fun `should prevent concurrent lock acquisition after over-release attempt`() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "concurrent-over-release-test"
        val lockType = LockType.CREATE
        val successfulAcquisitions = AtomicInteger(0)

        // Simulate over-release attempt
        val token = keyLock.tryLock(key, lockType).block()
        assertNotNull(token)

        StepVerifier
            .create(keyLock.unLock(key, lockType, token))
            .expectNext(true)
            .verifyComplete()

        // Multiple unlock attempts should all return false
        repeat(5) {
            StepVerifier
                .create(keyLock.unLock(key, lockType, token))
                .expectNext(false)
                .verifyComplete()
        }

        // Try to acquire lock concurrently - only ONE should succeed
        val attempts =
            (1..10).map {
                keyLock
                    .tryLock(key, lockType)
                    .map { successfulAcquisitions.incrementAndGet() }
                    .defaultIfEmpty(0)
            }

        StepVerifier
            .create(Mono.zip(attempts) { it.toList() })
            .expectNextCount(1)
            .verifyComplete()

        // Only one should have acquired the lock
        assertEquals(1, successfulAcquisitions.get(), "Only one should acquire the lock")
    }

    /**
     * The lock map is private to the companion, so reflection is the only way to observe that
     * unLock really drops the entry rather than leaving it for the expiry monitor.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readLockMap(): Map<String, Any> {
        // The companion's private val is compiled as a static field on the outer class.
        val field = KeyLocalLock::class.java.getDeclaredField("lockMap")
        field.isAccessible = true
        return field.get(null) as Map<String, Any>
    }

    /** Mirrors KeyLocalLock's private completeKey so the test can look up the same map key. */
    private fun lockMapKeyOf(
        key: String,
        lockType: LockType,
    ) = "$LOCK_KEY_PREFIX${key}_${lockType.name}"

    @Test
    fun `unLock removes the map entry right away instead of leaving it until expiry`() {
        // A long lock timeout rules the monitor out as the remover: were the entry kept by unLock,
        // it would still be in the map for the next 60 seconds.
        val keyLock = KeyLocalLock(60_000L)
        val key = "unlock-removal-test-${java.util.UUID.randomUUID()}"
        val mapKey = lockMapKeyOf(key, LockType.CREATE)

        val token = keyLock.tryLock(key, LockType.CREATE).block()
        assertNotNull(token)
        assertTrue(readLockMap().containsKey(mapKey), "A held lock must have an entry in the map")

        StepVerifier.create(keyLock.unLock(key, LockType.CREATE, token)).expectNext(true).verifyComplete()
        assertFalse(readLockMap().containsKey(mapKey), "unLock must drop the entry, not wait for the monitor")
    }

    @Test
    fun `at the cap tryLock still grants a permit but stops adding map entries`() {
        val keyLock = KeyLocalLock(60_000L)
        val first = "cap-test-first-${java.util.UUID.randomUUID()}"
        val second = "cap-test-second-${java.util.UUID.randomUUID()}"

        // Taken while still uncapped, so it always succeeds. Its 60s timeout keeps the entry in
        // the map for the rest of the test, which is what makes a cap of one deterministic here:
        // the map can only grow from this point, never shrink below one.
        val firstToken = assertNotNull(keyLock.tryLock(first, LockType.CREATE).block())
        assertTrue(readLockMap().containsKey(lockMapKeyOf(first, LockType.CREATE)))

        LocalLockLimit.maxEntries = 1
        try {
            val secondToken =
                assertNotNull(
                    keyLock.tryLock(second, LockType.CREATE).block(),
                    "past the cap the caller must still get a permit, so it writes the cache " +
                        "instead of waiting for a holder that does not exist",
                )
            assertFalse(
                readLockMap().containsKey(lockMapKeyOf(second, LockType.CREATE)),
                "a permit handed out past the cap must not add a map entry",
            )
            StepVerifier
                .create(keyLock.unLock(second, LockType.CREATE, secondToken))
                .expectNext(false)
                .verifyComplete()

            // Losing collapsing for that key is exactly what the cap costs.
            assertNotNull(
                keyLock.tryLock(second, LockType.CREATE).block(),
                "past the cap a second caller for the same key is not collapsed either",
            )
        } finally {
            LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES
        }

        keyLock.unLock(first, LockType.CREATE, firstToken).block()
    }

    /**
     * Only this module and the coroutine one can pin this: reaching the existing-entry branch
     * needs an expired entry that nothing sweeps, which means stopping the monitor. The core
     * module has no equivalent hook, so the same case stays uncovered there.
     */
    @Test
    fun `an entry already in the map is reacquired even when the map is at its cap`() {
        // Construct both locks first - each constructor restarts the monitor - then stop it, so
        // the expired entry below survives for the rest of the test.
        val shortLock = KeyLocalLock(1L)
        val longLock = KeyLocalLock(60_000L)
        KeyLocalLock.stopMonitoring()

        val key = "cap-existing-${java.util.UUID.randomUUID()}"
        assertNotNull(shortLock.tryLock(key, LockType.CREATE).block())
        Thread.sleep(20L) // the lock has timed out, and with the monitor stopped nothing removes it

        LocalLockLimit.maxEntries = readLockMap().size.toLong() // exactly full
        try {
            val reacquired =
                assertNotNull(
                    longLock.tryLock(key, LockType.CREATE).block(),
                    "an entry already in the map must not be subject to the cap",
                )
            // A real lock, not a cap permit: it excludes the next caller and releases cleanly.
            StepVerifier.create(longLock.tryLock(key, LockType.CREATE)).verifyComplete()
            StepVerifier
                .create(longLock.unLock(key, LockType.CREATE, reacquired))
                .expectNext(true)
                .verifyComplete()
        } finally {
            LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES
        }
    }

    private fun doWork(): Mono<Unit> =
        Mono
            .delay(Duration.ofSeconds(1))
            .then(Mono.just(Unit))
            .subscribeOn(Schedulers.boundedElastic())
}
