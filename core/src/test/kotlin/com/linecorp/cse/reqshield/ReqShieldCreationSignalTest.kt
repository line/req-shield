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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A request that waits for the holder of a creation lock or for a refresh waits for the signal of that holder, not
 * for a whole poll interval. The poll interval is 50 ms, so a wait that ends within 25 ms of the release was ended
 * by the signal.
 */
class ReqShieldCreationSignalTest {
    // The signals and the local locks are shared by all instances, so each test has its own key
    private val key = "creation-signal-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(4)
    private val product = Product("id", "name")

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun shield(
        writeMillis: Long = 0,
        maxAttemptGetCache: Int = 60,
        lockTimeoutMillis: Long = 3000,
        executor: Executor = pool,
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    Thread.sleep(writeMillis)
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey ->
                    cacheReads.incrementAndGet()
                    cache[cacheKey]
                },
                executor = executor,
                maxAttemptGetCache = maxAttemptGetCache,
                lockTimeoutMillis = lockTimeoutMillis,
            ),
        )

    private fun awaitNoSignal() = await().atMost(Duration.ofSeconds(2)).until { ReqShield.creationSignals.find(key) == null }

    @Test
    fun aWaiterShouldGetTheEntryRightAfterTheHolderWroteIt() {
        val finishSupplier = CountDownLatch(1)
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Product?> {
                supplierCalls.incrementAndGet()
                check(finishSupplier.await(5, TimeUnit.SECONDS))
                product
            }
        val shield = shield()
        val holder = pool.submit<ReqShieldData<Product>> { shield.getAndSetReqShieldData(key, supplier, 10_000) }
        // The holder read the cache twice: before the lock and after it
        await().atMost(Duration.ofSeconds(2)).until { cacheReads.get() == 2 && supplierCalls.get() == 1 }

        val waiterEndedAtNanos =
            pool.submit<Long> {
                shield.getAndSetReqShieldData(key, supplier, 10_000)
                System.nanoTime()
            }
        // Checked each millisecond, so the sleep below starts when the waiter starts
        await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(2)).until { cacheReads.get() >= 3 }
        // 70 ms after the waiter started, a poll at a fixed 50 ms interval is 30 ms away from its next read
        Thread.sleep(70)
        val releasedAtNanos = System.nanoTime()
        finishSupplier.countDown()

        val waitAfterReleaseMillis = (waiterEndedAtNanos.get(2, TimeUnit.SECONDS) - releasedAtNanos) / 1_000_000
        assertTrue(waitAfterReleaseMillis < 25, "the waiter ended $waitAfterReleaseMillis ms after the release")
        assertEquals(product, holder.get(2, TimeUnit.SECONDS).value)
        assertEquals(1, supplierCalls.get())
    }

    @Test
    fun aSecondReadOfANewKeyShouldWaitForTheCacheWriteAndNotForAPollInterval() {
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Product?> {
                supplierCalls.incrementAndGet()
                product
            }
        // The first read returns before its cache write, which takes 20 ms
        val shield = shield(writeMillis = 20)

        shield.getAndSetReqShieldData(key, supplier, 10_000)
        val startedAtNanos = System.nanoTime()
        val second = shield.getAndSetReqShieldData(key, supplier, 10_000)
        val secondReadMillis = (System.nanoTime() - startedAtNanos) / 1_000_000

        assertEquals(product, second.value)
        assertEquals(1, supplierCalls.get())
        assertTrue(secondReadMillis < 45, "the second read took $secondReadMillis ms")
    }

    @Test
    fun aWaiterThatLostTheLockBeforeTheHolderRegisteredItsSignalShouldStillWaitForThatSignal() {
        val holderHasTheLock = CountDownLatch(1)
        val waiterLostTheLock = CountDownLatch(1)
        val localLock = KeyLocalLock(3000)
        // The first request gets the lock, but its lock call returns only after the second request lost the lock.
        // So the second request looks for the signal before the first one can register it.
        val lock =
            object : KeyLock {
                override fun tryLock(
                    key: String,
                    lockType: LockType,
                ): String? {
                    val token = localLock.tryLock(key, lockType)
                    if (lockType == LockType.CREATE) {
                        if (token != null) {
                            holderHasTheLock.countDown()
                            check(waiterLostTheLock.await(5, TimeUnit.SECONDS))
                        } else {
                            waiterLostTheLock.countDown()
                        }
                    }
                    return token
                }

                override fun unLock(
                    key: String,
                    lockType: LockType,
                    token: String,
                ): Boolean = localLock.unLock(key, lockType, token)
            }
        val shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { cacheKey, data, _ ->
                        cache[cacheKey] = data
                        true
                    },
                    getCacheFunction = { cacheKey -> cache[cacheKey] },
                    executor = pool,
                    keyLock = lock,
                ),
            )
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Product?> {
                supplierCalls.incrementAndGet()
                Thread.sleep(15)
                product
            }
        val holder = pool.submit<ReqShieldData<Product>> { shield.getAndSetReqShieldData(key, supplier, 10_000) }
        assertTrue(holderHasTheLock.await(2, TimeUnit.SECONDS))

        val startedAtNanos = System.nanoTime()
        val waited = shield.getAndSetReqShieldData(key, supplier, 10_000)
        val waitedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000

        assertEquals(product, waited.value)
        assertEquals(product, holder.get(2, TimeUnit.SECONDS).value)
        assertEquals(1, supplierCalls.get())
        // The supplier takes 15 ms. A request that found no signal and slept the whole interval would need 50 ms.
        assertTrue(waitedMillis < 45, "the waiter ended after $waitedMillis ms")
    }

    @Test
    fun aWaiterWithoutASignalShouldKeepThePollIntervalAndTheReadCount() {
        // A lock that is held without a signal: the holder is in another JVM, for example
        val lock = KeyLocalLock(3000)
        val token = checkNotNull(lock.tryLock(key, LockType.CREATE))
        val supplierCalls = AtomicInteger()
        val shield = shield(maxAttemptGetCache = 4)

        try {
            val startedAtNanos = System.nanoTime()
            val data =
                shield.getAndSetReqShieldData(
                    key,
                    {
                        supplierCalls.incrementAndGet()
                        product
                    },
                    10_000,
                )
            val waitedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000

            assertEquals(product, data.value)
            // One read before the lock attempt, then maxAttemptGetCache reads with three poll intervals between them
            assertEquals(5, cacheReads.get())
            assertTrue(waitedMillis >= 150, "the waiter gave up after $waitedMillis ms")
            assertEquals(1, supplierCalls.get())
        } finally {
            lock.unLock(key, LockType.CREATE, token)
        }
    }

    @Test
    fun aWaiterShouldBeTheNextHolderRightAfterTheHolderFailed() {
        val failHolder = CountDownLatch(1)
        val holderStarted = CountDownLatch(1)
        val shield = shield()
        val holder =
            pool.submit {
                assertThrows(ClientException::class.java) {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            holderStarted.countDown()
                            check(failHolder.await(5, TimeUnit.SECONDS))
                            error("the supplier of the holder fails")
                        },
                        10_000,
                    )
                }
            }
        assertTrue(holderStarted.await(2, TimeUnit.SECONDS))
        val waiterSupplierCalls = AtomicInteger()
        val waiterSupplier =
            Callable<Product?> {
                waiterSupplierCalls.incrementAndGet()
                product
            }
        val waiters =
            List(2) {
                pool.submit<Long> {
                    assertEquals(product, shield.getAndSetReqShieldData(key, waiterSupplier, 10_000).value)
                    System.nanoTime()
                }
            }
        // Holder: 2 reads. Each waiter: 1 read before the lock attempt and 1 read after it.
        await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(2)).until { cacheReads.get() >= 6 }
        Thread.sleep(70)

        val failedAtNanos = System.nanoTime()
        failHolder.countDown()

        holder.get(2, TimeUnit.SECONDS)
        waiters.forEach { waiter ->
            val waitAfterFailureMillis = (waiter.get(2, TimeUnit.SECONDS) - failedAtNanos) / 1_000_000
            // Without the retry of the lock, a waiter reads at the 50 ms interval until its 60 reads are used up
            assertTrue(waitAfterFailureMillis < 25, "a waiter ended $waitAfterFailureMillis ms after the failure")
        }
        // One waiter got the lock and called the supplier. The other one got the entry of that waiter.
        assertEquals(1, waiterSupplierCalls.get())
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        awaitNoSignal()
    }

    @Test
    fun aWaiterShouldCallTheSupplierItselfWhenItsReadsAreUsedUpAfterAFailedHolder() {
        val failHolder = CountDownLatch(1)
        val holderStarted = CountDownLatch(1)
        // One read after the lost lock: the read that follows the release is the last one
        val shield = shield(maxAttemptGetCache = 2)
        val holder =
            pool.submit {
                assertThrows(ClientException::class.java) {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            holderStarted.countDown()
                            check(failHolder.await(5, TimeUnit.SECONDS))
                            error("the supplier of the holder fails")
                        },
                        10_000,
                    )
                }
            }
        assertTrue(holderStarted.await(2, TimeUnit.SECONDS))
        val waiterSupplierCalls = AtomicInteger()
        val waiter =
            pool.submit<ReqShieldData<Product>> {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        waiterSupplierCalls.incrementAndGet()
                        product
                    },
                    10_000,
                )
            }
        await().atMost(Duration.ofSeconds(2)).until { cacheReads.get() >= 4 }

        failHolder.countDown()

        holder.get(2, TimeUnit.SECONDS)
        assertEquals(product, waiter.get(2, TimeUnit.SECONDS).value)
        assertEquals(1, waiterSupplierCalls.get())
        // Holder: 2 reads. Waiter: 1 read before the lock attempt and maxAttemptGetCache reads after it.
        assertTrue(cacheReads.get() <= 6, "the cache was read ${cacheReads.get()} times")
    }

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(Product("old", "old"), ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    private fun awaitNoRefreshSignal() = await().atMost(Duration.ofSeconds(2)).until { ReqShield.refreshSignals.find(key) == null }

    @Test
    fun aRequestThatFindsTheEntryGoneDuringARefreshShouldWaitForTheRefresh() {
        cache[key] = oldEntry()
        val finishRefresh = CountDownLatch(1)
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Product?> {
                supplierCalls.incrementAndGet()
                check(finishRefresh.await(5, TimeUnit.SECONDS))
                product
            }
        val shield = shield()
        // This request gets the old entry and starts the refresh
        shield.getAndSetReqShieldData(key, supplier, 10_000)
        await().atMost(Duration.ofSeconds(2)).until { supplierCalls.get() == 1 }
        // The old entry expires before the refresh ends
        cache.remove(key)

        val readsBefore = cacheReads.get()
        val waiterEndedAtNanos =
            pool.submit<Long> {
                assertEquals(product, shield.getAndSetReqShieldData(key, supplier, 10_000).value)
                System.nanoTime()
            }
        await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(2)).until { cacheReads.get() > readsBefore }
        Thread.sleep(70)
        assertEquals(1, supplierCalls.get(), "the waiting request must not call the supplier")
        val refreshEndedAtNanos = System.nanoTime()
        finishRefresh.countDown()

        val waitAfterRefreshMillis = (waiterEndedAtNanos.get(2, TimeUnit.SECONDS) - refreshEndedAtNanos) / 1_000_000
        assertTrue(waitAfterRefreshMillis < 25, "the request ended $waitAfterRefreshMillis ms after the refresh")
        assertEquals(1, supplierCalls.get())
        awaitNoRefreshSignal()
    }

    @Test
    fun aRequestThatWaitedForAFailedRefreshShouldCreateTheEntryAtOnce() {
        cache[key] = oldEntry()
        val failRefresh = CountDownLatch(1)
        val refreshStarted = CountDownLatch(1)
        val shield = shield()
        shield.getAndSetReqShieldData(
            key,
            {
                refreshStarted.countDown()
                check(failRefresh.await(5, TimeUnit.SECONDS))
                error("the supplier of the refresh fails")
            },
            10_000,
        )
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
        cache.remove(key)

        val readsBefore = cacheReads.get()
        val waiterSupplierCalls = AtomicInteger()
        val waiterEndedAtNanos =
            pool.submit<Long> {
                val data =
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            waiterSupplierCalls.incrementAndGet()
                            product
                        },
                        10_000,
                    )
                assertEquals(product, data.value)
                System.nanoTime()
            }
        await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(2)).until { cacheReads.get() > readsBefore }
        Thread.sleep(70)
        assertEquals(0, waiterSupplierCalls.get())
        val failedAtNanos = System.nanoTime()
        failRefresh.countDown()

        val waitAfterFailureMillis = (waiterEndedAtNanos.get(2, TimeUnit.SECONDS) - failedAtNanos) / 1_000_000
        assertTrue(waitAfterFailureMillis < 25, "the request ended $waitAfterFailureMillis ms after the failed refresh")
        assertEquals(1, waiterSupplierCalls.get())
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        awaitNoRefreshSignal()
    }

    @Test
    fun aRequestShouldCreateTheEntryWhenARefreshOutlastsItsReads() {
        cache[key] = oldEntry()
        val finishRefresh = CountDownLatch(1)
        val refreshStarted = CountDownLatch(1)
        // Two reads, 50 ms apart, are all that a request spends on the refresh
        val shield = shield(maxAttemptGetCache = 2)
        try {
            shield.getAndSetReqShieldData(
                key,
                {
                    refreshStarted.countDown()
                    check(finishRefresh.await(5, TimeUnit.SECONDS))
                    Product("refreshed", "refreshed")
                },
                10_000,
            )
            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
            cache.remove(key)

            val data = shield.getAndSetReqShieldData(key, { product }, 10_000)

            assertEquals(product, data.value)
        } finally {
            finishRefresh.countDown()
        }
        awaitNoRefreshSignal()
    }

    @Test
    fun requestsShouldStillCollapseWhenARefreshOutlastsTheirReads() {
        cache[key] = oldEntry()
        val finishRefresh = CountDownLatch(1)
        val refreshStarted = CountDownLatch(1)
        // Two reads, 50 ms apart, are all that a request spends on the refresh
        val shield = shield(maxAttemptGetCache = 2)
        // The requests run on threads of their own, so the background tasks of req-shield always find a free thread
        val callers = Executors.newFixedThreadPool(4)
        val creationCalls = AtomicInteger()
        val creationSupplier =
            Callable<Product?> {
                creationCalls.incrementAndGet()
                Thread.sleep(20)
                product
            }
        try {
            shield.getAndSetReqShieldData(
                key,
                {
                    refreshStarted.countDown()
                    check(finishRefresh.await(5, TimeUnit.SECONDS))
                    Product("refreshed", "refreshed")
                },
                10_000,
            )
            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
            cache.remove(key)

            val requests =
                List(4) { callers.submit<ReqShieldData<Product>> { shield.getAndSetReqShieldData(key, creationSupplier, 10_000) } }

            requests.forEach { assertEquals(product, it.get(2, TimeUnit.SECONDS).value) }
            // One request got the creation lock. The others waited for it with reads of their own.
            assertEquals(1, creationCalls.get())
        } finally {
            finishRefresh.countDown()
            callers.shutdownNow()
        }
        awaitNoRefreshSignal()
    }

    /**
     * States what a read gets after an eviction during a refresh: the value of that refresh, whose supplier call
     * started before the eviction. The README names this as a known limitation.
     */
    @Test
    fun aReadAfterAnEvictionDuringARefreshShouldGetTheValueOfThatRefresh() {
        cache[key] = oldEntry()
        val finishRefresh = CountDownLatch(1)
        val refreshStarted = CountDownLatch(1)
        val refreshed = Product("refreshed", "computed before the eviction")
        val shield = shield()
        shield.getAndSetReqShieldData(
            key,
            {
                refreshStarted.countDown()
                check(finishRefresh.await(5, TimeUnit.SECONDS))
                refreshed
            },
            10_000,
        )
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
        // The application evicts the entry while the refresh runs
        cache.remove(key)

        val readerSupplierCalls = AtomicInteger()
        val reader =
            pool.submit<ReqShieldData<Product>> {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        readerSupplierCalls.incrementAndGet()
                        product
                    },
                    10_000,
                )
            }
        Thread.sleep(100)
        finishRefresh.countDown()

        assertEquals(refreshed, reader.get(2, TimeUnit.SECONDS).value)
        assertEquals(0, readerSupplierCalls.get())
        awaitNoRefreshSignal()
    }

    @Test
    fun noRefreshSignalShouldStayAfterARefreshWroteTheEntry() {
        cache[key] = oldEntry()

        shield().getAndSetReqShieldData(key, { product }, 10_000)

        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        awaitNoRefreshSignal()
    }

    @Test
    fun noSignalShouldStayAfterACacheWrite() {
        shield().getAndSetReqShieldData(key, { product }, 10_000)

        awaitNoSignal()
        assertEquals(product, cache[key]?.value)
    }

    @Test
    fun noSignalShouldStayWhenTheEntryAppearsBeforeTheLock() {
        val entry = ReqShieldData(product, 10_000)
        val reads = AtomicInteger()
        val shield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ -> true },
                    // Missing at the first read, there at the read under the lock
                    getCacheFunction = { if (reads.incrementAndGet() == 1) null else entry },
                    executor = pool,
                ),
            )

        assertEquals(product, shield.getAndSetReqShieldData(key, { error("not called") }, 10_000).value)

        assertNull(ReqShield.creationSignals.find(key))
    }

    @Test
    fun noSignalShouldStayWhenTheSupplierFails() {
        assertThrows(ClientException::class.java) {
            shield().getAndSetReqShieldData(key, { error("the supplier fails") }, 10_000)
        }

        assertNull(ReqShield.creationSignals.find(key))
    }

    @Test
    fun noSignalShouldStayWhenTheExecutorRejectsTheCacheWrite() {
        val rejecting = Executor { throw RejectedExecutionException("full") }

        assertEquals(product, shield(executor = rejecting).getAndSetReqShieldData(key, { product }, 10_000).value)

        assertNull(ReqShield.creationSignals.find(key))
    }

    @Test
    fun noSignalShouldStayWhenALateCacheWriteIsSkipped() {
        val other = Product("other", "other")
        // The lock times out during the supplier call, and another request writes the entry in that time
        val shield = shield(lockTimeoutMillis = 20)

        shield.getAndSetReqShieldData(
            key,
            {
                Thread.sleep(60)
                cache[key] = ReqShieldData(other, 10_000)
                product
            },
            10_000,
        )

        awaitNoSignal()
        assertEquals(other, cache[key]?.value)
    }
}
