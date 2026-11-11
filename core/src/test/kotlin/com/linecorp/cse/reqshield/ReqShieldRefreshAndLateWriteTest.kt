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
import com.linecorp.cse.reqshield.config.ReqShieldWorkMode
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
 * The refresh guard of ONLY_CREATE_CACHE and the late cache write after a lock timeout. The tests use a map as the
 * cache, so each assertion reads the entry that a request would get.
 */
class ReqShieldRefreshAndLateWriteTest {
    private val key = "issue-61-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4)
    private val oldProduct = Product("old", "old")
    private val newProduct = Product("new", "new")
    private val otherProduct = Product("other", "other")

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun shield(
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        lockTimeoutMillis: Long = 3000,
    ): ReqShield<Product> =
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
                executor = executor,
                lockTimeoutMillis = lockTimeoutMillis,
                reqShieldWorkMode = workMode,
            ),
        )

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    @Test
    fun onlyCreateCacheShouldRunOneRefreshOfAKeyAtATime() {
        cache[key] = oldEntry()
        val supplierCalls = AtomicInteger()
        val supplierStarted = CountDownLatch(1)
        val finishSupplier = CountDownLatch(1)
        val supplier =
            Callable<Product?> {
                supplierCalls.incrementAndGet()
                supplierStarted.countDown()
                check(finishSupplier.await(5, TimeUnit.SECONDS))
                newProduct
            }
        val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

        try {
            repeat(50) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).value) }
            assertTrue(supplierStarted.await(2, TimeUnit.SECONDS))
            // Let the executor run each task it got. A second refresh would call the supplier in one of them.
            executor.submit {}.get(2, TimeUnit.SECONDS)

            assertEquals(1, supplierCalls.get())
        } finally {
            finishSupplier.countDown()
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
    }

    @Test
    fun onlyCreateCacheShouldRefreshAgainAfterARefreshEnded() {
        cache[key] = oldEntry()
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Product?> {
                supplierCalls.incrementAndGet()
                newProduct
            }
        val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

        shield.getAndSetReqShieldData(key, supplier, 10_000)
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }

        // The entry is old again. The first refresh released its guard, so this request starts a refresh.
        cache[key] = oldEntry()
        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            shield.getAndSetReqShieldData(key, supplier, 10_000)
            assertEquals(newProduct, cache[key]?.value)
        }
        assertTrue(supplierCalls.get() >= 2)
    }

    @Test
    fun onlyCreateCacheShouldRefreshAgainAfterARefreshFailed() {
        cache[key] = oldEntry()
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Product?> {
                if (supplierCalls.incrementAndGet() == 1) throw IllegalStateException("origin down")
                newProduct
            }
        val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

        shield.getAndSetReqShieldData(key, supplier, 10_000)
        await().atMost(Duration.ofSeconds(2)).until { supplierCalls.get() == 1 }

        // The failed refresh released its guard, so a later request refreshes the entry.
        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            shield.getAndSetReqShieldData(key, supplier, 10_000)
            assertEquals(newProduct, cache[key]?.value)
        }
    }

    @Test
    fun aNewEntryShouldNotReplaceAnEntryThatAnotherRequestWroteAfterTheLockTimedOut() {
        val shield = shield(lockTimeoutMillis = 50)
        val other = ReqShieldData(otherProduct, 10_000)
        val supplier =
            Callable<Product?> {
                // The supplier is slower than the lock timeout, and another request writes the entry meanwhile.
                Thread.sleep(150)
                cache[key] = other
                newProduct
            }

        val result = shield.getAndSetReqShieldData(key, supplier, 10_000)

        assertEquals(newProduct, result.value, "the caller still gets the result of its own supplier call")
        assertEquals(other, cache[key], "the entry of the later supplier call must stay")
    }

    @Test
    fun theFirstLateWriteShouldStayWhenTwoCallsBothOutlastTheLockTimeout() {
        // The limit of the rule: the entry holds no start time, so the call that ends first wins, also when it
        // started first. The second call here started later, and its result is not written.
        val shield = shield(lockTimeoutMillis = 50)
        val firstStarted = CountDownLatch(1)
        val first =
            executor.submit<Product?> {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        firstStarted.countDown()
                        Thread.sleep(300)
                        oldProduct
                    },
                    10_000,
                ).value
            }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        // The lock of the first call has timed out, so the second call takes it and calls the supplier too.
        Thread.sleep(100)
        val second =
            shield.getAndSetReqShieldData(
                key,
                {
                    Thread.sleep(400)
                    newProduct
                },
                10_000,
            ).value

        assertEquals(oldProduct, first.get(2, TimeUnit.SECONDS))
        assertEquals(newProduct, second, "each caller gets the result of its own supplier call")
        assertEquals(oldProduct, cache[key]?.value)
    }

    @Test
    fun aNewEntryShouldBeWrittenAfterALockTimeoutWhenNoOtherRequestWroteOne() {
        val shield = shield(lockTimeoutMillis = 50)
        val supplier =
            Callable<Product?> {
                Thread.sleep(150)
                newProduct
            }

        shield.getAndSetReqShieldData(key, supplier, 10_000)

        assertEquals(newProduct, cache[key]?.value)
    }

    @Test
    fun aNewEntryShouldBeWrittenWithoutAnExtraCacheReadWhileTheLockIsValid() {
        val shield = shield(lockTimeoutMillis = 3000)

        shield.getAndSetReqShieldData(key, { newProduct }, 10_000)

        assertEquals(newProduct, cache[key]?.value)
        assertEquals(2, cacheReads.get(), "only the first read and the recheck after the lock")
    }

    @Test
    fun aRefreshShouldNotReplaceAnEntryThatAnotherRequestWroteAfterTheLockTimedOut() {
        val shield = shield(lockTimeoutMillis = 50)
        cache[key] = oldEntry()
        val other = ReqShieldData(otherProduct, 10_000)
        val supplierDone = CountDownLatch(1)
        val supplier =
            Callable<Product?> {
                Thread.sleep(150)
                cache[key] = other
                supplierDone.countDown()
                newProduct
            }

        shield.getAndSetReqShieldData(key, supplier, 10_000)

        assertTrue(supplierDone.await(2, TimeUnit.SECONDS))
        // Let the refresh task end before the cache is checked.
        executor.submit {}.get(2, TimeUnit.SECONDS)
        Thread.sleep(100)
        assertEquals(other, cache[key])
    }

    @Test
    fun aRefreshShouldReplaceTheEntryItReadAfterTheLockTimedOut() {
        val shield = shield(lockTimeoutMillis = 50)
        cache[key] = oldEntry()
        val supplier =
            Callable<Product?> {
                Thread.sleep(150)
                newProduct
            }

        shield.getAndSetReqShieldData(key, supplier, 10_000)

        // The cache still holds the entry that this request read, so the slow refresh writes its result.
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
    }

    @Test
    fun aLateWriteShouldGoOnWhenTheCacheReadBeforeItFails() {
        val failReads = AtomicInteger()
        val shield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { cacheKey, data, _ ->
                        cache[cacheKey] = data
                        true
                    },
                    // The first read and the recheck succeed. The read before the late write fails.
                    getCacheFunction = { cacheKey ->
                        if (failReads.incrementAndGet() > 2) throw IllegalStateException("cache down")
                        cache[cacheKey]
                    },
                    executor = executor,
                    lockTimeoutMillis = 50,
                ),
            )
        val supplier =
            Callable<Product?> {
                Thread.sleep(150)
                newProduct
            }

        shield.getAndSetReqShieldData(key, supplier, 10_000)

        assertEquals(3, failReads.get())
        assertEquals(newProduct, cache[key]?.value)
    }
}
