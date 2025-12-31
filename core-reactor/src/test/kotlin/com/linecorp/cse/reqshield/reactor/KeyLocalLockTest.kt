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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

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

        StepVerifier.create(instance1.tryLock(key, lockType)).expectNext(true).verifyComplete()
        StepVerifier.create(instance2.tryLock(key, lockType)).expectNext(false).verifyComplete()

        StepVerifier.create(instance1.unLock(key, lockType)).expectNext(true).verifyComplete()
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
                inst.tryLock(key, lockType).map { acquired -> if (acquired) 1 else 0 }
            }

        StepVerifier
            .create(Mono.zip(attempts) { arr -> arr.sumOf { it as Int } })
            .expectNextMatches { it == 1 }
            .verifyComplete()

        // cleanup by unlocking whoever acquired
        listOf(instance1, instance2, instance3).forEach { inst -> inst.unLock(key, lockType).subscribe() }
    }

    @Test
    override fun testConcurrencyWithOneKey() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "myKey"
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        val tasks =
            (0 until 20).map {
                tasksCompletedCount.incrementAndGet()
                keyLock
                    .tryLock(key, lockType)
                    .filter { it }
                    .flatMap {
                        lockAcquiredCount.incrementAndGet()
                        doWork()
                            .publishOn(Schedulers.boundedElastic())
                            .doFinally { _ ->
                                keyLock.unLock(key, lockType).subscribe()
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
            ).expectNext(true)
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
                val key = if (i % 2 == 0) "myKey1" else "myKey2"
                keyLock
                    .tryLock(key, lockType)
                    .filter { it }
                    .flatMap {
                        lockAcquiredCount.incrementAndGet()
                        doWork()
                            .publishOn(Schedulers.boundedElastic())
                            .doFinally { _ ->
                                keyLock.unLock(key, lockType).subscribe()
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
                    .then(keyLock.tryLock("myKey1", lockType)),
            ).expectNext(true)
            .verifyComplete()

        StepVerifier
            .create(
                Mono
                    .delay(Duration.ofMillis(100))
                    .then(keyLock.tryLock("myKey2", lockType)),
            ).expectNext(true)
            .verifyComplete()
    }

    @Test
    override fun testLockExpiration() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "myKey"
        val lockType = LockType.CREATE

        StepVerifier
            .create(
                keyLock.tryLock(key, lockType),
            ).expectNext(true)
            .verifyComplete()

        // Wait for lock timeout + cleanup interval + buffer
        // lockTimeoutMillis = 3000ms, cleanup interval = 1000ms
        Thread.sleep(lockTimeoutMillis + 1000L + 500L) // 4.5 seconds total

        StepVerifier
            .create(
                keyLock.tryLock(key, lockType),
            ).expectNext(true)
            .verifyComplete()

        StepVerifier
            .create(
                keyLock.unLock(key, lockType),
            ).expectNext(true)
            .verifyComplete()
    }

    @Test
    fun `should not over-release semaphore on multiple unlock calls`() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "over-release-test"
        val lockType = LockType.CREATE

        // Acquire lock
        StepVerifier.create(keyLock.tryLock(key, lockType))
            .expectNext(true)
            .verifyComplete()

        // First unlock should succeed
        StepVerifier.create(keyLock.unLock(key, lockType))
            .expectNext(true)
            .verifyComplete()

        // Second unlock should return false (over-release prevention)
        StepVerifier.create(keyLock.unLock(key, lockType))
            .expectNext(false)
            .verifyComplete()

        // Verify semaphore is not over-released: can acquire once, not twice
        StepVerifier.create(keyLock.tryLock(key, lockType))
            .expectNext(true)
            .verifyComplete()

        StepVerifier.create(keyLock.tryLock(key, lockType))
            .expectNext(false)
            .verifyComplete()

        // Cleanup
        keyLock.unLock(key, lockType).subscribe()
    }

    @Test
    fun `should prevent concurrent lock acquisition after over-release attempt`() {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "concurrent-over-release-test"
        val lockType = LockType.CREATE
        val successfulAcquisitions = AtomicInteger(0)

        // Simulate over-release attempt
        StepVerifier.create(keyLock.tryLock(key, lockType))
            .expectNext(true)
            .verifyComplete()

        StepVerifier.create(keyLock.unLock(key, lockType))
            .expectNext(true)
            .verifyComplete()

        // Multiple unlock attempts should all return false
        repeat(5) {
            StepVerifier.create(keyLock.unLock(key, lockType))
                .expectNext(false)
                .verifyComplete()
        }

        // Try to acquire lock concurrently - only ONE should succeed
        val attempts =
            (1..10).map {
                keyLock.tryLock(key, lockType)
                    .map { acquired -> if (acquired) successfulAcquisitions.incrementAndGet() else 0 }
            }

        StepVerifier
            .create(Mono.zip(attempts) { it.toList() })
            .expectNextCount(1)
            .verifyComplete()

        // Only one should have acquired the lock
        assertEquals(1, successfulAcquisitions.get(), "Only one should acquire the lock")

        // Cleanup
        keyLock.unLock(key, lockType).subscribe()
    }

    private fun doWork(): Mono<Unit> =
        Mono
            .delay(Duration.ofSeconds(1))
            .then(Mono.just(Unit))
            .subscribeOn(Schedulers.boundedElastic())
}
