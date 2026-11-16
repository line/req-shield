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
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
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
    private val timeout = Duration.ofSeconds(5)

    private fun shield(
        storeGivesTheLock: Boolean,
        lockTimeoutMillis: Long = 3000,
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    Mono.fromSupplier {
                        cache[cacheKey] = data
                        true
                    }
                },
                getCacheFunction = { cacheKey -> Mono.justOrEmpty(cache[cacheKey]) },
                globalLockFunction = { _, _, _ ->
                    Mono.fromSupplier {
                        lockCalls.incrementAndGet()
                        storeGivesTheLock
                    }
                },
                globalUnLockFunction = { _, _ -> Mono.just(true) },
                isLocalLock = false,
                lockTimeoutMillis = lockTimeoutMillis,
            ),
        )

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    private fun read(
        shield: ReqShield<Product>,
        supplier: Callable<Mono<Product?>>,
    ): Product? = shield.getAndSetReqShieldData(key, supplier, 10_000).block(timeout)?.value

    @Test
    fun readsOfAnOldEntryShouldAskTheStoreOnceInAnIntervalWhileAnotherJvmHoldsTheLock() {
        cache[key] = oldEntry()
        // The store answers no: a request of another JVM runs the refresh
        val shield = shield(storeGivesTheLock = false)
        val supplier = Callable<Mono<Product?>> { Mono.just(newProduct) }

        repeat(50) { assertEquals(oldProduct, read(shield, supplier)) }

        // The lock call runs on the scheduler, so it can come a moment after the reads
        await().atMost(Duration.ofSeconds(2)).until { lockCalls.get() >= 1 }
        // A call for each read would show here, after its task ran
        Thread.sleep(30)
        assertEquals(1, lockCalls.get())

        // After the interval the next read asks again
        Thread.sleep(80)
        read(shield, supplier)

        await().atMost(Duration.ofSeconds(2)).until { lockCalls.get() >= 2 }
        Thread.sleep(30)
        assertEquals(2, lockCalls.get())
    }

    @Test
    fun readsDuringARefreshOfThisJvmShouldNotAskTheStore() {
        cache[key] = oldEntry()
        val finishSupplier = CompletableFuture<Product?>()
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Mono<Product?>> {
                Mono.defer {
                    supplierCalls.incrementAndGet()
                    Mono.fromFuture(finishSupplier)
                }
            }
        val shield = shield(storeGivesTheLock = true)

        try {
            read(shield, supplier)
            await().atMost(Duration.ofSeconds(2)).until { supplierCalls.get() == 1 }
            // Longer than the interval, so only the refresh of this JVM keeps the reads from the store
            Thread.sleep(80)

            repeat(20) { assertEquals(oldProduct, read(shield, supplier)) }
            Thread.sleep(30)

            assertEquals(1, lockCalls.get())
            assertEquals(1, supplierCalls.get())
        } finally {
            finishSupplier.complete(newProduct)
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
    }

    @Test
    fun aReadShouldAskTheStoreAgainAfterTheLockOfARefreshOfThisJvmTimedOut() {
        cache[key] = oldEntry()
        val finishSupplier = CompletableFuture<Product?>()
        val slowCalls = AtomicInteger()
        // The supplier of the first refresh is slower than the lock timeout
        val slowSupplier =
            Callable<Mono<Product?>> {
                Mono.defer {
                    slowCalls.incrementAndGet()
                    Mono.fromFuture(finishSupplier)
                }
            }
        val shield = shield(storeGivesTheLock = true, lockTimeoutMillis = 200)

        try {
            read(shield, slowSupplier)
            await().atMost(Duration.ofSeconds(2)).until { slowCalls.get() == 1 }
            assertEquals(1, lockCalls.get())
            // The lock of the first refresh timed out, so the store gives it to the next request
            Thread.sleep(300)

            assertEquals(oldProduct, read(shield) { Mono.just(newProduct) })

            await().atMost(Duration.ofSeconds(2)).until { lockCalls.get() == 2 }
            await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
        } finally {
            finishSupplier.complete(oldProduct)
        }
    }
}
