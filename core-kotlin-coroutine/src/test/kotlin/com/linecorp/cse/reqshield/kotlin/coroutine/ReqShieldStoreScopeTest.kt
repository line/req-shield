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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith

/**
 * One ReqShield whose cache functions and lock functions choose their store from a coroutine context element of the
 * caller: one store for each tenant. With a store scope function, requests of two tenants for the same key never wait
 * for each other inside the JVM, and requests of one tenant still collapse.
 *
 * The cache write of a creation runs in the configured scope without the context elements of the caller. This is
 * as in version 1.2.0; only a refresh gets them. So the set function finds no tenant for a creation and fails. These
 * tests assert the written entry only for a refresh. For a creation they assert what needs no write: the request
 * time, the lock store calls and the supplier calls.
 */
class ReqShieldStoreScopeTest {
    private class Tenant(
        val id: String,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tenant>
    }

    private class Store {
        val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
        val locks = ConcurrentHashMap<String, String>()
        val lockCalls = AtomicInteger()
    }

    private val key = "store-scope-${UUID.randomUUID()}"
    private val storeA = Store()
    private val storeB = Store()
    private val timeoutMillis = 5_000L
    private val scopeCalls = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The requests run on threads of their own, so a blocking wait of the test does not stop them. A request that
    // fails does not cancel the test.
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        callers.cancel()
        scope.cancel()
    }

    private fun storeOf(context: CoroutineContext): Store =
        when (context[Tenant]?.id) {
            "A" -> storeA
            "B" -> storeB
            else -> error("no tenant in the coroutine context")
        }

    private fun shield(
        isLocalLock: Boolean = false,
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        storeScopeFunction: (CoroutineContext) -> String = { context ->
            scopeCalls.incrementAndGet()
            checkNotNull(context[Tenant]).id
        },
    ): ReqShield<String> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    storeOf(currentCoroutineContext()).cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey -> storeOf(currentCoroutineContext()).cache[cacheKey] },
                globalLockFunction = { lockKey, token, _ ->
                    val store = storeOf(currentCoroutineContext())
                    store.lockCalls.incrementAndGet()
                    store.locks.putIfAbsent(lockKey, token) == null
                },
                // The release of a creation runs without the tenant too. A token is unique, so the unlock finds its
                // lock in either store without the tenant.
                globalUnLockFunction = { lockKey, token -> storeA.locks.remove(lockKey, token) || storeB.locks.remove(lockKey, token) },
                isLocalLock = isLocalLock,
                reqShieldWorkMode = workMode,
                scope = scope,
                storeScopeFunction = storeScopeFunction,
            ),
        )

    private fun call(
        shield: ReqShield<String>,
        tenant: String,
        supplier: suspend () -> String?,
    ): Deferred<ReqShieldData<String>> = callers.async(Tenant(tenant)) { shield.getAndSetReqShieldData(key, supplier, 10_000) }

    /** A supplier that ends when [finish] completes. [calls] counts its calls. */
    private fun blocked(
        finish: CompletableDeferred<String?>,
        calls: AtomicInteger,
    ): suspend () -> String? =
        {
            calls.incrementAndGet()
            finish.await()
        }

    private fun counting(
        calls: AtomicInteger,
        value: String,
    ): suspend () -> String? =
        {
            calls.incrementAndGet()
            value
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

    private fun awaitEntry(
        store: Store,
        value: String,
    ) = await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(value, store.cache[key]?.value) }

    @Test
    fun aRequestShouldNotWaitForACreationOfAnotherStore() =
        runBlocking<Unit> {
            val shield = shield()
            val finishA = CompletableDeferred<String?>()
            val aCalls = AtomicInteger()
            val a = call(shield, "A", blocked(finishA, aCalls))
            try {
                awaitCalls(aCalls, 1)
                val bCalls = AtomicInteger()
                val startedAtNanos = System.nanoTime()

                val b = withTimeout(timeoutMillis) { call(shield, "B", counting(bCalls, "b")).await() }

                val millis = millisSince(startedAtNanos)
                assertEquals("b", b.value)
                assertTrue(millis < 1_000, "the request of tenant B took $millis ms")
                assertEquals(1, bCalls.get())
                // Tenant B asked its own lock store
                assertEquals(1, storeB.lockCalls.get())
            } finally {
                finishA.complete("a")
            }
            assertEquals("a", withTimeout(2_000) { a.await() }.value)
            await().atMost(Duration.ofSeconds(2)).until { storeA.locks.isEmpty() && storeB.locks.isEmpty() }
        }

    @Test
    fun requestsOfTheSameStoreShouldStillCollapse() =
        runBlocking<Unit> {
            val shield = shield()
            val finishA = CompletableDeferred<String?>()
            val calls = AtomicInteger()
            val first = call(shield, "A", blocked(finishA, calls))
            awaitCalls(calls, 1)

            val second = call(shield, "A", counting(calls, "second"))
            Thread.sleep(200)

            // The second request waits for the first one inside the JVM: no supplier call and no call of the lock store
            assertFalse(second.isCompleted)
            assertEquals(1, calls.get())
            assertEquals(1, storeA.lockCalls.get())
            finishA.complete("first")
            assertEquals("first", withTimeout(2_000) { first.await() }.value)
            // The first request writes no entry (see the class comment), so the second one may call its supplier
            // after the release. Only its end is asserted here.
            withTimeout(2_000) { second.await() }
        }

    private fun aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStore(isLocalLock: Boolean) =
        runBlocking<Unit> {
            val shield = shield(isLocalLock = isLocalLock)
            storeA.cache[key] = oldEntry()
            val finishA = CompletableDeferred<String?>()
            val aCalls = AtomicInteger()
            try {
                assertEquals("old", withTimeout(timeoutMillis) { call(shield, "A", blocked(finishA, aCalls)).await() }.value)
                awaitCalls(aCalls, 1)
                val bCalls = AtomicInteger()
                val startedAtNanos = System.nanoTime()

                val b = withTimeout(timeoutMillis) { call(shield, "B", counting(bCalls, "b")).await() }

                val millis = millisSince(startedAtNanos)
                assertEquals("b", b.value)
                assertTrue(millis < 500, "the request of tenant B took $millis ms")
                assertEquals(1, bCalls.get())
            } finally {
                finishA.complete("a")
            }
            // The refresh of tenant A gets the context elements of its caller, so it writes to store A
            awaitEntry(storeA, "a")
        }

    @Test
    fun aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStoreWithAGlobalLock() =
        aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStore(isLocalLock = false)

    @Test
    fun aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStoreWithALocalLock() =
        aRequestWithoutAnEntryShouldNotWaitForARefreshOfAnotherStore(isLocalLock = true)

    private fun aRefreshShouldStartDuringARefreshOfAnotherStore(workMode: ReqShieldWorkMode) =
        runBlocking<Unit> {
            val shield = shield(workMode = workMode)
            storeA.cache[key] = oldEntry()
            storeB.cache[key] = oldEntry()
            val finishA = CompletableDeferred<String?>()
            val aCalls = AtomicInteger()
            try {
                assertEquals("old", withTimeout(timeoutMillis) { call(shield, "A", blocked(finishA, aCalls)).await() }.value)
                awaitCalls(aCalls, 1)
                val bCalls = AtomicInteger()

                assertEquals("old", withTimeout(timeoutMillis) { call(shield, "B", counting(bCalls, "b")).await() }.value)

                // The refresh of tenant B runs while the refresh of tenant A still runs
                awaitEntry(storeB, "b")
                assertEquals(1, bCalls.get())
                assertEquals("old", storeA.cache[key]?.value)
            } finally {
                finishA.complete("a")
            }
            awaitEntry(storeA, "a")
        }

    @Test
    fun aRefreshShouldStartDuringARefreshOfAnotherStore() =
        aRefreshShouldStartDuringARefreshOfAnotherStore(ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE)

    @Test
    fun aRefreshShouldStartDuringARefreshOfAnotherStoreInOnlyCreateCacheMode() =
        aRefreshShouldStartDuringARefreshOfAnotherStore(ReqShieldWorkMode.ONLY_CREATE_CACHE)

    @Test
    fun theStoreScopeFunctionShouldRunOnceForARequestAndNotForAFreshEntry() =
        runBlocking<Unit> {
            val shield = shield()
            val calls = AtomicInteger()

            // A miss
            assertEquals("v", withTimeout(timeoutMillis) { call(shield, "A", counting(calls, "v")).await() }.value)
            assertEquals(1, scopeCalls.get())
            await().atMost(Duration.ofSeconds(2)).until { storeA.locks.isEmpty() }

            // A fresh entry. The miss above writes none (see the class comment), so the test writes it.
            storeA.cache[key] = ReqShieldData("v", ReqShieldData.Status.NEW, System.currentTimeMillis(), 10_000)
            repeat(5) { assertEquals("v", withTimeout(timeoutMillis) { call(shield, "A", counting(calls, "other")).await() }.value) }
            assertEquals(1, scopeCalls.get())

            // An old entry: the request starts a refresh
            storeA.cache[key] = oldEntry()
            assertEquals("old", withTimeout(timeoutMillis) { call(shield, "A", counting(calls, "new")).await() }.value)
            awaitEntry(storeA, "new")
            assertEquals(2, scopeCalls.get())
        }

    @Test
    fun aFailingStoreScopeFunctionShouldFailARequestWithoutAnEntry() =
        runBlocking<Unit> {
            val shield = shield(storeScopeFunction = { error("no tenant") })
            val calls = AtomicInteger()

            val error =
                assertFailsWith<ClientException> {
                    withTimeout(timeoutMillis) { call(shield, "A", counting(calls, "v")).await() }
                }

            assertEquals(ErrorCode.STORE_SCOPE_ERROR, error.errorCode)
            assertEquals(0, calls.get())
            assertEquals(0, storeA.lockCalls.get())
        }

    @Test
    fun aFailingStoreScopeFunctionShouldOnlySkipTheRefreshOfAnOldEntry() =
        runBlocking<Unit> {
            val shield = shield(storeScopeFunction = { error("no tenant") })
            storeA.cache[key] = oldEntry()
            val calls = AtomicInteger()

            assertEquals("old", withTimeout(timeoutMillis) { call(shield, "A", counting(calls, "new")).await() }.value)

            Thread.sleep(200)
            assertEquals(0, calls.get())
            assertEquals(0, storeA.lockCalls.get())
            assertEquals("old", storeA.cache[key]?.value)
        }
}
