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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One ReqShield whose cache functions and lock functions choose their store from a `ThreadLocal` of the calling
 * thread: one store for each tenant. With a store scope function, requests of two tenants for the same key never
 * wait for each other inside the JVM, and requests of one tenant still collapse.
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
    private val tenant = ThreadLocal<String>()

    // The threads on which the default store scope function ran, one entry for each call
    private val scopeThreads = CopyOnWriteArrayList<Thread>()

    // Runs the background tasks of the shield
    private val background = Executors.newCachedThreadPool()

    // Runs the requests that the test does not wait for at once
    private val callers = Executors.newCachedThreadPool()

    /**
     * The cache writes and some lock releases run on the executor. This one copies the tenant of the thread that
     * submits a task into that task, as an application with a store for each tenant must do.
     */
    private val tenantExecutor =
        Executor { task ->
            val submitter = tenant.get()
            background.execute {
                tenant.set(submitter)
                try {
                    task.run()
                } finally {
                    tenant.remove()
                }
            }
        }

    @AfterEach
    fun tearDown() {
        callers.shutdownNow()
        background.shutdownNow()
        assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS))
        assertTrue(background.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun store(): Store =
        when (val current = tenant.get()) {
            "A" -> storeA
            "B" -> storeB
            else -> error("No tenant on thread ${Thread.currentThread().name}: $current")
        }

    private fun shield(
        isLocalLock: Boolean = false,
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        storeScopeFunction: () -> String = {
            scopeThreads.add(Thread.currentThread())
            checkNotNull(tenant.get())
        },
    ): ReqShield<String> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    store().cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey -> store().cache[cacheKey] },
                globalLockFunction = { lockKey, token, _ ->
                    val store = store()
                    store.lockCalls.incrementAndGet()
                    store.locks.putIfAbsent(lockKey, token) == null
                },
                globalUnLockFunction = { lockKey, token -> store().locks.remove(lockKey, token) },
                isLocalLock = isLocalLock,
                executor = tenantExecutor,
                reqShieldWorkMode = workMode,
                storeScopeFunction = storeScopeFunction,
            ),
        )

    /** A request of [tenantName] on the current thread. */
    private fun call(
        shield: ReqShield<String>,
        tenantName: String,
        supplier: () -> String?,
    ): ReqShieldData<String> {
        tenant.set(tenantName)
        try {
            return shield.getAndSetReqShieldData(key, Callable { supplier() }, 10_000)
        } finally {
            tenant.remove()
        }
    }

    /** A request of [tenantName] on a thread of its own. */
    private fun callAsync(
        shield: ReqShield<String>,
        tenantName: String,
        supplier: () -> String?,
    ): CompletableFuture<ReqShieldData<String>> = CompletableFuture.supplyAsync({ call(shield, tenantName, supplier) }, callers)

    /** A supplier that ends when [finish] completes. [calls] counts its calls. */
    private fun blocked(
        finish: CompletableFuture<String?>,
        calls: AtomicInteger,
    ): () -> String? =
        {
            calls.incrementAndGet()
            finish.get()
        }

    private fun counting(
        calls: AtomicInteger,
        value: String,
    ): () -> String? =
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

    @Test
    fun aRequestShouldNotWaitForACreationOfAnotherStore() {
        val shield = shield()
        val finishA = CompletableFuture<String?>()
        val aCalls = AtomicInteger()
        val a = callAsync(shield, "A", blocked(finishA, aCalls))
        try {
            awaitCalls(aCalls, 1)
            val bCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val b = call(shield, "B", counting(bCalls, "b"))

            val millis = millisSince(startedAtNanos)
            assertEquals("b", b.value)
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
        val first = callAsync(shield, "A", blocked(finishA, calls))
        awaitCalls(calls, 1)

        val second = callAsync(shield, "A", counting(calls, "second"))
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
            assertEquals("old", call(shield, "A", blocked(finishA, aCalls)).value)
            awaitCalls(aCalls, 1)
            val bCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val b = call(shield, "B", counting(bCalls, "b"))

            val millis = millisSince(startedAtNanos)
            assertEquals("b", b.value)
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
            // The refresh of tenant A holds its lock and its signal when its request returns. Tenant B asks at once:
            // the refresh attempt guard of a global lock also holds a key for 50 ms only.
            assertEquals("old", call(shield, "A", blocked(finishA, aCalls)).value)
            val bCalls = AtomicInteger()

            assertEquals("old", call(shield, "B", counting(bCalls, "b")).value)

            // The refresh of tenant B runs while the refresh of tenant A still runs
            awaitCalls(aCalls, 1)
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
        assertEquals("v", call(shield, "A", counting(calls, "v")).value)
        assertEquals(1, scopeThreads.size)
        await().atMost(Duration.ofSeconds(2)).until { storeA.cache[key] != null && storeA.locks.isEmpty() }

        // A fresh entry
        repeat(5) { assertEquals("v", call(shield, "A", counting(calls, "other")).value) }
        assertEquals(1, scopeThreads.size)

        // An old entry: the request starts a refresh
        storeA.cache[key] = oldEntry()
        assertEquals("old", call(shield, "A", counting(calls, "new")).value)
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals("new", storeA.cache[key]?.value) }
        assertEquals(2, scopeThreads.size)
        // It ran on the caller's thread, where the ThreadLocal of the caller is set
        assertTrue(scopeThreads.all { it == Thread.currentThread() }, "the scope function ran on $scopeThreads")
    }

    @Test
    fun aFailingStoreScopeFunctionShouldFailARequestWithoutAnEntry() {
        val shield = shield(storeScopeFunction = { error("no tenant") })
        val calls = AtomicInteger()

        val error = assertThrows(ClientException::class.java) { call(shield, "A", counting(calls, "v")) }

        assertEquals(ErrorCode.STORE_SCOPE_ERROR, error.errorCode)
        assertEquals(0, calls.get())
        assertEquals(0, storeA.lockCalls.get())
    }

    @Test
    fun aFailingStoreScopeFunctionShouldOnlySkipTheRefreshOfAnOldEntry() {
        val shield = shield(storeScopeFunction = { error("no tenant") })
        storeA.cache[key] = oldEntry()
        val calls = AtomicInteger()

        assertEquals("old", call(shield, "A", counting(calls, "new")).value)

        Thread.sleep(200)
        assertEquals(0, calls.get())
        assertEquals(0, storeA.lockCalls.get())
        assertEquals("old", storeA.cache[key]?.value)
    }
}
