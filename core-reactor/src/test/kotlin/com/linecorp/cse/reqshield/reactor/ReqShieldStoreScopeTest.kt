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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One ReqShield whose cache functions and lock functions choose their store from the Reactor Context: one store for
 * each tenant. With a store scope function, requests of two tenants for the same key never wait for each other
 * inside the JVM, and requests of one tenant still collapse.
 */
class ReqShieldStoreScopeTest {
    private class Store {
        val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
        val locks = ConcurrentHashMap<String, String>()
        val lockCalls = AtomicInteger()
    }

    private val key = "store-scope-${UUID.randomUUID()}"
    private val storeA = Store()
    private val storeB = Store()
    private val timeout = Duration.ofSeconds(5)
    private val scopeCalls = AtomicInteger()

    private fun <R : Any> inStore(action: (Store) -> R?): Mono<R> =
        Mono.deferContextual { context -> Mono.fromCallable { action(storeOf(context)) } }

    private fun storeOf(context: ContextView): Store = if (context.get<String>(TENANT) == "A") storeA else storeB

    private fun shield(
        isLocalLock: Boolean = false,
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        storeScopeFunction: (ContextView) -> String = { context ->
            scopeCalls.incrementAndGet()
            context.get(TENANT)
        },
    ): ReqShield<String> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    inStore { store ->
                        store.cache[cacheKey] = data
                        true
                    }
                },
                getCacheFunction = { cacheKey ->
                    Mono.deferContextual { context -> Mono.fromCallable<ReqShieldData<String>?> { storeOf(context).cache[cacheKey] } }
                },
                globalLockFunction = { lockKey, token, _ ->
                    inStore { store ->
                        store.lockCalls.incrementAndGet()
                        store.locks.putIfAbsent(lockKey, token) == null
                    }
                },
                globalUnLockFunction = { lockKey, token -> inStore { store -> store.locks.remove(lockKey, token) } },
                isLocalLock = isLocalLock,
                reqShieldWorkMode = workMode,
                storeScopeFunction = storeScopeFunction,
            ),
        )

    private fun call(
        shield: ReqShield<String>,
        tenant: String,
        supplier: () -> Mono<String?>,
    ): Mono<ReqShieldData<String>> =
        shield.getAndSetReqShieldData(key, Callable { supplier() }, 10_000).contextWrite(Context.of(TENANT, tenant))

    /** A supplier that ends when [finish] completes. [calls] counts its calls. */
    private fun blocked(
        finish: CompletableFuture<String?>,
        calls: AtomicInteger,
    ): () -> Mono<String?> =
        {
            calls.incrementAndGet()
            Mono.fromFuture(finish)
        }

    private fun counting(
        calls: AtomicInteger,
        value: String,
    ): () -> Mono<String?> =
        {
            calls.incrementAndGet()
            Mono.just(value)
        }

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<String> =
        ReqShieldData(
            "old",
            ReqShieldData.Status.NEW,
            System.currentTimeMillis() - 9_000,
            10_000,
        )

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun awaitCalls(
        calls: AtomicInteger,
        count: Int,
    ) = await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { calls.get() == count }

    @Test
    fun aRequestShouldNotWaitForACreationOfAnotherStore() {
        val shield = shield()
        val finishA = CompletableFuture<String?>()
        val aCalls = AtomicInteger()
        val a = call(shield, "A", blocked(finishA, aCalls)).toFuture()
        try {
            awaitCalls(aCalls, 1)
            val bCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val b = call(shield, "B", counting(bCalls, "b")).block(timeout)

            val millis = millisSince(startedAtNanos)
            assertEquals("b", b?.value)
            assertTrue(millis < 1_000, "the request of tenant B took $millis ms")
            assertEquals(1, bCalls.get())
            // Tenant B asked its own lock store and wrote its own entry
            assertEquals(1, storeB.lockCalls.get())
            await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("b", storeB.cache[key]?.value) }
        } finally {
            finishA.complete("a")
        }
        assertEquals("a", a.get(2, TimeUnit.SECONDS).value)
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("a", storeA.cache[key]?.value) }
        await().atMost(Duration.ofSeconds(2)).until { storeA.locks.isEmpty() && storeB.locks.isEmpty() }
    }

    @Test
    fun requestsOfTheSameStoreShouldStillCollapse() {
        val shield = shield()
        val finishA = CompletableFuture<String?>()
        val calls = AtomicInteger()
        val first = call(shield, "A", blocked(finishA, calls)).toFuture()
        awaitCalls(calls, 1)

        val second = call(shield, "A", counting(calls, "second")).toFuture()
        Thread.sleep(200)

        // The second request waits for the first one inside the JVM: no supplier call and no call of the lock store
        assertFalse(second.isDone)
        assertEquals(1, calls.get())
        assertEquals(1, storeA.lockCalls.get())
        finishA.complete("first")
        assertEquals("first", first.get(2, TimeUnit.SECONDS).value)
        assertEquals("first", second.get(2, TimeUnit.SECONDS).value)
        assertEquals(1, calls.get())
    }

    private fun aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStore(isLocalLock: Boolean) {
        val shield = shield(isLocalLock = isLocalLock)
        storeA.cache[key] = oldEntry()
        val finishA = CompletableFuture<String?>()
        val aCalls = AtomicInteger()
        try {
            assertEquals("old", call(shield, "A", blocked(finishA, aCalls)).block(timeout)?.value)
            awaitCalls(aCalls, 1)
            val bCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val b = call(shield, "B", counting(bCalls, "b")).block(timeout)

            val millis = millisSince(startedAtNanos)
            assertEquals("b", b?.value)
            assertTrue(millis < 500, "the request of tenant B took $millis ms")
            assertEquals(1, bCalls.get())
            await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("b", storeB.cache[key]?.value) }
        } finally {
            finishA.complete("a")
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("a", storeA.cache[key]?.value) }
    }

    @Test
    fun aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStoreWithAGlobalLock() =
        aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStore(isLocalLock = false)

    @Test
    fun aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStoreWithALocalLock() =
        aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStore(isLocalLock = true)

    private fun aRefreshShouldStartDuringARefreshOfAnotherStore(workMode: ReqShieldWorkMode) {
        val shield = shield(workMode = workMode)
        storeA.cache[key] = oldEntry()
        storeB.cache[key] = oldEntry()
        val finishA = CompletableFuture<String?>()
        val aCalls = AtomicInteger()
        try {
            assertEquals("old", call(shield, "A", blocked(finishA, aCalls)).block(timeout)?.value)
            awaitCalls(aCalls, 1)
            val bCalls = AtomicInteger()

            assertEquals("old", call(shield, "B", counting(bCalls, "b")).block(timeout)?.value)

            // The refresh of tenant B runs while the refresh of tenant A still runs
            await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("b", storeB.cache[key]?.value) }
            assertEquals(1, bCalls.get())
            assertEquals("old", storeA.cache[key]?.value)
        } finally {
            finishA.complete("a")
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("a", storeA.cache[key]?.value) }
    }

    @Test
    fun aRefreshShouldStartDuringARefreshOfAnotherStore() =
        aRefreshShouldStartDuringARefreshOfAnotherStore(ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE)

    @Test
    fun aRefreshShouldStartDuringARefreshOfAnotherStoreInOnlyCreateCacheMode() =
        aRefreshShouldStartDuringARefreshOfAnotherStore(ReqShieldWorkMode.ONLY_CREATE_CACHE)

    @Test
    fun theStoreScopeFunctionShouldRunOnceForARequestAndNotForAFreshEntry() {
        val shield = shield()
        val calls = AtomicInteger()

        // A miss
        assertEquals("v", call(shield, "A", counting(calls, "v")).block(timeout)?.value)
        assertEquals(1, scopeCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { storeA.cache[key] != null && storeA.locks.isEmpty() }

        // A fresh entry
        repeat(5) { assertEquals("v", call(shield, "A", counting(calls, "other")).block(timeout)?.value) }
        assertEquals(1, scopeCalls.get())

        // An old entry: the request starts a refresh
        storeA.cache[key] = oldEntry()
        assertEquals("old", call(shield, "A", counting(calls, "new")).block(timeout)?.value)
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("new", storeA.cache[key]?.value) }
        assertEquals(2, scopeCalls.get())
    }

    @Test
    fun aFailingStoreScopeFunctionShouldFailARequestWithoutAnEntry() {
        val shield = shield(storeScopeFunction = { error("no tenant") })
        val calls = AtomicInteger()

        val error = assertThrows(ClientException::class.java) { call(shield, "A", counting(calls, "v")).block(timeout) }

        assertEquals(ErrorCode.STORE_SCOPE_ERROR, error.errorCode)
        assertEquals(0, calls.get())
        assertEquals(0, storeA.lockCalls.get())
    }

    @Test
    fun aFailingStoreScopeFunctionShouldOnlySkipTheRefreshOfAnOldEntry() {
        val shield = shield(storeScopeFunction = { error("no tenant") })
        storeA.cache[key] = oldEntry()
        val calls = AtomicInteger()

        assertEquals("old", call(shield, "A", counting(calls, "new")).block(timeout)?.value)

        Thread.sleep(200)
        assertEquals(0, calls.get())
        assertEquals(0, storeA.lockCalls.get())
        assertEquals("old", storeA.cache[key]?.value)
    }

    private companion object {
        const val TENANT = "tenant"
    }
}
