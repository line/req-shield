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
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.async.RedisAsyncCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

class KeyGlobalLockTest :
    AbstractRedisTest(),
    BaseKeyLockTest {
    private lateinit var redisCommands: RedisAsyncCommands<String, String>
    private lateinit var globalLockFunc: (String, String, Long) -> Mono<Boolean>
    private lateinit var globalUnLockFunc: (String, String) -> Mono<Boolean>

    @BeforeEach
    fun init() {
        val host = AbstractRedisTest.redisHost
        val port = AbstractRedisTest.redisPort
        val redisUrl = "redis://$host:$port"
        val redisClient = RedisClient.create(redisUrl)
        val connection = redisClient.connect()
        redisCommands = connection.async()

        // Clean up all keys from previous tests for proper test isolation
        connection.sync().flushdb()

        // Recommended lock implementation: the token is stored as the value so that
        // ownership can be checked on release, and PX makes the lock self-expiring.
        globalLockFunc = { lockKey, token, ttlMillis ->
            Mono
                .fromFuture {
                    redisCommands.set(lockKey, token, SetArgs.Builder.nx().px(ttlMillis)).toCompletableFuture()
                }.map { it == "OK" }
        }

        // Compare-and-delete: only the owner of the stored token may release the lock.
        globalUnLockFunc = { lockKey, token ->
            Mono
                .fromFuture {
                    redisCommands
                        .eval<Long>(UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token)
                        .toCompletableFuture()
                }.map { it == 1L }
        }
    }

    @Test
    override fun testConcurrencyWithOneKey() {
        val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
        val key = "myKey"
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
        val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        val tasks =
            (0 until 20).map { i ->
                tasksCompletedCount.incrementAndGet()
                val key = if (i % 2 == 0) "myKey1" else "myKey2"
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
                    .then(keyLock.tryLock("myKey1", lockType)),
            ).expectNextCount(1)
            .verifyComplete()

        StepVerifier
            .create(
                Mono
                    .delay(Duration.ofMillis(100))
                    .then(keyLock.tryLock("myKey2", lockType)),
            ).expectNextCount(1)
            .verifyComplete()
    }

    @Test
    override fun testLockExpiration() {
        val shortTimeoutMillis = 500L
        val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, shortTimeoutMillis)
        val key = "expirationKey"
        val lockType = LockType.CREATE

        val expiredToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(expiredToken)

        // While the lock is held, nobody else can acquire it
        StepVerifier.create(keyLock.tryLock(key, lockType)).verifyComplete()

        // Wait for the Redis key TTL to elapse
        Thread.sleep(shortTimeoutMillis + 300L)

        val newToken = keyLock.tryLock(key, lockType).block()
        assertNotNull(newToken)
        assertNotEquals(expiredToken, newToken)

        // The expired holder must not release the lock that now belongs to someone else
        StepVerifier
            .create(keyLock.unLock(key, lockType, expiredToken))
            .expectNext(false)
            .verifyComplete()

        StepVerifier
            .create(keyLock.unLock(key, lockType, newToken))
            .expectNext(true)
            .verifyComplete()
    }

    private fun doWork(): Mono<Unit> =
        Mono
            .delay(Duration.ofSeconds(1))
            .then(Mono.just(Unit))
            .subscribeOn(Schedulers.boundedElastic())

    companion object {
        private const val UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}
