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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A lock store chosen by the caller's Reactor Context (one store per tenant) must get the release of a lock it
 * granted. A release subscribed without that Context goes to the default store and leaves the lock in place.
 */
class ReqShieldLockContextTest {
    private val key = "testKey"
    private val timeToLiveMillis = 10_000L
    private val tenant = "alice"

    // Lock stores by tenant: lock key -> token of the holder
    private val lockStores = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val acquiredByTenant = ConcurrentHashMap<String, AtomicInteger>()
    private val setCacheCalls = AtomicInteger()

    private fun lockStoreOf(tenantName: String) = lockStores.computeIfAbsent(tenantName) { ConcurrentHashMap() }

    private fun acquiredBy(tenantName: String) = acquiredByTenant.computeIfAbsent(tenantName) { AtomicInteger() }

    private fun <R : Any> withTenant(action: (String) -> R): Mono<R> =
        Mono.deferContextual { context -> Mono.fromSupplier { action(context.getOrDefault("tenant", "default")!!) } }

    private val keyLock =
        KeyGlobalLock(
            globalLockFunction = { lockKey, token, _ ->
                withTenant { tenantName ->
                    val acquired = lockStoreOf(tenantName).putIfAbsent(lockKey, token) == null
                    if (acquired) acquiredBy(tenantName).incrementAndGet()
                    acquired
                }
            },
            // Compare and delete: only the token that took the lock removes it
            globalUnLockFunction = { lockKey, token -> withTenant { tenantName -> lockStoreOf(tenantName).remove(lockKey, token) } },
            lockTimeoutMillis = 3_000,
        )

    private fun shield(
        cached: ReqShieldData<Product>?,
        setCacheFunction: (String, ReqShieldData<Product>, Long) -> Mono<Boolean> = { _, _, _ -> Mono.just(true) },
    ) = ReqShield(
        ReqShieldConfiguration(
            setCacheFunction = { cacheKey, data, ttl ->
                setCacheCalls.incrementAndGet()
                setCacheFunction(cacheKey, data, ttl)
            },
            getCacheFunction = { Mono.justOrEmpty(cached) },
            keyLock = keyLock,
        ),
    )

    private fun entryDueForRefresh() =
        ReqShieldData(
            Product("old", "old"),
            ReqShieldData.Status.NEW,
            nowToEpochTime() - (timeToLiveMillis * 0.9).toLong(),
            timeToLiveMillis,
        )

    private fun supplierOf(result: Mono<Product?>) = Callable { result }

    private fun ReqShield<Product>.callAsTenant(callable: Callable<Mono<Product?>>) =
        getAndSetReqShieldData(key, callable, timeToLiveMillis).contextWrite { it.put("tenant", tenant) }

    private fun assertTheTenantLockWasTakenAndReleased() {
        await().atMost(Duration.ofSeconds(5)).until { acquiredBy(tenant).get() == 1 }
        await().atMost(Duration.ofSeconds(5)).until { lockStoreOf(tenant).isEmpty() }
        // The lock was asked of the tenant's store only
        assertEquals(0, acquiredBy("default").get())
    }

    @Test
    fun shouldReleaseTheRefreshLockInTheStoreOfTheCallerContext() {
        val cached = entryDueForRefresh()

        StepVerifier
            .create(shield(cached).callAsTenant(supplierOf(Mono.just(Product("new", "new")))))
            .expectNext(cached)
            .verifyComplete()

        await().atMost(Duration.ofSeconds(5)).until { setCacheCalls.get() == 1 }
        assertTheTenantLockWasTakenAndReleased()
    }

    @Test
    fun shouldReleaseTheRefreshLockInTheStoreOfTheCallerContextWhenTheSupplierFails() {
        val cached = entryDueForRefresh()

        StepVerifier
            .create(shield(cached).callAsTenant(supplierOf(Mono.error(IllegalStateException("supplier down")))))
            .expectNext(cached)
            .verifyComplete()

        assertTheTenantLockWasTakenAndReleased()
        assertEquals(0, setCacheCalls.get())
    }

    @Test
    fun shouldReleaseTheRefreshLockInTheStoreOfTheCallerContextWhenTheCacheWriteFails() {
        val cached = entryDueForRefresh()
        val shield = shield(cached) { _, _, _ -> Mono.error(IllegalStateException("cache down")) }

        StepVerifier
            .create(shield.callAsTenant(supplierOf(Mono.just(Product("new", "new")))))
            .expectNext(cached)
            .verifyComplete()

        await().atMost(Duration.ofSeconds(5)).until { setCacheCalls.get() == 1 }
        assertTheTenantLockWasTakenAndReleased()
    }

    @Test
    fun shouldReleaseTheCreationLockInTheStoreOfTheCallerContext() {
        val created = Product("new", "new")

        StepVerifier
            .create(shield(cached = null).callAsTenant(supplierOf(Mono.just(created))))
            .assertNext { assertEquals(created, it.value) }
            .verifyComplete()

        await().atMost(Duration.ofSeconds(5)).until { setCacheCalls.get() == 1 }
        assertTheTenantLockWasTakenAndReleased()
    }

    @Test
    fun shouldReleaseTheCreationLockInTheStoreOfTheCallerContextWhenTheSupplierFails() {
        StepVerifier
            .create(shield(cached = null).callAsTenant(supplierOf(Mono.error(IllegalStateException("supplier down")))))
            .expectError(ClientException::class.java)
            .verify()

        assertTheTenantLockWasTakenAndReleased()
        assertEquals(0, setCacheCalls.get())
    }

    @Test
    fun shouldReleaseTheCreationLockInTheStoreOfTheCallerContextWhenTheRecheckFindsAnEntry() {
        val filledByAnotherRequest = ReqShieldData(Product("other", "other"), timeToLiveMillis)
        val reads = AtomicInteger()
        val shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    // The first read misses; the recheck under the lock finds the entry of another request
                    getCacheFunction = {
                        Mono.defer { if (reads.getAndIncrement() == 0) Mono.empty() else Mono.just(filledByAnotherRequest) }
                    },
                    keyLock = keyLock,
                ),
            )
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Mono<Product?>> {
                supplierCalls.incrementAndGet()
                Mono.empty()
            }

        StepVerifier
            .create(shield.callAsTenant(supplier))
            .expectNext(filledByAnotherRequest)
            .verifyComplete()

        assertTheTenantLockWasTakenAndReleased()
        assertEquals(0, supplierCalls.get())
        assertTrue(lockStoreOf("default").isEmpty())
    }
}
