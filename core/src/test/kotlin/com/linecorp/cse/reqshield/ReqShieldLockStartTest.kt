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

import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The start of a lock for the late-write check. A lock that is not a KeyLocalLock - a lock in a store - starts
 * before its answer arrives, so the time of the lock call counts. The check reads the cache again only after the
 * lock timed out, so the number of cache reads shows which start was used.
 */
class ReqShieldLockStartTest {
    private val key = "lock-start-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(2)
    private val product = Product("id", "name")

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun shield(keyLock: KeyLock): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey ->
                    cacheReads.incrementAndGet()
                    cache[cacheKey]
                },
                executor = pool,
                lockTimeoutMillis = LOCK_TIMEOUT_MILLIS,
                keyLock = keyLock,
            ),
        )

    /** A lock whose call takes longer than the lock timeout, as a slow lock store would. */
    private fun slowLock(): KeyLock {
        val localLock = KeyLocalLock(LOCK_TIMEOUT_MILLIS)
        return object : KeyLock {
            override fun tryLock(
                key: String,
                lockType: LockType,
            ): String? {
                Thread.sleep(LOCK_TIMEOUT_MILLIS + 30)
                return localLock.tryLock(key, lockType)
            }

            override fun unLock(
                key: String,
                lockType: LockType,
                token: String,
            ): Boolean = localLock.unLock(key, lockType, token)
        }
    }

    @Test
    fun theTimeOfTheLockCallShouldCountForALockThatIsNotALocalLock() {
        shield(slowLock()).getAndSetReqShieldData(key, { product }, 10_000)

        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        // The read before the lock, the read under the lock, and the read before the write of a lock that timed out
        assertEquals(3, cacheReads.get())
    }

    @Test
    fun aLocalLockThatDidNotTimeOutShouldCostNoReadBeforeTheWrite() {
        shield(KeyLocalLock(LOCK_TIMEOUT_MILLIS)).getAndSetReqShieldData(key, { product }, 10_000)

        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        // The read before the lock and the read under the lock
        assertEquals(2, cacheReads.get())
    }

    private companion object {
        const val LOCK_TIMEOUT_MILLIS = 200L
    }
}
