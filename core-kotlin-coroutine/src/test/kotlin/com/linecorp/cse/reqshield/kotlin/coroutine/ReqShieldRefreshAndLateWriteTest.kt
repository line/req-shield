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
import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The refresh guard of ONLY_CREATE_CACHE and the late cache write after a lock timeout. The tests use a map as the cache, so each assertion reads the entry that a request would get. They run
 * in real time, because the lock timeout is measured with the system clock.
 */
class ReqShieldRefreshAndLateWriteTest {
    private val key = "issue-61-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val oldProduct = Product("old", "old")
    private val newProduct = Product("new", "new")
    private val otherProduct = Product("other", "other")

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun shield(
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        lockTimeoutMillis: Long = 3000,
        getCache: suspend (String) -> ReqShieldData<Product>? = { cacheKey -> cache[cacheKey] },
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey ->
                    cacheReads.incrementAndGet()
                    getCache(cacheKey)
                },
                lockTimeoutMillis = lockTimeoutMillis,
                reqShieldWorkMode = workMode,
                scope = scope,
            ),
        )

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    /** Waits until each task that the shield launched on its scope has ended. */
    private suspend fun awaitScopeTasks() = scope.coroutineContext.job.children.toList().forEach { it.join() }

    private suspend fun awaitCachedValue(expected: Product) =
        withTimeout(2_000) {
            while (cache[key]?.value != expected) delay(10)
        }

    @Test
    fun onlyCreateCacheShouldRunOneRefreshOfAKeyAtATime() =
        runBlocking {
            cache[key] = oldEntry()
            val supplierCalls = AtomicInteger()
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            val supplier: suspend () -> Product? = {
                supplierCalls.incrementAndGet()
                supplierStarted.complete(Unit)
                finishSupplier.await()
                newProduct
            }
            val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

            repeat(50) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).value) }
            supplierStarted.await()
            // Give a second refresh the time to call the supplier, if one was launched.
            delay(200)
            assertEquals(1, supplierCalls.get())

            finishSupplier.complete(Unit)
            awaitCachedValue(newProduct)
        }

    @Test
    fun onlyCreateCacheShouldRefreshAgainAfterARefreshFailed() =
        runBlocking {
            cache[key] = oldEntry()
            val supplierCalls = AtomicInteger()
            val supplier: suspend () -> Product? = {
                if (supplierCalls.incrementAndGet() == 1) throw IllegalStateException("origin down")
                newProduct
            }
            val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

            shield.getAndSetReqShieldData(key, supplier, 10_000)
            awaitScopeTasks()
            assertEquals(1, supplierCalls.get())

            // The failed refresh released its guard, so the next request refreshes the entry.
            shield.getAndSetReqShieldData(key, supplier, 10_000)
            awaitCachedValue(newProduct)
        }

    @Test
    fun aNewEntryShouldNotReplaceAnEntryThatAnotherRequestWroteAfterTheLockTimedOut() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 50)
            val other = ReqShieldData(otherProduct, 10_000)

            val result =
                shield.getAndSetReqShieldData(
                    key,
                    {
                        // The supplier is slower than the lock timeout, and another request writes the entry meanwhile.
                        delay(150)
                        cache[key] = other
                        newProduct
                    },
                    10_000,
                )
            awaitScopeTasks()

            assertEquals(newProduct, result.value, "the caller still gets the result of its own supplier call")
            assertEquals(other, cache[key], "the entry that the other request wrote must stay")
        }

    @Test
    fun aNewEntryShouldBeWrittenAfterALockTimeoutWhenNoOtherRequestWroteOne() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 50)

            shield.getAndSetReqShieldData(
                key,
                {
                    delay(150)
                    newProduct
                },
                10_000,
            )
            awaitScopeTasks()

            assertEquals(newProduct, cache[key]?.value)
        }

    @Test
    fun aNewEntryShouldBeWrittenWithoutAnExtraCacheReadWhileTheLockIsValid() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 3000)

            shield.getAndSetReqShieldData(key, { newProduct }, 10_000)
            awaitScopeTasks()

            assertEquals(newProduct, cache[key]?.value)
            assertEquals(2, cacheReads.get(), "only the first read and the recheck after the lock")
        }

    @Test
    fun aRefreshShouldNotReplaceAnEntryThatAnotherRequestWroteAfterTheLockTimedOut() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 50)
            cache[key] = oldEntry()
            val other = ReqShieldData(otherProduct, 10_000)

            shield.getAndSetReqShieldData(
                key,
                {
                    delay(150)
                    cache[key] = other
                    newProduct
                },
                10_000,
            )
            awaitScopeTasks()

            assertEquals(other, cache[key])
        }

    @Test
    fun aRefreshShouldReplaceTheEntryItReadAfterTheLockTimedOut() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 50)
            cache[key] = oldEntry()

            shield.getAndSetReqShieldData(
                key,
                {
                    delay(150)
                    newProduct
                },
                10_000,
            )
            awaitScopeTasks()

            // The cache still held the entry that this request read, so the slow refresh wrote its result.
            assertEquals(newProduct, cache[key]?.value)
        }

    @Test
    fun aLateWriteShouldGoOnWhenTheCacheReadBeforeItFails() =
        runBlocking {
            // The first read and the recheck succeed. The read before the late write fails.
            val shield =
                shield(
                    lockTimeoutMillis = 50,
                    getCache = { cacheKey ->
                        if (cacheReads.get() > 2) throw IllegalStateException("cache down")
                        cache[cacheKey]
                    },
                )

            shield.getAndSetReqShieldData(
                key,
                {
                    delay(150)
                    newProduct
                },
                10_000,
            )
            awaitScopeTasks()

            assertEquals(3, cacheReads.get())
            assertEquals(newProduct, cache[key]?.value)
        }
}
