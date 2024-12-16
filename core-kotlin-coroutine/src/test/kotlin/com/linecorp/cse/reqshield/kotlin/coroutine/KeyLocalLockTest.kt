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
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class KeyLocalLockTest: BaseKeyLockTest {

    @Test
    override fun `test concurrency with one key`() = runBlocking {
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "myKey"
        val lockType = LockType.CREATE
        val lockAcquiredCount = AtomicInteger(0)
        val tasksCompletedCount = AtomicInteger(0)

        val jobs = List(20) {
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
    override fun `test concurrency with two key`() = runBlocking {
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
    override fun `test lock expiration`() = runBlocking {

        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val key = "myKey"
        val lockType = LockType.CREATE

        assertTrue(keyLock.tryLock(key, lockType))

        delay(Duration.ofSeconds(3).toMillis() + 100)

        val result = withContext(Dispatchers.IO) {
            keyLock.tryLock(key, lockType)
        }

        assertTrue(result)
        assertTrue(keyLock.unLock(key, lockType))
    }

    private suspend fun doWork() = delay(1000)
}