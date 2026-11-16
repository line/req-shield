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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two ReqShield instances that use the same key for caches and lock stores of their own. With a lock that is not
 * the local lock, a request of one instance must not wait for a request of the other, as in version 1.2.0. The
 * tests use only the public API.
 */
class ReqShieldInstanceIsolationTest {
    private val key = "isolation-${UUID.randomUUID()}"
    private val other = Executors.newSingleThreadExecutor()

    /** Ends the supplier of the other instance. */
    private val endOther = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        endOther.countDown()
        other.shutdownNow()
        assertTrue(other.awaitTermination(5, TimeUnit.SECONDS))
    }

    /** Starts a request of instance [a] and returns when its supplier runs. The supplier ends with [endOther]. */
    private fun holdTheSupplierOf(a: ProbeCall) {
        val inSupplier = CountDownLatch(1)
        other.submit {
            a(key) {
                inSupplier.countDown()
                endOther.await(20, TimeUnit.SECONDS)
                "a"
            }
        }
        assertTrue(inSupplier.await(5, TimeUnit.SECONDS), "the supplier of the other instance did not start")
    }

    @Test
    fun requestsShouldNotWaitForACreationOfAnotherInstanceWithAGlobalLock() {
        val storeB = ProbeStore()
        val a = ProbeShields.shield(ProbeStore(), global = true)
        val b = ProbeShields.shield(storeB, global = true)
        holdTheSupplierOf(a)
        val supplierCalls = AtomicInteger()

        // The supplier of instance a still runs during all of this
        val results =
            ProbeShields.burst(10) {
                b(key) {
                    supplierCalls.incrementAndGet()
                    Thread.sleep(50)
                    "b"
                }
            }

        assertEquals(setOf<String?>("b"), results.map { it.first }.toSet())
        assertEquals(1, supplierCalls.get())
        assertEquals(1L, endOther.count, "the requests returned only after the other instance ended")
        // Far below the time that the supplier of instance a is held
        assertTrue(results.maxOf { it.second } < 2_000, "the slowest request took ${results.maxOf { it.second }} ms")
        Thread.sleep(200)
        assertEquals("b", storeB.cache[key]?.value)
    }

    @Test
    fun aRefreshInOnlyCreateCacheShouldNotWaitForARefreshOfAnotherInstance() {
        val storeA = ProbeStore()
        val storeB = ProbeStore()
        storeA.putOldEntry(key)
        storeB.putOldEntry(key)
        val a = ProbeShields.shield(storeA, global = true, onlyCreate = true)
        val b = ProbeShields.shield(storeB, global = true, onlyCreate = true)
        val aRefreshing = CountDownLatch(1)
        val bRefreshing = CountDownLatch(1)

        assertEquals(
            "old",
            a(key) {
                aRefreshing.countDown()
                endOther.await(20, TimeUnit.SECONDS)
                "a"
            },
        )
        assertTrue(aRefreshing.await(5, TimeUnit.SECONDS), "the refresh of instance a did not start")
        assertEquals(
            "old",
            b(key) {
                bRefreshing.countDown()
                "b"
            },
        )

        assertTrue(bRefreshing.await(5, TimeUnit.SECONDS), "instance b did not refresh while instance a refreshed")
        assertEquals(1L, endOther.count)
    }

    /** Instance a refreshes its old entry and its supplier is held. Instance b has no entry in its own cache. */
    private fun aCreationShouldNotWaitForARefreshOfAnotherInstance(global: Boolean) {
        val storeA = ProbeStore()
        val storeB = ProbeStore()
        storeA.putOldEntry(key)
        val a = ProbeShields.shield(storeA, global)
        val b = ProbeShields.shield(storeB, global)
        val aRefreshing = CountDownLatch(1)
        assertEquals(
            "old",
            a(key) {
                aRefreshing.countDown()
                endOther.await(20, TimeUnit.SECONDS)
                "a"
            },
        )
        assertTrue(aRefreshing.await(5, TimeUnit.SECONDS), "the refresh of instance a did not start")
        val supplierCalls = AtomicInteger()

        val results =
            ProbeShields.burst(5) {
                b(key) {
                    supplierCalls.incrementAndGet()
                    "b"
                }
            }

        assertEquals(setOf<String?>("b"), results.map { it.first }.toSet())
        assertEquals(1, supplierCalls.get())
        assertEquals(1L, endOther.count, "the requests returned only after the refresh of the other instance ended")
        // Far below the 3 s that a wait for the refresh of instance a would take
        assertTrue(results.maxOf { it.second } < 2_000, "the slowest request took ${results.maxOf { it.second }} ms")
    }

    @Test
    fun aCreationShouldNotWaitForARefreshOfAnotherInstanceWithTheLocalLock() =
        aCreationShouldNotWaitForARefreshOfAnotherInstance(
            global = false,
        )

    @Test
    fun aCreationShouldNotWaitForARefreshOfAnotherInstanceWithAGlobalLock() =
        aCreationShouldNotWaitForARefreshOfAnotherInstance(
            global = true,
        )

    @Test
    fun aRefreshShouldNotWaitForARefreshOfAnotherInstanceWithAGlobalLock() {
        val storeA = ProbeStore()
        val storeB = ProbeStore()
        storeA.putOldEntry(key)
        storeB.putOldEntry(key)
        val a = ProbeShields.shield(storeA, global = true)
        val b = ProbeShields.shield(storeB, global = true)
        val aRefreshing = CountDownLatch(1)
        val bRefreshing = CountDownLatch(1)

        assertEquals(
            "old",
            a(key) {
                aRefreshing.countDown()
                endOther.await(20, TimeUnit.SECONDS)
                "a"
            },
        )
        assertTrue(aRefreshing.await(5, TimeUnit.SECONDS), "the refresh of instance a did not start")
        assertEquals(
            "old",
            b(key) {
                bRefreshing.countDown()
                "b"
            },
        )

        assertTrue(bRefreshing.await(5, TimeUnit.SECONDS), "instance b did not refresh while instance a refreshed")
        assertEquals(1L, endOther.count)
    }

    @Test
    fun eachInstanceShouldAskItsOwnStoreForTheRefreshLock() {
        val storeA = ProbeStore()
        val storeB = ProbeStore()
        for (store in listOf(storeA, storeB)) {
            store.putOldEntry(key)
            // A request of another JVM refreshes the entry, so the store refuses the lock
            store.lockByAnotherJvm(key, "UPDATE")
        }
        val a = ProbeShields.shield(storeA, global = true)
        val b = ProbeShields.shield(storeB, global = true)

        assertEquals("old", a(key) { "a" })
        assertEquals("old", b(key) { "b" })

        // The attempt of instance a a moment ago must not count as the attempt of instance b
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (storeB.lockCalls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(1, storeA.lockCalls.get())
        assertEquals(1, storeB.lockCalls.get())
    }

    @Test
    fun aCreationOfAnotherInstanceShouldLeaveTheSignalOfThisInstanceAlone() {
        val a = ProbeShields.instance(ProbeStore(), global = true)
        val b = ProbeShields.instance(ProbeStore(), global = true)
        holdTheSupplierOf(ProbeShields.callOf(a))
        val signalOfA = ProbeShields.openCreationSignalOf(a, key)
        assertNotNull(signalOfA, "the request of instance a registered no signal")

        // A whole creation of instance b for the same key: it registers a signal and completes it
        assertEquals("b", ProbeShields.callOf(b)(key) { "b" })

        // The signal of instance b completes after its cache write, which runs after the reply
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (ProbeShields.openCreationSignalOf(b, key) != null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNull(ProbeShields.openCreationSignalOf(b, key))
        // The waiters of instance a still wait for the request of instance a
        assertSame(signalOfA, ProbeShields.openCreationSignalOf(a, key))
    }

    @Test
    fun instancesWithTheLocalLockShouldStillShareTheCreationLockOfAKey() {
        val a = ProbeShields.shield(ProbeStore(), global = false)
        val b = ProbeShields.shield(ProbeStore(), global = false)
        holdTheSupplierOf(a)
        val supplierCalls = AtomicInteger()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result =
                caller.submit<String?> {
                    b(key) {
                        supplierCalls.incrementAndGet()
                        "b"
                    }
                }
            // The local lock map is shared in the JVM by key, so instance b waits for the lock of instance a
            Thread.sleep(300)
            assertEquals(0, supplierCalls.get())

            endOther.countDown()

            assertEquals("b", result.get(10, TimeUnit.SECONDS))
            assertEquals(1, supplierCalls.get())
        } finally {
            caller.shutdownNow()
        }
    }
}
