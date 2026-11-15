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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * With a global lock, the reads of an old entry do not each call the lock store. A JVM asks for the refresh lock of
 * one key once in 50 ms, and not at all while one of its own requests runs the refresh.
 */
class ReqShieldGlobalRefreshAttemptTest {
    private val key = "global-refresh-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val lockCalls = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(2)
    private val oldProduct = Product("old", "old")
    private val newProduct = Product("new", "new")

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun shield(storeGivesTheLock: Boolean): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey -> cache[cacheKey] },
                globalLockFunction = { _, _, _ ->
                    lockCalls.incrementAndGet()
                    storeGivesTheLock
                },
                globalUnLockFunction = { _, _ -> true },
                isLocalLock = false,
                executor = pool,
            ),
        )

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    @Test
    fun readsOfAnOldEntryShouldAskTheStoreOnceInAnIntervalWhileAnotherJvmHoldsTheLock() {
        cache[key] = oldEntry()
        // The store answers no: a request of another JVM runs the refresh
        val shield = shield(storeGivesTheLock = false)

        repeat(50) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, { newProduct }, 10_000).value) }

        assertEquals(1, lockCalls.get())

        // After the interval the next read asks again
        Thread.sleep(80)
        shield.getAndSetReqShieldData(key, { newProduct }, 10_000)

        assertEquals(2, lockCalls.get())
    }

    @Test
    fun readsDuringARefreshOfThisJvmShouldNotAskTheStore() {
        cache[key] = oldEntry()
        val supplierStarted = CountDownLatch(1)
        val finishSupplier = CountDownLatch(1)
        val supplier =
            Callable<Product?> {
                supplierStarted.countDown()
                check(finishSupplier.await(5, TimeUnit.SECONDS))
                newProduct
            }
        val shield = shield(storeGivesTheLock = true)

        try {
            shield.getAndSetReqShieldData(key, supplier, 10_000)
            assertTrue(supplierStarted.await(2, TimeUnit.SECONDS))
            // Longer than the interval, so only the refresh of this JVM keeps the reads from the store
            Thread.sleep(80)

            repeat(20) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).value) }

            assertEquals(1, lockCalls.get())
        } finally {
            finishSupplier.countDown()
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
    }

    @Test
    fun aLockThatIsNotAGlobalLockOfTheLibraryShouldBeAskedOnEachRead() {
        cache[key] = oldEntry()
        val customLockCalls = AtomicInteger()
        val customLock =
            object : KeyLock {
                override fun tryLock(
                    key: String,
                    lockType: LockType,
                ): String? {
                    customLockCalls.incrementAndGet()
                    return null
                }

                override fun unLock(
                    key: String,
                    lockType: LockType,
                    token: String,
                ): Boolean = false
            }
        val shield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ -> true },
                    getCacheFunction = { cacheKey -> cache[cacheKey] },
                    executor = pool,
                    keyLock = customLock,
                ),
            )

        repeat(10) { shield.getAndSetReqShieldData(key, { newProduct }, 10_000) }

        assertEquals(10, customLockCalls.get())
    }
}
