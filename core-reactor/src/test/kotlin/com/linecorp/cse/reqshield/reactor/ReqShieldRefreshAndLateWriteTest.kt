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

package com.linecorp.cse.reqshield.reactor

import com.linecorp.cse.reqshield.reactor.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.reactor.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The refresh guard of ONLY_CREATE_CACHE and the late cache write after a lock timeout. The tests use a map as the cache, so each assertion reads the entry that a request would get.
 */
class ReqShieldRefreshAndLateWriteTest {
    private val key = "issue-61-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()
    private val oldProduct = Product("old", "old")
    private val newProduct = Product("new", "new")
    private val otherProduct = Product("other", "other")
    private val timeout = Duration.ofSeconds(5)

    private fun shield(
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        lockTimeoutMillis: Long = 3000,
        failReadsAfter: Int = Int.MAX_VALUE,
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    Mono.fromSupplier {
                        cache[cacheKey] = data
                        true
                    }
                },
                getCacheFunction = { cacheKey ->
                    Mono.defer {
                        if (cacheReads.incrementAndGet() > failReadsAfter) {
                            Mono.error(IllegalStateException("cache down"))
                        } else {
                            Mono.justOrEmpty(cache[cacheKey])
                        }
                    }
                },
                lockTimeoutMillis = lockTimeoutMillis,
                reqShieldWorkMode = workMode,
            ),
        )

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    /** A supplier that needs [millis] and then runs [beforeResult], for example the write of another request. */
    private fun slowSupplier(
        millis: Long,
        beforeResult: () -> Unit = {},
    ): Callable<Mono<Product?>> =
        Callable {
            Mono.delay(Duration.ofMillis(millis)).map {
                beforeResult()
                newProduct
            }
        }

    private fun awaitCachedValue(expected: Product) =
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(expected, cache[key]?.value) }

    @Test
    fun onlyCreateCacheShouldRunOneRefreshOfAKeyAtATime() {
        cache[key] = oldEntry()
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Mono<Product?>> {
                supplierCalls.incrementAndGet()
                Mono.delay(Duration.ofMillis(500)).map { newProduct }
            }
        val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

        repeat(50) {
            assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).block(timeout)?.value)
        }
        // Give a second refresh the time to call the supplier, if one was subscribed.
        Thread.sleep(200)

        assertEquals(1, supplierCalls.get())
        awaitCachedValue(newProduct)
    }

    @Test
    fun onlyCreateCacheShouldRefreshAgainAfterARefreshFailed() {
        cache[key] = oldEntry()
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Mono<Product?>> {
                if (supplierCalls.incrementAndGet() == 1) Mono.error(IllegalStateException("origin down")) else Mono.just(newProduct)
            }
        val shield = shield(ReqShieldWorkMode.ONLY_CREATE_CACHE)

        shield.getAndSetReqShieldData(key, supplier, 10_000).block(timeout)
        await().atMost(Duration.ofSeconds(2)).until { supplierCalls.get() == 1 }

        // The failed refresh released its guard, so a later request refreshes the entry.
        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            shield.getAndSetReqShieldData(key, supplier, 10_000).block(timeout)
            assertEquals(newProduct, cache[key]?.value)
        }
    }

    @Test
    fun aNewEntryShouldNotReplaceAnEntryThatAnotherRequestWroteAfterTheLockTimedOut() {
        val shield = shield(lockTimeoutMillis = 50)
        val other = ReqShieldData(otherProduct, 10_000)

        // The supplier is slower than the lock timeout, and another request writes the entry meanwhile.
        val result = shield.getAndSetReqShieldData(key, slowSupplier(150) { cache[key] = other }, 10_000).block(timeout)

        assertEquals(newProduct, result?.value, "the caller still gets the result of its own supplier call")
        // The write runs after the value is emitted. If it ran, it would be there well before 300 ms.
        Thread.sleep(300)
        assertEquals(other, cache[key], "the entry that the other request wrote must stay")
    }

    @Test
    fun aNewEntryShouldBeWrittenAfterALockTimeoutWhenNoOtherRequestWroteOne() {
        val shield = shield(lockTimeoutMillis = 50)

        shield.getAndSetReqShieldData(key, slowSupplier(150), 10_000).block(timeout)

        awaitCachedValue(newProduct)
    }

    @Test
    fun aNewEntryShouldBeWrittenWithoutAnExtraCacheReadWhileTheLockIsValid() {
        val shield = shield(lockTimeoutMillis = 3000)

        shield.getAndSetReqShieldData(key, { Mono.just(newProduct) }, 10_000).block(timeout)

        awaitCachedValue(newProduct)
        assertEquals(2, cacheReads.get(), "only the first read and the recheck after the lock")
    }

    @Test
    fun aRefreshShouldNotReplaceAnEntryThatAnotherRequestWroteAfterTheLockTimedOut() {
        val shield = shield(lockTimeoutMillis = 50)
        cache[key] = oldEntry()
        val other = ReqShieldData(otherProduct, 10_000)

        shield.getAndSetReqShieldData(key, slowSupplier(150) { cache[key] = other }, 10_000).block(timeout)

        // The refresh ends about 150 ms after the call. Its write, if it ran, would be there well before 500 ms.
        Thread.sleep(500)
        assertEquals(other, cache[key])
    }

    @Test
    fun aRefreshShouldReplaceTheEntryItReadAfterTheLockTimedOut() {
        val shield = shield(lockTimeoutMillis = 50)
        cache[key] = oldEntry()

        shield.getAndSetReqShieldData(key, slowSupplier(150), 10_000).block(timeout)

        // The cache still holds the entry that this request read, so the slow refresh writes its result.
        awaitCachedValue(newProduct)
    }

    @Test
    fun aLateWriteShouldGoOnWhenTheCacheReadBeforeItFails() {
        // The first read and the recheck succeed. The read before the late write fails.
        val shield = shield(lockTimeoutMillis = 50, failReadsAfter = 2)

        shield.getAndSetReqShieldData(key, slowSupplier(150), 10_000).block(timeout)

        awaitCachedValue(newProduct)
        assertEquals(3, cacheReads.get())
    }
}
