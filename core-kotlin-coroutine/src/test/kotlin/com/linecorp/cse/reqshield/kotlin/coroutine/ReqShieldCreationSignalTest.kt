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
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A request that waits for the holder of a creation lock or for a refresh waits for the signal of that holder, not
 * for a whole poll interval. The poll interval is 50 ms, so a wait that ends within 25 ms of the release was ended
 * by the signal. The tests run in real time, because the waits are measured with the system clock.
 */
class ReqShieldCreationSignalTest {
    /** The ReqShield that the test made last. Its signals and guard locks are the ones the test looks at. */
    private lateinit var created: ReqShield<Product>

    // The signals and the local locks are shared by all instances, so each test has its own key
    private val key = "creation-signal-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()

    // Runs the background cache writes of the shield
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Runs the requests. It is not the shield's scope, so awaitScopeTasks does not wait for the requests.
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val product = Product("id", "name")

    @AfterEach
    fun tearDown() {
        callers.cancel()
        scope.cancel()
    }

    private fun shield(
        writeMillis: Long = 0,
        maxAttemptGetCache: Int = 60,
        lockTimeoutMillis: Long = 3000,
        keyLock: KeyLock = KeyLocalLock(lockTimeoutMillis),
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        scope: CoroutineScope = this.scope,
        getCache: suspend (String) -> ReqShieldData<Product>? = { cacheKey -> cache[cacheKey] },
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    delay(writeMillis)
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey ->
                    cacheReads.incrementAndGet()
                    getCache(cacheKey)
                },
                keyLock = keyLock,
                lockTimeoutMillis = lockTimeoutMillis,
                maxAttemptGetCache = maxAttemptGetCache,
                reqShieldWorkMode = workMode,
                scope = scope,
            ),
        ).also { created = it }

    /** Waits until each task that the shield launched on its scope has ended. */
    private suspend fun awaitScopeTasks() = scope.coroutineContext.job.children.toList().forEach { it.join() }

    private suspend fun awaitReads(count: Int) =
        withTimeout(2_000) {
            while (cacheReads.get() < count) delay(1)
        }

    private fun signal() = created.creationSignals.find(key)

    private fun refreshSignal() = created.refreshSignals.find(key)

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(Product("old", "old"), ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    /** Cancels the shield's scope and waits until each task that it launched has ended. */
    private suspend fun cancelTheScope() {
        // Taken before the cancel, so the join waits for the tasks that release their locks
        val tasks = scope.coroutineContext.job.children.toList()
        scope.cancel()
        tasks.forEach { it.join() }
    }

    /** True when no request of this JVM holds the lock of [lockType] for the key. The lock is released at once. */
    private suspend fun KeyLock.isFree(lockType: LockType): Boolean {
        val token = tryLock(key, lockType) ?: return false
        unLock(key, lockType, token)
        return true
    }

    @Test
    fun aWaiterShouldGetTheEntryRightAfterTheHolderWroteIt() =
        runBlocking {
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            val supplierCalls = AtomicInteger()
            val supplier: suspend () -> Product? = {
                supplierCalls.incrementAndGet()
                supplierStarted.complete(Unit)
                finishSupplier.await()
                product
            }
            val shield = shield()
            val holder = callers.async { shield.getAndSetReqShieldData(key, supplier, 10_000) }
            withTimeout(2_000) { supplierStarted.await() }
            // The holder read the cache twice: before the lock and after it
            assertEquals(2, cacheReads.get())

            val waiterEndedAtNanos =
                callers.async {
                    shield.getAndSetReqShieldData(key, supplier, 10_000)
                    System.nanoTime()
                }
            awaitReads(3)
            // 70 ms after the waiter started, a poll at a fixed 50 ms interval is 30 ms away from its next read
            delay(70)
            val releasedAtNanos = System.nanoTime()
            finishSupplier.complete(Unit)

            val waitAfterReleaseMillis = (withTimeout(2_000) { waiterEndedAtNanos.await() } - releasedAtNanos) / 1_000_000
            assertTrue(waitAfterReleaseMillis < 25, "the waiter ended $waitAfterReleaseMillis ms after the release")
            assertEquals(product, holder.await().value)
            assertEquals(1, supplierCalls.get())
        }

    @Test
    fun aCacheWriteThatTheReadMissedShouldEndTheWaitAtOnce() =
        runBlocking {
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            var missReturnedAtNanos = 0L
            // Read 4 is the first read in the wait of the waiter. It misses, and the holder writes the entry and
            // releases its lock before the read returns.
            val shield =
                shield(getCache = { cacheKey ->
                    val entry = cache[cacheKey]
                    if (cacheReads.get() == 4) {
                        finishSupplier.complete(Unit)
                        withTimeout(2_000) { while (signal() != null) delay(1) }
                        missReturnedAtNanos = System.nanoTime()
                    }
                    entry
                })
            val supplier: suspend () -> Product? = {
                supplierStarted.complete(Unit)
                finishSupplier.await()
                product
            }
            val holder = callers.async { shield.getAndSetReqShieldData(key, supplier, 10_000) }
            withTimeout(2_000) { supplierStarted.await() }

            val waiterEndedAtNanos =
                callers.async {
                    shield.getAndSetReqShieldData(key, supplier, 10_000)
                    System.nanoTime()
                }

            val waitAfterMissMillis = (withTimeout(2_000) { waiterEndedAtNanos.await() } - missReturnedAtNanos) / 1_000_000
            // The waiter looked up the signal before the read, so the release that the read missed ends the wait
            assertTrue(waitAfterMissMillis < 25, "the waiter ended $waitAfterMissMillis ms after its read missed")
            assertEquals(product, holder.await().value)
            assertEquals(5, cacheReads.get())
        }

    @Test
    fun aSecondReadOfANewKeyShouldWaitForTheCacheWriteAndNotForAPollInterval() =
        runBlocking {
            val supplierCalls = AtomicInteger()
            val supplier: suspend () -> Product? = {
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
    fun aWaiterWithoutASignalShouldKeepThePollIntervalAndTheReadCount() =
        runBlocking {
            // A lock that is held without a signal: the holder is in another JVM, for example
            val lock = KeyLocalLock(3000)
            val token = checkNotNull(lock.tryLock(key, LockType.CREATE))
            val supplierCalls = AtomicInteger()
            var lastReadAtNanos = 0L
            var supplierStartedAtNanos = 0L
            val shield =
                shield(maxAttemptGetCache = 4, getCache = { cacheKey ->
                    lastReadAtNanos = System.nanoTime()
                    cache[cacheKey]
                })

            try {
                val startedAtNanos = System.nanoTime()
                val data =
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierStartedAtNanos = System.nanoTime()
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
                // No poll interval follows the last read: the supplier call comes right after it
                val supplierAfterLastReadMillis = (supplierStartedAtNanos - lastReadAtNanos) / 1_000_000
                assertTrue(supplierAfterLastReadMillis < 25, "the supplier started $supplierAfterLastReadMillis ms after the last read")
                assertEquals(1, supplierCalls.get())
                assertNull(signal())
            } finally {
                lock.unLock(key, LockType.CREATE, token)
            }
        }

    /** A request that holds the lock and whose supplier fails when [failHolder] completes. */
    private fun CoroutineScope.failingHolder(
        shield: ReqShield<Product>,
        holderStarted: CompletableDeferred<Unit>,
        failHolder: CompletableDeferred<Unit>,
    ) = async {
        assertFailsWith<ClientException> {
            shield.getAndSetReqShieldData(
                key,
                {
                    holderStarted.complete(Unit)
                    failHolder.await()
                    error("the supplier of the holder fails")
                },
                10_000,
            )
        }
    }

    @Test
    fun aWaiterShouldBeTheNextHolderRightAfterTheHolderFailed() =
        runBlocking {
            val holderStarted = CompletableDeferred<Unit>()
            val failHolder = CompletableDeferred<Unit>()
            val shield = shield()
            val holder = callers.failingHolder(shield, holderStarted, failHolder)
            withTimeout(2_000) { holderStarted.await() }
            val waiterSupplierCalls = AtomicInteger()
            val waiterSupplier: suspend () -> Product? = {
                waiterSupplierCalls.incrementAndGet()
                product
            }
            val waiters =
                List(2) {
                    callers.async {
                        assertEquals(product, shield.getAndSetReqShieldData(key, waiterSupplier, 10_000).value)
                        System.nanoTime()
                    }
                }
            // Holder: 2 reads. Each waiter: 1 read before the lock attempt and 1 read after it.
            awaitReads(6)
            delay(70)

            val failedAtNanos = System.nanoTime()
            failHolder.complete(Unit)

            withTimeout(2_000) { holder.await() }
            waiters.forEach { waiter ->
                val waitAfterFailureMillis = (withTimeout(2_000) { waiter.await() } - failedAtNanos) / 1_000_000
                // Without the retry of the lock, a waiter reads at the 50 ms interval until its 60 reads are used up
                assertTrue(waitAfterFailureMillis < 25, "a waiter ended $waitAfterFailureMillis ms after the failure")
            }
            // One waiter got the lock and called the supplier. The other one got the entry of that waiter.
            assertEquals(1, waiterSupplierCalls.get())
            awaitScopeTasks()
            assertEquals(product, cache[key]?.value)
            assertNull(signal())
        }

    @Test
    fun aWaiterShouldCallTheSupplierItselfWhenItsReadsAreUsedUpAfterAFailedHolder() =
        runBlocking {
            // The holder of this JVM gets the lock. After its release, a holder in another JVM takes the lock, so
            // the retry of the waiter loses it and finds no signal.
            val localLock = KeyLocalLock(3000)
            val tryLocks = AtomicInteger()
            val lockTakenByAnotherJvm =
                object : KeyLock {
                    override suspend fun tryLock(
                        key: String,
                        lockType: LockType,
                    ): String? = if (tryLocks.incrementAndGet() == 1) localLock.tryLock(key, lockType) else null

                    override suspend fun unLock(
                        key: String,
                        lockType: LockType,
                        token: String,
                    ): Boolean = localLock.unLock(key, lockType, token)
                }
            val holderStarted = CompletableDeferred<Unit>()
            val failHolder = CompletableDeferred<Unit>()
            // Two reads after the lost lock: the read that follows the release is the last one
            val shield = shield(maxAttemptGetCache = 2, keyLock = lockTakenByAnotherJvm)
            val holder = callers.failingHolder(shield, holderStarted, failHolder)
            withTimeout(2_000) { holderStarted.await() }
            val waiterSupplierCalls = AtomicInteger()
            val waiter =
                callers.async {
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
            // Holder: 2 reads. Waiter: 1 read before the lock attempt and 1 read in its wait. The holder fails at
            // once, because the waiter waits only one poll interval for it.
            awaitReads(4)
            assertTrue(waiter.isActive, "the waiter ended before the holder failed")

            val failedAtNanos = System.nanoTime()
            failHolder.complete(Unit)

            withTimeout(2_000) { holder.await() }
            val waitAfterFailureMillis = (withTimeout(2_000) { waiter.await() } - failedAtNanos) / 1_000_000
            assertTrue(waitAfterFailureMillis < 25, "the waiter ended $waitAfterFailureMillis ms after the failure")
            assertEquals(1, waiterSupplierCalls.get())
            // Holder: 1 lock attempt. Waiter: 1 attempt, and 1 retry after the release that it lost.
            assertEquals(3, tryLocks.get())
            // Holder: 2 reads. Waiter: 1 read before the lock attempt and maxAttemptGetCache reads after it.
            assertEquals(2 + 1 + 2, cacheReads.get())
        }

    @Test
    fun theWaitForTheCreationLockShouldHaveItsOwnReadsAfterTheWaitForARefresh() =
        runBlocking {
            cache[key] = oldEntry()
            val refreshStarted = CompletableDeferred<Unit>()
            val finishRefresh = CompletableDeferred<Unit>()
            // A creation lock that is held without a signal: the holder is in another JVM, for example
            val otherJvmLock = KeyLocalLock(3000)
            val otherJvmToken = checkNotNull(otherJvmLock.tryLock(key, LockType.CREATE))
            val shield = shield(maxAttemptGetCache = 3)
            try {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        refreshStarted.complete(Unit)
                        finishRefresh.await()
                        Product("refreshed", "refreshed")
                    },
                    10_000,
                )
                withTimeout(2_000) { refreshStarted.await() }
                cache.remove(key)
                val readsBefore = cacheReads.get()
                val supplierCalls = AtomicInteger()

                val data =
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierCalls.incrementAndGet()
                            product
                        },
                        10_000,
                    )

                assertEquals(product, data.value)
                assertEquals(1, supplierCalls.get())
                // 1 read before the waits, then maxAttemptGetCache reads for the refresh and maxAttemptGetCache reads
                // for the creation lock
                assertEquals(1 + 3 + 3, cacheReads.get() - readsBefore)
            } finally {
                finishRefresh.complete(Unit)
                otherJvmLock.unLock(key, LockType.CREATE, otherJvmToken)
            }
        }

    @Test
    fun aRequestThatFindsTheEntryGoneDuringARefreshShouldWaitForTheRefresh() =
        runBlocking {
            cache[key] = oldEntry()
            val finishRefresh = CompletableDeferred<Unit>()
            val supplierCalls = AtomicInteger()
            val supplier: suspend () -> Product? = {
                supplierCalls.incrementAndGet()
                finishRefresh.await()
                product
            }
            val shield = shield()
            // This request gets the old entry and starts the refresh
            shield.getAndSetReqShieldData(key, supplier, 10_000)
            withTimeout(2_000) { while (supplierCalls.get() < 1) delay(1) }
            // The old entry expires before the refresh ends
            cache.remove(key)

            val readsBefore = cacheReads.get()
            val waiterEndedAtNanos =
                callers.async {
                    assertEquals(product, shield.getAndSetReqShieldData(key, supplier, 10_000).value)
                    System.nanoTime()
                }
            awaitReads(readsBefore + 1)
            delay(70)
            assertEquals(1, supplierCalls.get(), "the waiting request must not call the supplier")
            val refreshEndedAtNanos = System.nanoTime()
            finishRefresh.complete(Unit)

            val waitAfterRefreshMillis = (withTimeout(2_000) { waiterEndedAtNanos.await() } - refreshEndedAtNanos) / 1_000_000
            assertTrue(waitAfterRefreshMillis < 25, "the request ended $waitAfterRefreshMillis ms after the refresh")
            assertEquals(1, supplierCalls.get())
            awaitScopeTasks()
            assertNull(refreshSignal())
        }

    @Test
    fun aRequestThatWaitedForAFailedRefreshShouldCreateTheEntryAtOnce() =
        runBlocking {
            cache[key] = oldEntry()
            val refreshStarted = CompletableDeferred<Unit>()
            val failRefresh = CompletableDeferred<Unit>()
            val shield = shield()
            shield.getAndSetReqShieldData(
                key,
                {
                    refreshStarted.complete(Unit)
                    failRefresh.await()
                    error("the supplier of the refresh fails")
                },
                10_000,
            )
            withTimeout(2_000) { refreshStarted.await() }
            cache.remove(key)

            val readsBefore = cacheReads.get()
            val waiterSupplierCalls = AtomicInteger()
            val waiterEndedAtNanos =
                callers.async {
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
            awaitReads(readsBefore + 1)
            delay(70)
            assertEquals(0, waiterSupplierCalls.get())
            val failedAtNanos = System.nanoTime()
            failRefresh.complete(Unit)

            val waitAfterFailureMillis = (withTimeout(2_000) { waiterEndedAtNanos.await() } - failedAtNanos) / 1_000_000
            assertTrue(waitAfterFailureMillis < 25, "the request ended $waitAfterFailureMillis ms after the failed refresh")
            assertEquals(1, waiterSupplierCalls.get())
            awaitScopeTasks()
            assertEquals(product, cache[key]?.value)
            assertNull(refreshSignal())
        }

    @Test
    fun aRequestShouldCallTheSupplierWhenTheCacheFailsDuringARefresh() =
        runBlocking {
            cache[key] = oldEntry()
            val refreshStarted = CompletableDeferred<Unit>()
            val finishRefresh = CompletableDeferred<Unit>()
            // Each read with a number above this one fails, as with a cache that is down
            val failReadsAfter = AtomicInteger(Int.MAX_VALUE)
            val shield =
                shield(
                    getCache = { cacheKey ->
                        check(cacheReads.get() <= failReadsAfter.get()) { "the cache is down" }
                        cache[cacheKey]
                    },
                )
            try {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        refreshStarted.complete(Unit)
                        finishRefresh.await()
                        Product("refreshed", "refreshed")
                    },
                    10_000,
                )
                withTimeout(2_000) { refreshStarted.await() }
                cache.remove(key)
                val readsBefore = cacheReads.get()
                // The request reads the miss. Each read after that fails.
                failReadsAfter.set(readsBefore + 1)
                val supplierCalls = AtomicInteger()

                val data =
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierCalls.incrementAndGet()
                            product
                        },
                        10_000,
                    )

                assertEquals(product, data.value)
                assertEquals(1, supplierCalls.get())
                // The read on the miss and the three failed reads of the wait. No read under a creation lock follows.
                assertEquals(1 + 3, cacheReads.get() - readsBefore)
                assertNull(signal(), "the request took no creation lock")
            } finally {
                failReadsAfter.set(Int.MAX_VALUE)
                finishRefresh.complete(Unit)
            }
            withTimeout(2_000) {
                while (refreshSignal() != null) delay(1)
            }
        }

    @Test
    fun aWaiterShouldCallTheSupplierWhenItsReadFailsAfterTheHolderGaveTheLockBack() =
        runBlocking {
            val holderStarted = CompletableDeferred<Unit>()
            val failHolder = CompletableDeferred<Unit>()
            // Each read with a number above this one fails, as with a cache that is down
            val failReadsAfter = AtomicInteger(Int.MAX_VALUE)
            val shield =
                shield(
                    getCache = { cacheKey ->
                        check(cacheReads.get() <= failReadsAfter.get()) { "the cache is down" }
                        cache[cacheKey]
                    },
                )
            val holder = callers.failingHolder(shield, holderStarted, failHolder)
            withTimeout(2_000) { holderStarted.await() }
            val waiterSupplierCalls = AtomicInteger()
            val waiter =
                callers.async {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            waiterSupplierCalls.incrementAndGet()
                            product
                        },
                        10_000,
                    )
                }
            // Holder: 2 reads. Waiter: 1 read before the lock attempt and 1 read after it.
            awaitReads(4)
            try {
                // Each read from now on fails, and the holder gives the lock back without an entry
                failReadsAfter.set(4)
                failHolder.complete(Unit)

                assertEquals(product, withTimeout(2_000) { waiter.await() }.value)
                assertEquals(1, waiterSupplierCalls.get())
                // One failed read after the release. No read under a creation lock follows.
                assertEquals(5, cacheReads.get())
                withTimeout(2_000) { holder.await() }
            } finally {
                failReadsAfter.set(Int.MAX_VALUE)
            }
            assertNull(signal())
        }

    /** Starts a refresh of the old entry whose supplier ends only when the returned signal completes. */
    private suspend fun startHungRefresh(shield: ReqShield<Product>): CompletableDeferred<Unit> {
        cache[key] = oldEntry()
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        shield.getAndSetReqShieldData(
            key,
            {
                refreshStarted.complete(Unit)
                finishRefresh.await()
                Product("refreshed", "refreshed")
            },
            10_000,
        )
        withTimeout(2_000) { refreshStarted.await() }
        return finishRefresh
    }

    @Test
    fun aRequestShouldNotWaitForARefreshWhoseLockTimedOut() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 200)
            val finishRefresh = startHungRefresh(shield)
            try {
                // The lock of the refresh timed out: another request could take the refresh over
                delay(300)
                cache.remove(key)
                val readsBefore = cacheReads.get()
                val supplierCalls = AtomicInteger()
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

                val millis = (System.nanoTime() - startedAtNanos) / 1_000_000
                assertEquals(product, data.value)
                assertEquals(1, supplierCalls.get())
                assertTrue(millis < 1_000, "the request took $millis ms")
                // The read on the miss and the read under the creation lock. No read of a wait.
                assertEquals(2, cacheReads.get() - readsBefore)
            } finally {
                finishRefresh.complete(Unit)
            }
            withTimeout(2_000) {
                while (refreshSignal() != null) delay(1)
            }
        }

    @Test
    fun aRequestShouldStopWaitingForARefreshWhenTheLockOfTheRefreshTimesOut() =
        runBlocking {
            val shield = shield(lockTimeoutMillis = 300)
            val finishRefresh = startHungRefresh(shield)
            try {
                cache.remove(key)
                val supplierCalls = AtomicInteger()
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

                val millis = (System.nanoTime() - startedAtNanos) / 1_000_000
                assertEquals(product, data.value)
                assertEquals(1, supplierCalls.get())
                // The wait ends with the lock timeout of 300 ms, not with the 60 reads of about 3 s
                assertTrue(millis in 150..1_500, "the request took $millis ms")
            } finally {
                finishRefresh.complete(Unit)
            }
            withTimeout(2_000) {
                while (refreshSignal() != null) delay(1)
            }
        }

    @Test
    fun aRequestShouldCreateTheEntryWhenARefreshOutlastsItsReads() =
        runBlocking {
            cache[key] = oldEntry()
            val refreshStarted = CompletableDeferred<Unit>()
            val finishRefresh = CompletableDeferred<Unit>()
            // Two reads, 50 ms apart, are all that a request spends on the refresh
            val shield = shield(maxAttemptGetCache = 2)
            try {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        refreshStarted.complete(Unit)
                        finishRefresh.await()
                        Product("refreshed", "refreshed")
                    },
                    10_000,
                )
                withTimeout(2_000) { refreshStarted.await() }
                cache.remove(key)
                val readsBefore = cacheReads.get()
                val supplierCalls = AtomicInteger()

                val data =
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierCalls.incrementAndGet()
                            product
                        },
                        10_000,
                    )

                assertEquals(product, data.value)
                assertEquals(1, supplierCalls.get())
                // 1 read before the wait, maxAttemptGetCache reads in the wait, 1 read under the creation lock
                assertEquals(1 + 2 + 1, cacheReads.get() - readsBefore)
            } finally {
                finishRefresh.complete(Unit)
            }
            awaitScopeTasks()
            assertNull(refreshSignal())
        }

    @Test
    fun cancellingOneWaiterShouldNotWakeOrCancelTheOthers() =
        runBlocking {
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            val supplierCalls = AtomicInteger()
            val supplier: suspend () -> Product? = {
                supplierCalls.incrementAndGet()
                supplierStarted.complete(Unit)
                finishSupplier.await()
                product
            }
            val shield = shield()
            val holder = callers.async { shield.getAndSetReqShieldData(key, supplier, 10_000) }
            withTimeout(2_000) { supplierStarted.await() }
            val cancelled = callers.async { shield.getAndSetReqShieldData(key, supplier, 10_000) }
            val other = callers.async { shield.getAndSetReqShieldData(key, supplier, 10_000) }
            // The holder read twice, and each waiter read before its lock attempt and once in its wait
            awaitReads(6)

            cancelled.cancelAndJoin()
            val readsAtCancellation = cacheReads.get()
            // Three poll intervals: a woken or cancelled waiter would end, or read in a loop, in this time
            delay(150)

            assertTrue(other.isActive, "the other waiter ended before the holder's release")
            val openSignal = assertNotNull(signal())
            assertFalse(openSignal.isCompleted, "the signal of the holder was completed or cancelled")
            val readsWhileWaiting = cacheReads.get() - readsAtCancellation
            assertTrue(readsWhileWaiting <= 5, "the other waiter read $readsWhileWaiting times in 150 ms")

            finishSupplier.complete(Unit)

            // A waiter without the signal still finds the entry at its next poll, at most 50 ms later, so no time
            // bound can tell the two apart. This bound only catches a waiter that stops reading. How soon the signal
            // wakes a waiter is measured by aWaiterShouldGetTheEntryRightAfterTheHolderWroteIt.
            assertEquals(product, withTimeout(1_000) { other.await() }.value)
            assertEquals(product, holder.await().value)
            assertEquals(product, cache[key]?.value)
            assertTrue(cancelled.isCancelled)
            assertEquals(1, supplierCalls.get())
        }

    @Test
    fun noSignalShouldStayAfterACacheWrite() =
        runBlocking {
            var signalDuringSupplier: CompletableDeferred<Unit>? = null

            shield().getAndSetReqShieldData(
                key,
                {
                    signalDuringSupplier = signal()
                    product
                },
                10_000,
            )
            awaitScopeTasks()

            assertNotNull(signalDuringSupplier, "the holder registered no signal")
            assertTrue(signalDuringSupplier!!.isCompleted)
            assertNull(signal())
            assertEquals(product, cache[key]?.value)
        }

    @Test
    fun noSignalShouldStayWhenTheEntryAppearsBeforeTheLock() =
        runBlocking {
            val entry = ReqShieldData(product, 10_000)
            var signalDuringRecheck: CompletableDeferred<Unit>? = null
            // Missing at the first read, there at the read under the lock
            val shield =
                shield(getCache = {
                    if (cacheReads.get() == 1) {
                        null
                    } else {
                        signalDuringRecheck = signal()
                        entry
                    }
                })

            assertEquals(product, shield.getAndSetReqShieldData(key, { error("not called") }, 10_000).value)

            assertNotNull(signalDuringRecheck, "the holder registered no signal")
            assertNull(signal())
        }

    @Test
    fun noSignalShouldStayWhenTheSupplierFails() =
        runBlocking {
            var signalDuringSupplier: CompletableDeferred<Unit>? = null

            assertFailsWith<ClientException> {
                shield().getAndSetReqShieldData(
                    key,
                    {
                        signalDuringSupplier = signal()
                        error("the supplier fails")
                    },
                    10_000,
                )
            }

            assertNotNull(signalDuringSupplier, "the holder registered no signal")
            assertNull(signal())
        }

    @Test
    fun noSignalShouldStayWhenTheHolderIsCancelled() =
        runBlocking {
            val supplierStarted = CompletableDeferred<Unit>()
            val shield = shield()
            val holder =
                callers.async {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierStarted.complete(Unit)
                            awaitCancellation()
                        },
                        10_000,
                    )
                }
            withTimeout(2_000) { supplierStarted.await() }
            val signalOfHolder = assertNotNull(signal())

            holder.cancelAndJoin()

            assertTrue(signalOfHolder.isCompleted)
            assertNull(signal())
        }

    @Test
    fun noSignalShouldStayWhenTheUnlockFails() =
        runBlocking {
            val unlockCalls = AtomicInteger()
            val localLock = KeyLocalLock(3000)
            val failingUnlock =
                object : KeyLock {
                    override suspend fun tryLock(
                        key: String,
                        lockType: LockType,
                    ): String? = localLock.tryLock(key, lockType)

                    override suspend fun unLock(
                        key: String,
                        lockType: LockType,
                        token: String,
                    ): Boolean {
                        unlockCalls.incrementAndGet()
                        localLock.unLock(key, lockType, token)
                        error("the lock store fails")
                    }
                }

            shield(keyLock = failingUnlock).getAndSetReqShieldData(key, { product }, 10_000)
            awaitScopeTasks()

            assertEquals(1, unlockCalls.get())
            assertNull(signal())
            assertEquals(product, cache[key]?.value)
        }

    @Test
    fun noSignalShouldStayWhenALateCacheWriteIsSkipped() =
        runBlocking {
            val other = Product("other", "other")
            var signalDuringSupplier: CompletableDeferred<Unit>? = null
            // The lock times out during the supplier call, and another request writes the entry in that time
            val shield = shield(lockTimeoutMillis = 20)

            shield.getAndSetReqShieldData(
                key,
                {
                    signalDuringSupplier = signal()
                    delay(60)
                    cache[key] = ReqShieldData(other, 10_000)
                    product
                },
                10_000,
            )
            awaitScopeTasks()

            assertNotNull(signalDuringSupplier, "the holder registered no signal")
            assertNull(signal())
            assertEquals(other, cache[key]?.value)
        }

    @Test
    fun theReleaseOfARefreshLockShouldNotCompleteACreationSignal() =
        runBlocking {
            // Both lock types get the same token, so only the lock type tells the two releases apart
            val sameToken = "same-token"
            val unlocks = mutableListOf<LockType>()
            val keyLock =
                object : KeyLock {
                    override suspend fun tryLock(
                        key: String,
                        lockType: LockType,
                    ): String = sameToken

                    override suspend fun unLock(
                        key: String,
                        lockType: LockType,
                        token: String,
                    ): Boolean {
                        synchronized(unlocks) { unlocks += lockType }
                        return true
                    }
                }
            val shield = shield(keyLock = keyLock)
            val creationSignal = CompletableDeferred<Unit>()
            shield.creationSignals.register(key, sameToken, creationSignal)
            cache[key] = oldEntry()
            var refreshSignalDuringSupplier: CompletableDeferred<Unit>? = null

            try {
                shield.getAndSetReqShieldData(
                    key,
                    {
                        refreshSignalDuringSupplier = refreshSignal()
                        product
                    },
                    10_000,
                )
                awaitScopeTasks()

                assertEquals(listOf(LockType.UPDATE), synchronized(unlocks) { unlocks.toList() })
                assertFalse(creationSignal.isCompleted)
                assertEquals(creationSignal, signal())
                // The release of the refresh lock completed the signal of the refresh instead
                assertNotNull(refreshSignalDuringSupplier, "the refresh registered no signal")
                assertTrue(refreshSignalDuringSupplier!!.isCompleted)
                assertNull(refreshSignal())
            } finally {
                shield.creationSignals.release(key, sameToken)
            }
        }

    @Test
    fun noRefreshSignalShouldStayAfterARefreshWroteTheEntry() =
        runBlocking {
            cache[key] = oldEntry()
            var signalDuringRefresh: CompletableDeferred<Unit>? = null

            shield().getAndSetReqShieldData(
                key,
                {
                    signalDuringRefresh = refreshSignal()
                    product
                },
                10_000,
            )
            awaitScopeTasks()

            assertNotNull(signalDuringRefresh, "the refresh registered no signal")
            assertTrue(signalDuringRefresh!!.isCompleted)
            assertNull(refreshSignal())
            assertEquals(product, cache[key]?.value)
        }

    @Test
    fun noRefreshSignalShouldStayAfterTheSupplierOfARefreshFailed() =
        runBlocking {
            val old = oldEntry()
            cache[key] = old
            var signalDuringRefresh: CompletableDeferred<Unit>? = null

            shield().getAndSetReqShieldData(
                key,
                {
                    signalDuringRefresh = refreshSignal()
                    error("the supplier of the refresh fails")
                },
                10_000,
            )
            awaitScopeTasks()

            assertNotNull(signalDuringRefresh, "the refresh registered no signal")
            assertTrue(signalDuringRefresh!!.isCompleted)
            assertNull(refreshSignal())
            assertEquals(old, cache[key])
        }

    @Test
    fun noRefreshSignalShouldStayAfterARefreshWasCancelled() =
        runBlocking {
            cache[key] = oldEntry()
            val lock = KeyLocalLock(60_000)
            val refreshStarted = CompletableDeferred<Unit>()

            shield(keyLock = lock).getAndSetReqShieldData(
                key,
                {
                    refreshStarted.complete(Unit)
                    awaitCancellation()
                },
                10_000,
            )
            withTimeout(2_000) { refreshStarted.await() }
            val signalOfRefresh = assertNotNull(refreshSignal())
            cancelTheScope()

            assertTrue(signalOfRefresh.isCompleted)
            assertNull(refreshSignal())
            assertTrue(lock.isFree(LockType.UPDATE), "the refresh lock is still held")
        }

    @Test
    fun noRefreshSignalShouldStayWhenALateWriteOfARefreshIsSkipped() =
        runBlocking {
            cache[key] = oldEntry()
            val other = Product("other", "other")
            // The lock store keeps the lock for a minute, so only a release frees it during the test
            val lock = KeyLocalLock(60_000)
            var signalDuringRefresh: CompletableDeferred<Unit>? = null

            // The lock times out during the supplier call, and another request writes the entry in that time
            shield(lockTimeoutMillis = 20, keyLock = lock).getAndSetReqShieldData(
                key,
                {
                    signalDuringRefresh = refreshSignal()
                    delay(60)
                    cache[key] = ReqShieldData(other, 10_000)
                    product
                },
                10_000,
            )
            awaitScopeTasks()

            assertNotNull(signalDuringRefresh, "the refresh registered no signal")
            assertTrue(signalDuringRefresh!!.isCompleted)
            assertNull(refreshSignal())
            assertEquals(other, cache[key]?.value)
            assertTrue(lock.isFree(LockType.UPDATE), "the refresh lock is still held")
        }

    @Test
    fun aRefreshUnderTheLocalRefreshLockOfOnlyCreateCacheShouldRegisterASignal() =
        runBlocking {
            cache[key] = oldEntry()
            var signalDuringRefresh: CompletableDeferred<Unit>? = null

            shield(workMode = ReqShieldWorkMode.ONLY_CREATE_CACHE).getAndSetReqShieldData(
                key,
                {
                    signalDuringRefresh = refreshSignal()
                    product
                },
                10_000,
            )
            awaitScopeTasks()

            assertNotNull(signalDuringRefresh, "the refresh registered no signal")
            assertTrue(signalDuringRefresh!!.isCompleted)
            assertNull(refreshSignal())
            assertEquals(product, cache[key]?.value)
        }

    /**
     * Runs a request whose lock times out during its supplier call, and cancels the shield's scope while the
     * background task reads the cache again before its late write. [readBeforeLateWrite] is the number of that read.
     *
     * @return the signal of the holder, as the supplier saw it.
     */
    private suspend fun cancelTheScopeDuringTheReadBeforeALateWrite(
        lock: KeyLock,
        readBeforeLateWrite: Int,
        signalOfHolder: () -> CompletableDeferred<Unit>?,
    ): CompletableDeferred<Unit>? {
        val readStarted = CompletableDeferred<Unit>()
        var signalDuringSupplier: CompletableDeferred<Unit>? = null
        val shield =
            shield(lockTimeoutMillis = 20, keyLock = lock, getCache = { cacheKey ->
                if (cacheReads.get() == readBeforeLateWrite) {
                    readStarted.complete(Unit)
                    awaitCancellation()
                }
                cache[cacheKey]
            })

        shield.getAndSetReqShieldData(
            key,
            {
                signalDuringSupplier = signalOfHolder()
                delay(60)
                product
            },
            10_000,
        )
        withTimeout(2_000) { readStarted.await() }
        cancelTheScope()
        return signalDuringSupplier
    }

    @Test
    fun theCreationLockShouldBeReleasedWhenTheScopeIsCancelledDuringTheReadBeforeALateWrite() =
        runBlocking {
            // The lock store keeps the lock for a minute, so only a release frees it during the test
            val lock = KeyLocalLock(60_000)

            // Read 1: the miss. Read 2: the read under the lock. Read 3: the read before the late write.
            val signalOfHolder = cancelTheScopeDuringTheReadBeforeALateWrite(lock, readBeforeLateWrite = 3) { signal() }

            assertNotNull(signalOfHolder, "the holder registered no signal")
            assertTrue(signalOfHolder!!.isCompleted, "the signal of the holder was not completed")
            assertNull(signal())
            assertTrue(lock.isFree(LockType.CREATE), "the creation lock is still held")
            assertNull(cache[key])
        }

    @Test
    fun theRefreshLockShouldBeReleasedWhenTheScopeIsCancelledDuringTheReadBeforeALateWrite() =
        runBlocking {
            val old = oldEntry()
            cache[key] = old
            val lock = KeyLocalLock(60_000)

            // Read 1: the old entry, which starts the refresh. Read 2: the read before the late write.
            val signalOfRefresh = cancelTheScopeDuringTheReadBeforeALateWrite(lock, readBeforeLateWrite = 2) { refreshSignal() }

            assertNotNull(signalOfRefresh, "the refresh registered no signal")
            assertTrue(signalOfRefresh!!.isCompleted, "the signal of the refresh was not completed")
            assertNull(refreshSignal())
            assertTrue(lock.isFree(LockType.UPDATE), "the refresh lock is still held")
            assertEquals(old, cache[key])
        }

    /** A scope whose dispatcher throws [error], so `launch` on it throws and its task never runs. */
    private fun scopeWhoseDispatcherThrows(error: Throwable = IllegalStateException("the dispatcher is down")) =
        CoroutineScope(
            SupervisorJob() +
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = throw error
                },
        )

    @Test
    fun theCallerShouldGetItsDataAndTheCreationLockShouldBeReleasedWhenTheScopeCannotStartTheCacheWrite() =
        runBlocking {
            val lock = KeyLocalLock(60_000)
            val shield = shield(keyLock = lock, scope = scopeWhoseDispatcherThrows())

            // The supplier already returned, so only the cache write is skipped
            val result = shield.getAndSetReqShieldData(key, { product }, 10_000)

            assertEquals(product, result.value)
            assertNull(cache[key], "the cache write ran although the scope could not start it")
            assertNull(signal())
            assertTrue(lock.isFree(LockType.CREATE), "the creation lock is still held")
        }

    @Test
    fun theCallerShouldGetTheEntryItReadAndTheRefreshLockShouldBeReleasedWhenTheScopeCannotStartTheRefresh() =
        runBlocking {
            val old = oldEntry()
            cache[key] = old
            val lock = KeyLocalLock(60_000)
            val shield = shield(keyLock = lock, scope = scopeWhoseDispatcherThrows())
            val supplierCalls = AtomicInteger()

            // The caller read an entry, so only the refresh is skipped
            val result =
                shield.getAndSetReqShieldData(
                    key,
                    {
                        supplierCalls.incrementAndGet()
                        product
                    },
                    10_000,
                )

            assertEquals(old, result)
            assertEquals(0, supplierCalls.get(), "the supplier of the refresh was called")
            assertEquals(old, cache[key])
            assertNull(refreshSignal())
            assertTrue(lock.isFree(LockType.UPDATE), "the refresh lock is still held")
        }

    @Test
    fun theCallerShouldGetItsCancellationWhenItIsCancelledWhileTheScopeCannotStartTheCacheWrite() =
        runBlocking {
            val lock = KeyLocalLock(60_000)
            val caller = AtomicReference<Job>()
            val returned = AtomicReference<ReqShieldData<Product>>()
            // The dispatcher cancels the caller and then throws the caller's cancellation
            val scope =
                CoroutineScope(
                    SupervisorJob() +
                        object : CoroutineDispatcher() {
                            override fun dispatch(
                                context: CoroutineContext,
                                block: Runnable,
                            ) {
                                caller.get().cancel()
                                throw CancellationException("the caller is cancelled")
                            }
                        },
                )
            val shield = shield(keyLock = lock, scope = scope)

            val request =
                callers.async {
                    caller.set(coroutineContext.job)
                    returned.set(shield.getAndSetReqShieldData(key, { product }, 10_000))
                }

            withTimeout(2_000) { request.join() }
            assertTrue(request.isCancelled, "the caller was not cancelled")
            // The cancellation goes on instead of the data
            assertNull(returned.get(), "the cancelled caller got its data")
            assertNull(signal())
            assertTrue(lock.isFree(LockType.CREATE), "the creation lock is still held")
        }

    @Test
    fun theCallerShouldGetAnErrorOfTheDispatcherAfterTheCreationLockWasReleased() =
        runBlocking {
            val lock = KeyLocalLock(60_000)
            val shield = shield(keyLock = lock, scope = scopeWhoseDispatcherThrows(NotImplementedError("the dispatcher throws an Error")))

            // An Error is not a failure to start the task, so it goes on unchanged
            assertFailsWith<NotImplementedError> { shield.getAndSetReqShieldData(key, { product }, 10_000) }

            assertNull(signal())
            assertTrue(lock.isFree(LockType.CREATE), "the creation lock is still held")
        }

    @Test
    fun theCreationLockShouldBeReleasedWhenTheSupplierThrowsAnError() =
        runBlocking {
            val lock = KeyLocalLock(60_000)
            var signalDuringSupplier: CompletableDeferred<Unit>? = null

            // An Error is not a failure of the supplier, so the caller gets it unchanged
            assertFailsWith<NotImplementedError> {
                shield(keyLock = lock).getAndSetReqShieldData(
                    key,
                    {
                        signalDuringSupplier = signal()
                        throw NotImplementedError("the supplier throws an Error")
                    },
                    10_000,
                )
            }

            assertNotNull(signalDuringSupplier, "the holder registered no signal")
            assertTrue(signalDuringSupplier!!.isCompleted, "the signal of the holder was not completed")
            assertNull(signal())
            assertTrue(lock.isFree(LockType.CREATE), "the creation lock is still held")
        }
}
