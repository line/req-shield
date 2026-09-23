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
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.UNLIMITED_LOCK_ENTRIES
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.assertNotNull

/**
 * Exercises a real [KeyLocalLock] through [ReqShield] with the lock map capped.
 *
 * The unit tests only prove what `tryLock` returns; what actually matters is what the request
 * does with that answer. A refusal would send the caller into the lost-the-lock path, where it
 * polls for a holder that does not exist and then returns **without writing the cache** - so the
 * key would never become cacheable while the cap held. These tests pin the opposite.
 */
class ReqShieldLockLimitIntegrationTest {
    private val maxAttemptGetCache = 20
    private val pollingBudgetMillis = maxAttemptGetCache * GET_CACHE_INTERVAL_MILLIS

    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val supplierCalls = AtomicInteger(0)
    private lateinit var saturator: KeyLocalLock
    private lateinit var saturatorToken: String
    private lateinit var saturatorKey: String

    private fun reqShield(): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { key, data, _ ->
                    cache[key] = data
                    true
                },
                getCacheFunction = { cache[it] },
                maxAttemptGetCache = maxAttemptGetCache,
            ),
        )

    private fun supplier(name: String): Callable<Product?> =
        Callable {
            supplierCalls.incrementAndGet()
            Product("id-$name", name)
        }

    /**
     * Holds one real lock and caps the map at that single entry, so every other key is past the
     * cap. Taking the lock while still uncapped makes this deterministic: the map can only grow
     * from here, so it never drops back below the cap during a test.
     */
    private fun saturateLockMap() {
        saturatorKey = "saturator-${UUID.randomUUID()}"
        saturator = KeyLocalLock(60_000L)
        saturatorToken = assertNotNull(saturator.tryLock(saturatorKey, LockType.CREATE))
        LocalLockLimit.maxEntries = 1
    }

    @AfterEach
    fun releaseSaturator() {
        LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES
        if (::saturator.isInitialized) {
            saturator.unLock(saturatorKey, LockType.CREATE, saturatorToken)
            saturator.shutdown()
        }
    }

    @Test
    fun `a cache miss past the cap populates the cache without waiting for a holder`() {
        saturateLockMap()
        val key = "capped-miss-${UUID.randomUUID()}"

        val elapsed =
            measureTimeMillis {
                val data = reqShield().getAndSetReqShieldData(key, supplier("first"), 10_000)
                assertEquals("first", data.value?.name)
            }

        assertTrue(
            elapsed < pollingBudgetMillis,
            "the request must not spend the ${pollingBudgetMillis}ms polling budget waiting for a " +
                "holder that the cap prevented from existing, but took ${elapsed}ms",
        )
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertNotNull(cache[key], "a request past the cap must still write the cache")
        }
        assertEquals(1, supplierCalls.get())
    }

    @Test
    fun `the value written past the cap is served from the cache on the next request`() {
        saturateLockMap()
        val key = "capped-reuse-${UUID.randomUUID()}"

        reqShield().getAndSetReqShieldData(key, supplier("first"), 10_000)
        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertNotNull(cache[key]) }

        // Still capped: the second request must be a plain cache hit, not another supplier call.
        val data = reqShield().getAndSetReqShieldData(key, supplier("second"), 10_000)

        assertEquals("first", data.value?.name, "the second request must be served from the cache")
        assertEquals(1, supplierCalls.get(), "a cached key must not reach the supplier again")
    }

    @Test
    fun `an uncapped map is unaffected`() {
        LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES
        val key = "uncapped-${UUID.randomUUID()}"

        val data = reqShield().getAndSetReqShieldData(key, supplier("first"), 10_000)

        assertEquals("first", data.value?.name)
        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertNotNull(cache[key]) }
        assertEquals(1, supplierCalls.get())
    }
}
