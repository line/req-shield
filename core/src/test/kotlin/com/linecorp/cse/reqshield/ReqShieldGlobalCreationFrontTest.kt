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
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * With a global lock, one request of a JVM asks the lock store for the creation lock of a key. The other requests of
 * that JVM wait for that request, and do not call the lock store.
 */
class ReqShieldGlobalCreationFrontTest {
    private val key = "global-front-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()

    /** The lock store: lock key to token. */
    private val locks = ConcurrentHashMap<String, String>()
    private val lockCalls = AtomicInteger()
    private val unlockCalls = AtomicInteger()
    private val supplierCalls = AtomicInteger()
    private val callers = Executors.newFixedThreadPool(CALLERS + 1)
    private val background = Executors.newFixedThreadPool(2)
    private val remoteProduct = Product("remote", "remote")
    private val localProduct = Product("local", "local")

    @AfterEach
    fun tearDown() {
        callers.shutdownNow()
        background.shutdownNow()
        assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS))
        assertTrue(background.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun shield(
        maxAttemptGetCache: Int = 60,
        lockFunction: (String, String) -> Boolean = { lockKey, token -> locks.putIfAbsent(lockKey, token) == null },
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey -> cache[cacheKey] },
                globalLockFunction = { lockKey, token, _ ->
                    lockCalls.incrementAndGet()
                    lockFunction(lockKey, token)
                },
                globalUnLockFunction = { lockKey, token ->
                    unlockCalls.incrementAndGet()
                    locks.remove(lockKey, token)
                },
                isLocalLock = false,
                executor = background,
                maxAttemptGetCache = maxAttemptGetCache,
            ),
        )

    private fun supplier(millis: Long = 0) =
        Callable<Product?> {
            supplierCalls.incrementAndGet()
            if (millis > 0) Thread.sleep(millis)
            localProduct
        }

    /** A request of another JVM holds the creation lock of the key. */
    private fun lockByAnotherJvm() {
        locks["reqshield:lock:${key}_CREATE"] = REMOTE_TOKEN
    }

    private fun unlockByAnotherJvm() {
        locks.remove("reqshield:lock:${key}_CREATE", REMOTE_TOKEN)
    }

    /** Starts [CALLERS] requests for the key at the same time. */
    private fun startCallers(
        shield: ReqShield<Product>,
        supplier: Callable<Product?>,
    ): List<Future<ReqShieldData<Product>>> {
        val start = CountDownLatch(1)
        val futures =
            (1..CALLERS).map {
                callers.submit<ReqShieldData<Product>> {
                    start.await()
                    shield.getAndSetReqShieldData(key, supplier, 10_000)
                }
            }
        start.countDown()
        return futures
    }

    /**
     * No front lock and no signal of the key is left: the next request for the key asks the lock store, gets the
     * lock, and gives it back. With a front lock left, that request would not ask the store.
     */
    private fun assertTheNextRequestGetsTheLock(shield: ReqShield<Product>) {
        assertEquals(null, ReqShield.creationSignals.find(key))
        cache.remove(key)
        unlockByAnotherJvm()
        await().atMost(Duration.ofSeconds(2)).until { locks.isEmpty() }
        val lockCallsBefore = lockCalls.get()
        val unlockCallsBefore = unlockCalls.get()
        val supplierCallsBefore = supplierCalls.get()

        val data = shield.getAndSetReqShieldData(key, supplier(), 10_000)

        assertEquals(localProduct, data.value)
        assertEquals(lockCallsBefore + 1, lockCalls.get())
        assertEquals(supplierCallsBefore + 1, supplierCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() == unlockCallsBefore + 1 && locks.isEmpty() }
        assertEquals(null, ReqShield.creationSignals.find(key))
    }

    @Test
    fun requestsShouldAskTheStoreOnceAndGetTheEntryOfAnotherJvm() {
        lockByAnotherJvm()
        val shield = shield()

        val futures = startCallers(shield, supplier())
        // The other JVM writes the entry after 30 ms, then gives its lock back
        Thread.sleep(30)
        cache[key] = ReqShieldData(remoteProduct, 10_000)
        unlockByAnotherJvm()

        val begin = System.nanoTime()
        futures.forEach { assertEquals(remoteProduct, it.get(2, TimeUnit.SECONDS).value) }
        // The requests of this JVM do not each wait for a poll interval of their own after the write
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1_000)
        assertEquals(1, lockCalls.get())
        assertEquals(0, unlockCalls.get())
        assertEquals(0, supplierCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun requestsShouldAskTheStoreOnceWhenARequestOfThisJvmGetsTheLock() {
        val shield = shield()

        val futures = startCallers(shield, supplier(millis = 30))

        futures.forEach { assertEquals(localProduct, it.get(2, TimeUnit.SECONDS).value) }
        assertEquals(1, supplierCalls.get())
        assertEquals(1, lockCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() == 1 }
        assertTrue(locks.isEmpty())
        assertEquals(null, ReqShield.creationSignals.find(key))
    }

    @Test
    fun aWaiterShouldBeTheNextHolderRightAfterTheHolderOfThisJvmFailed() {
        val shield = shield()
        val holderInSupplier = CountDownLatch(1)
        val failHolder = CountDownLatch(1)
        val holder =
            callers.submit<ReqShieldData<Product>> {
                shield.getAndSetReqShieldData(
                    key,
                    Callable<Product?> {
                        holderInSupplier.countDown()
                        failHolder.await()
                        throw IllegalStateException("the supplier of the holder failed")
                    },
                    10_000,
                )
            }
        assertTrue(holderInSupplier.await(2, TimeUnit.SECONDS))
        val waiter = callers.submit<ReqShieldData<Product>> { shield.getAndSetReqShieldData(key, supplier(), 10_000) }
        // The waiter lost the front lock and waits for the signal of the holder
        Thread.sleep(20)
        assertEquals(1, lockCalls.get())

        val begin = System.nanoTime()
        failHolder.countDown()

        assertEquals(localProduct, waiter.get(2, TimeUnit.SECONDS).value)
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
        // Not a poll interval of 50 ms: the release of the holder wakes the waiter
        assertTrue(waitedMillis < 40, "the waiter got its value after $waitedMillis ms")
        assertThrows(Exception::class.java) { holder.get(2, TimeUnit.SECONDS) }
        assertEquals(1, supplierCalls.get())
        assertEquals(2, lockCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() == 2 && locks.isEmpty() }
    }

    @Test
    fun theFrontLockShouldBeFreeAfterTheRequestThatReadsForTheJvmGaveUp() {
        lockByAnotherJvm()
        // Reads at 0, 5 and 15 ms, then each request calls the supplier without a lock
        val shield = shield(maxAttemptGetCache = 3)

        val futures = startCallers(shield, supplier())

        futures.forEach { assertEquals(localProduct, it.get(3, TimeUnit.SECONDS).value) }
        assertEquals(CALLERS, supplierCalls.get())
        // When the reading request gives up, a waiter takes its place and asks the store once more. So the count is
        // not 1 here, but no request asks more often than it reads.
        assertTrue(lockCalls.get() in 1..CALLERS * 3, "lock calls: ${lockCalls.get()}")
        assertEquals(0, unlockCalls.get())
        // A request that gave up writes no entry
        assertEquals(null, cache[key])
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun theFrontLockShouldBeFreeAfterTheLockStoreFailed() {
        val storeFails = AtomicInteger(1)
        val shield =
            shield(lockFunction = { lockKey, token ->
                if (storeFails.getAndDecrement() > 0) throw IllegalStateException("the lock store is down")
                locks.putIfAbsent(lockKey, token) == null
            })

        assertThrows(Exception::class.java) { shield.getAndSetReqShieldData(key, supplier(), 10_000) }

        assertEquals(0, supplierCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    private companion object {
        const val CALLERS = 20
        const val REMOTE_TOKEN = "token-of-another-jvm"
    }
}
