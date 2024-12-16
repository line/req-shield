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
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import io.lettuce.core.RedisClient
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore

class KeyGlobalLockTest : BaseKeyLockTest, AbstractRedisTest() {
    private lateinit var redisCommands: RedisAsyncCommands<String, String>
    private lateinit var globalLockFunc: suspend (String, Long) -> Boolean
    private lateinit var globalUnLockFunc: suspend (String) -> Boolean

    @BeforeEach
    fun init() {
        val redisUrl = "redis://localhost:6379" // testContainer url
        val redisClient = RedisClient.create(redisUrl)
        val connection = redisClient.connect()
        redisCommands = connection.async()

        globalLockFunc = { key, timeToLiveMillis ->
            redisCommands.setnx(key, key).toCompletableFuture().await()
        }

        globalUnLockFunc = { key ->
            redisCommands.del(key).toCompletableFuture().await()
            true
        }
    }

    @Test
    override fun `test concurrency with one key`() =
        runBlocking {
            val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
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
    override fun `test concurrency with two key`() =
        runBlocking {
            val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
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

            assertTrue(lockAcquiredCount.get() <= 4)

            delay(100)

            assertTrue(keyLock.tryLock("myKey1", lockType))
            assertTrue(keyLock.tryLock("myKey2", lockType))
        }

    @Test
    @Ignore
    override fun `test lock expiration`() =
        runBlocking {
            // Global locks do not have an expiration
        }

    private suspend fun doWork() = delay(100)
}
