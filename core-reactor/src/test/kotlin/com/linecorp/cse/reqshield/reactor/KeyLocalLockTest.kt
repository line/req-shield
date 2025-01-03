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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KeyLocalLockTest : BaseKeyLockTest {
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

        Thread.sleep(TimeUnit.SECONDS.toMillis(3) + 100)

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

    private fun doWork(): Mono<Unit> =
        Mono
            .delay(Duration.ofSeconds(1))
            .then(Mono.just(Unit))
            .subscribeOn(Schedulers.boundedElastic())
}
