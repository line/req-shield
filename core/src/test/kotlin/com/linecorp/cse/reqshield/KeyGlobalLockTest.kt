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
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyGlobalLockTest :
    AbstractRedisTest(),
    BaseKeyLockTest {
    private lateinit var redisCommands: RedisCommands<String, String>
    private lateinit var globalLockFunc: (String, String, Long) -> Boolean
    private lateinit var globalUnLockFunc: (String, String) -> Boolean

    @BeforeEach
    fun init() {
        val host = AbstractRedisTest.redisHost
        val port = AbstractRedisTest.redisPort
        val redisUrl = "redis://$host:$port"
        val redisClient = RedisClient.create(redisUrl)
        val connection = redisClient.connect()
        redisCommands = connection.sync()

        // Clean up all keys from previous tests for proper test isolation
        redisCommands.flushdb()

        // Store the ownership token only when the key is absent, and let Redis expire the lock
        globalLockFunc = { key, token, timeToLiveMillis ->
            redisCommands.set(key, token, SetArgs.Builder.nx().px(timeToLiveMillis)) == "OK"
        }

        // Compare-and-delete: never delete a lock that is already owned by someone else
        globalUnLockFunc = { key, token ->
            redisCommands.eval<Long>(
                COMPARE_AND_DELETE_SCRIPT,
                ScriptOutputType.INTEGER,
                arrayOf(key),
                token,
            ) == 1L
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
        executorService.shutdown()

        await().atMost(Duration.ofSeconds(3)).until { tasksCompletedCount.get() == 20 }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(1, lockAcquiredCount.get())
            assertNotNull(keyLock.tryLock(key, lockType))
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
        executorService.shutdown()

        await().atMost(Duration.ofSeconds(3)).until { tasksCompletedCount.get() == 20 }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertTrue(lockAcquiredCount.get() <= 4)
            assertNotNull(keyLock.tryLock("myKey1", lockType))
            assertNotNull(keyLock.tryLock("myKey2", lockType))
        }
    }

    @Test
    override fun testLockExpiration() {
        val keyLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, lockTimeoutMillis)
        val key = "expirationKey"
        val lockType = LockType.CREATE

        // Given: the lock is held and carries the TTL handed to the lock function
        val tokenOfA = assertNotNull(keyLock.tryLock(key, lockType))
        assertNull(keyLock.tryLock(key, lockType), "Lock must not be acquired twice while held")
        assertFalse(keyLock.unLock(key, lockType, "foreign-token"), "A foreign token must not release the lock")

        // When: the TTL passes without an explicit unlock
        Thread.sleep(lockTimeoutMillis + 500L)

        // Then: the expired lock can be taken over, and the previous holder cannot release it
        val tokenOfB = assertNotNull(keyLock.tryLock(key, lockType), "Expired lock should be acquirable again")
        assertFalse(keyLock.unLock(key, lockType, tokenOfA), "A stale token must not release the new holder's lock")
        assertTrue(keyLock.unLock(key, lockType, tokenOfB), "The current holder can release the lock")
    }

    private fun doWork() = Thread.sleep(1000)

    companion object {
        private const val COMPARE_AND_DELETE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}
