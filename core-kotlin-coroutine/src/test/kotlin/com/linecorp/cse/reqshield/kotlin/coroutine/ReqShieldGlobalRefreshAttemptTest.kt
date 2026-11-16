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

import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * With a global lock, the reads of an old entry do not each call the lock store. A JVM asks for the refresh lock of
 * one key once in 50 ms, and not at all while one of its own requests runs the refresh.
 */
class ReqShieldGlobalRefreshAttemptTest {
    private val key = "global-refresh-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val lockCalls = AtomicInteger()
    private val oldProduct = Product("old", "old")
    private val newProduct = Product("new", "new")

    private fun shield(
        storeGivesTheLock: Boolean,
        lockTimeoutMillis: Long = 3000,
    ): ReqShield<Product> =
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
                lockTimeoutMillis = lockTimeoutMillis,
            ),
        )

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    @Test
    fun readsOfAnOldEntryShouldAskTheStoreOnceInAnIntervalWhileAnotherJvmHoldsTheLock() =
        runBlocking {
            cache[key] = oldEntry()
            // The store answers no: a request of another JVM runs the refresh
            val shield = shield(storeGivesTheLock = false)

            repeat(50) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, { newProduct }, 10_000).value) }

            assertEquals(1, lockCalls.get())

            // After the interval the next read asks again
            delay(80)
            shield.getAndSetReqShieldData(key, { newProduct }, 10_000)

            assertEquals(2, lockCalls.get())
        }

    @Test
    fun readsDuringARefreshOfThisJvmShouldNotAskTheStore() =
        runBlocking {
            cache[key] = oldEntry()
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            val supplier: suspend () -> Product? = {
                supplierStarted.complete(Unit)
                finishSupplier.await()
                newProduct
            }
            val shield = shield(storeGivesTheLock = true)

            try {
                shield.getAndSetReqShieldData(key, supplier, 10_000)
                withTimeout(2_000) { supplierStarted.await() }
                // Longer than the interval, so only the refresh of this JVM keeps the reads from the store
                delay(80)

                repeat(20) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).value) }

                assertEquals(1, lockCalls.get())
            } finally {
                finishSupplier.complete(Unit)
            }
            withTimeout(2_000) {
                while (cache[key]?.value != newProduct) delay(5)
            }
        }

    @Test
    fun aReadShouldAskTheStoreAgainAfterTheLockOfARefreshOfThisJvmTimedOut() =
        runBlocking {
            cache[key] = oldEntry()
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            // The supplier of the first refresh is slower than the lock timeout
            val slowSupplier: suspend () -> Product? = {
                supplierStarted.complete(Unit)
                finishSupplier.await()
                oldProduct
            }
            val shield = shield(storeGivesTheLock = true, lockTimeoutMillis = 200)

            try {
                shield.getAndSetReqShieldData(key, slowSupplier, 10_000)
                withTimeout(2_000) { supplierStarted.await() }
                assertEquals(1, lockCalls.get())
                // The lock of the first refresh timed out, so the store gives it to the next request
                delay(300)

                assertEquals(oldProduct, shield.getAndSetReqShieldData(key, { newProduct }, 10_000).value)

                withTimeout(2_000) {
                    while (lockCalls.get() != 2 || cache[key]?.value != newProduct) delay(5)
                }
            } finally {
                finishSupplier.complete(Unit)
            }
        }
}
