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
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Releases the lock only when the stored value still equals the token presented by the caller.
 * This is the compare-and-delete the global unlock function is documented to implement.
 */
private const val UNLOCK_SCRIPT =
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"

class KeyGlobalLockTest :
    AbstractRedisTest(),
    BaseKeyLockTest {
    private lateinit var redisCommands: RedisAsyncCommands<String, String>
    private lateinit var globalLockFunc: suspend (String, String, Long) -> Boolean
    private lateinit var globalUnLockFunc: suspend (String, String) -> Boolean

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

        globalLockFunc = { key, token, lockTimeoutMillis ->
            redisCommands
                .set(key, token, SetArgs.Builder.nx().px(lockTimeoutMillis))
                .toCompletableFuture()
                .await() == "OK"
        }

        globalUnLockFunc = { key, token ->
            redisCommands
                .eval<Long>(UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(key), token)
                .toCompletableFuture()
                .await() == 1L
        }
    }

    @Test
    override fun testConcurrencyWithOneKey() =
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
                            val token = keyLock.tryLock(key, lockType)
                            if (token != null) {
                                try {
                                    println("${Thread.currentThread().name} acquired the lock")
                                    lockAcquiredCount.incrementAndGet()
                                    doWork()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                } finally {
                                    assertTrue(keyLock.unLock(key, lockType, token))
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
            val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
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
                                    println("${Thread.currentThread().name} acquired the lock")
                                    lockAcquiredCount.incrementAndGet()
                                    doWork()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                } finally {
                                    assertTrue(keyLock.unLock(key, lockType, token))
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

            assertTrue(keyLock.tryLock("myKey1", lockType) != null)
            assertTrue(keyLock.tryLock("myKey2", lockType) != null)
        }

    @Test
    override fun testLockExpiration() =
        runBlocking {
            // The lock is written with `SET ... NX PX lockTimeoutMillis`, so Redis expires it for us.
            val shortLockTimeout = 300L
            val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, shortLockTimeout)
            val key = "expiring-key"
            val lockType = LockType.CREATE

            val expiredToken = keyLock.tryLock(key, lockType)
            assertNotNull(expiredToken)
            assertNull(keyLock.tryLock(key, lockType), "The lock must stay held until it expires")

            delay(shortLockTimeout + 200L)

            val newToken = keyLock.tryLock(key, lockType)
            assertNotNull(newToken, "The lock must be reacquirable once it expired")

            // The previous owner must not be able to release the lock of the new owner.
            assertFalse(keyLock.unLock(key, lockType, expiredToken))
            assertNull(keyLock.tryLock(key, lockType), "The new owner must still hold the lock")

            assertTrue(keyLock.unLock(key, lockType, newToken))
            assertTrue(keyLock.tryLock(key, lockType) != null, "The released lock must be acquirable again")
        }

    private suspend fun doWork() = delay(100)
}
