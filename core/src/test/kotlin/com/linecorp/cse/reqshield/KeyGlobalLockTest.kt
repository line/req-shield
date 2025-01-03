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
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore

class KeyGlobalLockTest :
    AbstractRedisTest(),
    BaseKeyLockTest {
    private lateinit var redisCommands: RedisCommands<String, String>
    private lateinit var globalLockFunc: (String, Long) -> Boolean
    private lateinit var globalUnLockFunc: (String) -> Boolean

    @BeforeEach
    fun init() {
        val redisUrl = "redis://localhost:6379" // testContainer url
        val redisClient = RedisClient.create(redisUrl)
        val connection = redisClient.connect()
        redisCommands = connection.sync()

        globalLockFunc = { key, timeToLiveMillis ->
            redisCommands.setnx(key, key)
        }

        globalUnLockFunc = { key ->
            redisCommands.del(key)
            true
        }
    }

    @Test
    override fun testConcurrencyWithOneKey() {
        val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
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
        val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
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
    @Ignore
    override fun testLockExpiration() {
        // Global locks do not have an expiration
    }

    private fun doWork() = Thread.sleep(1000)
}
