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
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A request that waits for the holder of a creation lock or for a refresh waits for the signal of that holder, not
 * for a whole poll interval. The poll interval is 50 ms, so a wait that ends within 25 ms of the release was ended
 * by the signal.
 */
class ReqShieldCreationSignalTest {
    /** The ReqShield that the test made last. Its signals and guard locks are the ones the test looks at. */
    private lateinit var created: ReqShield<Product>

    // The signals and the local locks are shared by all instances, so each test has its own key
    private val key = "creation-signal-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()

    // The time of each read of the default cache function, by its number
    private val readAtNanos = ConcurrentHashMap<Int, Long>()
    private val product = Product("id", "name")
    private val timeout = Duration.ofSeconds(5)

    private fun shield(
        writeMillis: Long = 0,
        maxAttemptGetCache: Int = 60,
        lockTimeoutMillis: Long = 3000,
        scheduler: Scheduler = Schedulers.boundedElastic(),
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        keyLock: KeyLock? = null,
        getCacheFunction: (String) -> Mono<ReqShieldData<Product>?> = { cacheKey ->
            Mono.fromCallable {
                readAtNanos[cacheReads.incrementAndGet()] = System.nanoTime()
                cache[cacheKey]
            }
        },
    ): ReqShield<Product> {
        val configuration =
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    Mono.fromCallable {
                        Thread.sleep(writeMillis)
                        cache[cacheKey] = data
                        true
                    }
                },
                getCacheFunction = getCacheFunction,
                scheduler = scheduler,
                maxAttemptGetCache = maxAttemptGetCache,
                lockTimeoutMillis = lockTimeoutMillis,
                reqShieldWorkMode = workMode,
            )
        return ReqShield(if (keyLock == null) configuration else configuration.copy(keyLock = keyLock)).also { created = it }
    }

    /** A supplier that emits [product] once [finish] completes, and counts its calls in [calls]. */
    private fun blockedSupplier(
        finish: CompletableFuture<Product?>,
        calls: AtomicInteger,
    ): Callable<Mono<Product?>> =
        Callable {
            calls.incrementAndGet()
            Mono.fromFuture(finish)
        }

    private fun countingSupplier(calls: AtomicInteger): Callable<Mono<Product?>> =
        Callable {
            calls.incrementAndGet()
            Mono.just(product)
        }

    /** The release runs in doFinally, which can run after the caller got its result, so the removal is awaited. */
    private fun awaitNoSignal() = await().atMost(Duration.ofSeconds(2)).until { created.creationSignals.find(key) == null }

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /**
     * Waits for read [readNumber] of the default cache function and sleeps until 70 ms after it. A poll at a fixed
     * 50 ms interval that started at that read is then 30 ms away from its next read. Returns the time it woke at.
     */
    private fun sleepUntil70MillisAfterRead(readNumber: Int): Long {
        // Checked each millisecond, so the read time is known at once
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { cacheReads.get() >= readNumber }
        val readAt = readAtNanos.getValue(readNumber)
        val wakeAtNanos = readAt + TimeUnit.MILLISECONDS.toNanos(70)
        // Sleep overshoots, so the last 5 ms are spun to keep the release away from the next poll
        Thread.sleep(maxOf(0, 65 - millisSince(readAt)))
        while (System.nanoTime() < wakeAtNanos) Thread.onSpinWait()
        return System.nanoTime()
    }

    @Test
    fun aWaiterShouldGetTheEntryRightAfterTheHolderWroteIt() {
        val finishSupplier = CompletableFuture<Product?>()
        val supplierCalls = AtomicInteger()
        val shield = shield()
        val holder = shield.getAndSetReqShieldData(key, blockedSupplier(finishSupplier, supplierCalls), 10_000).toFuture()
        // The holder read the cache twice: before the lock and after it
        await().atMost(Duration.ofSeconds(2)).until { cacheReads.get() == 2 && supplierCalls.get() == 1 }

        val waiterEndedAtNanos =
            shield
                .getAndSetReqShieldData(key, blockedSupplier(finishSupplier, supplierCalls), 10_000)
                .map { System.nanoTime() }
                .toFuture()
        // Read 3 is the waiter's read before the lock attempt, and read 4 its first read after it
        val releasedAtNanos = sleepUntil70MillisAfterRead(4)
        finishSupplier.complete(product)

        val waitAfterReleaseMillis = (waiterEndedAtNanos.get(2, TimeUnit.SECONDS) - releasedAtNanos) / 1_000_000
        assertTrue(waitAfterReleaseMillis < 25, "the waiter ended $waitAfterReleaseMillis ms after the release")
        assertEquals(product, holder.get(2, TimeUnit.SECONDS).value)
        assertEquals(1, supplierCalls.get())
    }

    @Test
    fun aSecondReadOfANewKeyShouldWaitForTheCacheWriteAndNotForAPollInterval() {
        val supplierCalls = AtomicInteger()
        // The first read returns before its cache write, which takes 20 ms
        val shield = shield(writeMillis = 20)

        shield.getAndSetReqShieldData(key, countingSupplier(supplierCalls), 10_000).block(timeout)
        val startedAtNanos = System.nanoTime()
        val second = shield.getAndSetReqShieldData(key, countingSupplier(supplierCalls), 10_000).block(timeout)
        val secondReadMillis = millisSince(startedAtNanos)

        assertEquals(product, second?.value)
        assertEquals(1, supplierCalls.get())
        assertTrue(secondReadMillis < 45, "the second read took $secondReadMillis ms")
    }

    @Test
    fun aWaiterWithoutASignalShouldKeepThePollIntervalAndTheReadCount() {
        // A lock that is held without a signal: the holder is in another JVM, for example
        val lock = KeyLocalLock(3000)
        val token = checkNotNull(lock.tryLock(key, LockType.CREATE).block(timeout))
        val supplierCalls = AtomicInteger()
        val shield = shield(maxAttemptGetCache = 4)

        try {
            val startedAtNanos = System.nanoTime()
            val data = shield.getAndSetReqShieldData(key, countingSupplier(supplierCalls), 10_000).block(timeout)
            val waitedMillis = millisSince(startedAtNanos)

            assertEquals(product, data?.value)
            // One read before the lock attempt, then maxAttemptGetCache reads with three poll intervals between them
            assertEquals(5, cacheReads.get())
            assertTrue(waitedMillis >= 150, "the waiter gave up after $waitedMillis ms")
            assertEquals(1, supplierCalls.get())
        } finally {
            lock.unLock(key, LockType.CREATE, token).block(timeout)
        }
    }

    @Test
    fun aWaiterShouldBeTheNextHolderRightAfterTheHolderFailed() {
        val failHolder = CompletableFuture<Product?>()
        val holderCalls = AtomicInteger()
        val shield = shield()
        val holder = shield.getAndSetReqShieldData(key, blockedSupplier(failHolder, holderCalls), 10_000).toFuture()
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { holderCalls.get() == 1 }
        val waiterCalls = AtomicInteger()
        val waiters =
            List(2) {
                shield
                    .getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000)
                    .map { it.value to System.nanoTime() }
                    .toFuture()
            }
        // Holder: 2 reads. Each waiter: 1 read before the lock attempt and its first read after it.
        val failedAtNanos = sleepUntil70MillisAfterRead(6)
        failHolder.completeExceptionally(IllegalStateException("the supplier of the holder fails"))

        val holderError = assertThrows(Exception::class.java) { holder.get(2, TimeUnit.SECONDS) }
        assertTrue(holderError.cause is ClientException, "the holder failed with ${holderError.cause}")
        waiters.forEach { waiter ->
            // Without the retry of the lock, a waiter reads at the 50 ms interval until its 60 reads are used up
            val (value, endedAtNanos) = waiter.get(5, TimeUnit.SECONDS)
            assertEquals(product, value)
            val waitAfterFailureMillis = (endedAtNanos - failedAtNanos) / 1_000_000
            assertTrue(waitAfterFailureMillis < 25, "a waiter ended $waitAfterFailureMillis ms after the failure")
        }
        // One waiter got the lock and called the supplier. The other one got the entry of that waiter.
        assertEquals(1, waiterCalls.get())
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        awaitNoSignal()
    }

    @Test
    fun aWaiterShouldCallTheSupplierItselfWhenItsReadsAreUsedUpAfterAFailedHolder() {
        val failHolder = CompletableFuture<Product?>()
        val holderCalls = AtomicInteger()
        // One read after the lost lock: the read that follows the release is the last one
        val shield = shield(maxAttemptGetCache = 2)
        val holder = shield.getAndSetReqShieldData(key, blockedSupplier(failHolder, holderCalls), 10_000).toFuture()
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { holderCalls.get() == 1 }
        val waiterCalls = AtomicInteger()
        val waiter = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).toFuture()
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { cacheReads.get() >= 4 }

        failHolder.completeExceptionally(IllegalStateException("the supplier of the holder fails"))

        assertThrows(Exception::class.java) { holder.get(2, TimeUnit.SECONDS) }
        assertEquals(product, waiter.get(2, TimeUnit.SECONDS).value)
        assertEquals(1, waiterCalls.get())
        // Holder: 2 reads. Waiter: 1 read before the lock attempt and maxAttemptGetCache reads after it.
        assertTrue(cacheReads.get() <= 6, "the cache was read ${cacheReads.get()} times")
        awaitNoSignal()
    }

    @Test
    fun aWaiterThatLosesTheLockAgainWithItsReadsUsedUpShouldCallTheSupplierAtOnce() {
        // Only the first lock attempt wins. The retry of the waiter loses, as to a holder in another JVM.
        val localLock = KeyLocalLock(3000)
        val lockAttempts = AtomicInteger()
        val lock =
            object : KeyLock by localLock {
                override fun tryLock(
                    key: String,
                    lockType: LockType,
                ): Mono<String> = if (lockAttempts.incrementAndGet() == 1) localLock.tryLock(key, lockType) else Mono.empty()
            }
        val failHolder = CompletableFuture<Product?>()
        val holderCalls = AtomicInteger()
        val shield = shield(maxAttemptGetCache = 2, keyLock = lock)
        val holder = shield.getAndSetReqShieldData(key, blockedSupplier(failHolder, holderCalls), 10_000).toFuture()
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { holderCalls.get() == 1 }
        val waiterCalls = AtomicInteger()
        val waiter =
            shield
                .getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000)
                .map { it.value to System.nanoTime() }
                .toFuture()
        // Holder: 2 reads. Waiter: 1 read before the lock attempt and its first read after it.
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { cacheReads.get() >= 4 }

        val failedAtNanos = System.nanoTime()
        failHolder.completeExceptionally(IllegalStateException("the supplier of the holder fails"))

        assertThrows(Exception::class.java) { holder.get(2, TimeUnit.SECONDS) }
        val (value, endedAtNanos) = waiter.get(2, TimeUnit.SECONDS)
        assertEquals(product, value)
        assertEquals(1, waiterCalls.get())
        assertEquals(3, lockAttempts.get(), "the waiter tries the lock again after the failure")
        // The read after the failure was the last one, so the waiter does not wait for the holder it lost to
        assertEquals(2 + 1 + 2, cacheReads.get())
        val waitAfterFailureMillis = (endedAtNanos - failedAtNanos) / 1_000_000
        assertTrue(waitAfterFailureMillis < 25, "the waiter ended $waitAfterFailureMillis ms after the failure")
        awaitNoSignal()
    }

    @Test
    fun aWaiterThatStopsWaitingShouldNotWakeOrCancelTheOtherWaiters() {
        val finishSupplier = CompletableFuture<Product?>()
        val holderCalls = AtomicInteger()
        val shield = shield()
        val holder = shield.getAndSetReqShieldData(key, blockedSupplier(finishSupplier, holderCalls), 10_000).toFuture()
        await().atMost(Duration.ofSeconds(2)).until { holderCalls.get() == 1 }
        val waiterCalls = AtomicInteger()
        val cancelledWaiter: Disposable = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).subscribe()
        val otherWaiter = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).toFuture()
        // Holder: 2 reads. Each waiter: 1 read before the lock attempt and its first read after it.
        await().atMost(Duration.ofSeconds(2)).until { cacheReads.get() >= 6 }

        cancelledWaiter.dispose()
        // Several poll intervals, in which the other waiter also stops each of its waits for the signal
        Thread.sleep(150)

        assertFalse(otherWaiter.isDone, "the other waiter must still wait")
        val signal = created.creationSignals.find(key)
        assertNotNull(signal)
        assertFalse(signal!!.isDone, "the signal must still be pending")
        finishSupplier.complete(product)

        // A waiter without the signal still finds the entry at its next poll, at most 50 ms later, so no time bound
        // can tell the two apart. This bound only catches a waiter that stops reading. How soon the signal wakes a
        // waiter is measured by aWaiterShouldGetTheEntryRightAfterTheHolderWroteIt.
        assertEquals(product, otherWaiter.get(1, TimeUnit.SECONDS).value)
        assertEquals(product, holder.get(2, TimeUnit.SECONDS).value)
        assertEquals(0, waiterCalls.get())
    }

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(Product("old", "old"), ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    /**
     * Gets the old entry from [shield], which starts a refresh with [refreshSupplier], and waits until the refresh
     * calls its supplier. The refresh runs on the scheduler, so it may start after the caller got the old entry.
     */
    private fun startRefresh(
        shield: ReqShield<Product>,
        refreshSupplier: Callable<Mono<Product?>>,
        refreshCalls: AtomicInteger,
    ) {
        cache[key] = oldEntry()
        assertEquals("old", shield.getAndSetReqShieldData(key, refreshSupplier, 10_000).block(timeout)?.value?.id)
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { refreshCalls.get() == 1 }
    }

    /** The refresh registers its signal before its supplier call, so the signal is awaited from that call on. */
    private fun recordingRefreshSupplier(
        signalDuringRefresh: AtomicReference<CompletableFuture<Unit>?>,
        result: () -> Mono<Product?>,
    ): Callable<Mono<Product?>> =
        Callable {
            signalDuringRefresh.set(created.refreshSignals.find(key))
            result()
        }

    private fun awaitNoRefreshSignal(signalDuringRefresh: AtomicReference<CompletableFuture<Unit>?>) {
        // Without this, the check below could pass before the refresh even registered its signal
        await().atMost(Duration.ofSeconds(2)).until { signalDuringRefresh.get() != null }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
        assertTrue(signalDuringRefresh.get()!!.isDone, "the release completes the signal")
    }

    @Test
    fun aRequestThatFindsTheEntryGoneDuringARefreshShouldWaitForTheRefresh() {
        val finishRefresh = CompletableFuture<Product?>()
        val supplierCalls = AtomicInteger()
        val shield = shield()
        startRefresh(shield, blockedSupplier(finishRefresh, supplierCalls), supplierCalls)
        // The old entry expires before the refresh ends
        cache.remove(key)

        val readsBefore = cacheReads.get()
        val waiter =
            shield
                .getAndSetReqShieldData(key, blockedSupplier(finishRefresh, supplierCalls), 10_000)
                .map { it.value to System.nanoTime() }
                .toFuture()
        // The waiter reads once on its miss, and its next read is its first read during the refresh
        val refreshEndedAtNanos = sleepUntil70MillisAfterRead(readsBefore + 2)
        assertEquals(1, supplierCalls.get(), "the waiting request must not call the supplier")
        finishRefresh.complete(product)

        val (value, endedAtNanos) = waiter.get(2, TimeUnit.SECONDS)
        assertEquals(product, value)
        val waitAfterRefreshMillis = (endedAtNanos - refreshEndedAtNanos) / 1_000_000
        assertTrue(waitAfterRefreshMillis < 25, "the request ended $waitAfterRefreshMillis ms after the refresh")
        assertEquals(1, supplierCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun aRequestThatWaitedForAFailedRefreshShouldCreateTheEntryAtOnce() {
        val failRefresh = CompletableFuture<Product?>()
        val refreshCalls = AtomicInteger()
        val shield = shield()
        startRefresh(shield, blockedSupplier(failRefresh, refreshCalls), refreshCalls)
        cache.remove(key)

        val readsBefore = cacheReads.get()
        val waiterCalls = AtomicInteger()
        val waiter =
            shield
                .getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000)
                .map { it.value to System.nanoTime() }
                .toFuture()
        val failedAtNanos = sleepUntil70MillisAfterRead(readsBefore + 2)
        assertEquals(0, waiterCalls.get(), "the waiting request must not call the supplier before the refresh ends")
        failRefresh.completeExceptionally(IllegalStateException("the supplier of the refresh fails"))

        val (value, endedAtNanos) = waiter.get(2, TimeUnit.SECONDS)
        assertEquals(product, value)
        val waitAfterFailureMillis = (endedAtNanos - failedAtNanos) / 1_000_000
        assertTrue(waitAfterFailureMillis < 25, "the request ended $waitAfterFailureMillis ms after the failed refresh")
        assertEquals(1, waiterCalls.get())
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun aRequestShouldCallTheSupplierWhenTheCacheFailsDuringARefresh() {
        val finishRefresh = CompletableFuture<Product?>()
        val refreshCalls = AtomicInteger()
        // Each read with a number above this one fails, as with a cache that is down
        val failReadsAfter = AtomicInteger(Int.MAX_VALUE)
        val shield =
            shield(
                getCacheFunction = { cacheKey ->
                    Mono.fromCallable {
                        check(cacheReads.incrementAndGet() <= failReadsAfter.get()) { "the cache is down" }
                        cache[cacheKey]
                    }
                },
            )
        try {
            startRefresh(shield, blockedSupplier(finishRefresh, refreshCalls), refreshCalls)
            cache.remove(key)
            val readsBefore = cacheReads.get()
            // The request reads the miss. Each read after that fails.
            failReadsAfter.set(readsBefore + 1)
            val waiterCalls = AtomicInteger()

            val data = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).block(timeout)

            assertEquals(product, data?.value)
            assertEquals(1, waiterCalls.get())
            // The read on the miss and the three failed reads of the wait. No read under a creation lock follows.
            assertEquals(1 + 3, cacheReads.get() - readsBefore)
            assertEquals(null, created.creationSignals.find(key), "the request took no creation lock")
        } finally {
            failReadsAfter.set(Int.MAX_VALUE)
            finishRefresh.complete(Product("refreshed", "refreshed"))
        }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun aWaiterShouldCallTheSupplierWhenItsReadFailsAfterTheHolderGaveTheLockBack() {
        val failHolder = CompletableFuture<Product?>()
        val holderCalls = AtomicInteger()
        // Each read with a number above this one fails, as with a cache that is down
        val failReadsAfter = AtomicInteger(Int.MAX_VALUE)
        val shield =
            shield(
                getCacheFunction = { cacheKey ->
                    Mono.fromCallable {
                        check(cacheReads.incrementAndGet() <= failReadsAfter.get()) { "the cache is down" }
                        cache[cacheKey]
                    }
                },
            )
        val holder = shield.getAndSetReqShieldData(key, blockedSupplier(failHolder, holderCalls), 10_000).toFuture()
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { holderCalls.get() == 1 }
        val waiterCalls = AtomicInteger()
        val waiter = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).toFuture()
        // Holder: 2 reads. Waiter: 1 read before the lock attempt and its first read after it.
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { cacheReads.get() == 4 }
        try {
            // Each read from now on fails, and the holder gives the lock back without an entry
            failReadsAfter.set(4)
            failHolder.completeExceptionally(IllegalStateException("the supplier of the holder fails"))

            assertEquals(product, waiter.get(2, TimeUnit.SECONDS).value)
            assertEquals(1, waiterCalls.get())
            // One failed read after the release. No read under a creation lock follows.
            assertEquals(5, cacheReads.get())
            assertThrows(Exception::class.java) { holder.get(2, TimeUnit.SECONDS) }
        } finally {
            failReadsAfter.set(Int.MAX_VALUE)
        }
        awaitNoSignal()
    }

    @Test
    fun aRequestShouldNotWaitForARefreshWhoseLockTimedOut() {
        val finishRefresh = CompletableFuture<Product?>()
        val refreshCalls = AtomicInteger()
        val shield = shield(lockTimeoutMillis = 200)
        try {
            startRefresh(shield, blockedSupplier(finishRefresh, refreshCalls), refreshCalls)
            // The lock of the refresh timed out: another request could take the refresh over
            Thread.sleep(300)
            cache.remove(key)
            val readsBefore = cacheReads.get()
            val waiterCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val data = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).block(timeout)

            val millis = millisSince(startedAtNanos)
            assertEquals(product, data?.value)
            assertEquals(1, waiterCalls.get())
            assertTrue(millis < 1_000, "the request took $millis ms")
            // The read on the miss and the read under the creation lock. No read of a wait.
            assertEquals(readsBefore + 2, cacheReads.get())
        } finally {
            finishRefresh.complete(Product("refreshed", "refreshed"))
        }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun aRequestShouldStopWaitingForARefreshWhenTheLockOfTheRefreshTimesOut() {
        val finishRefresh = CompletableFuture<Product?>()
        val refreshCalls = AtomicInteger()
        val shield = shield(lockTimeoutMillis = 300)
        try {
            startRefresh(shield, blockedSupplier(finishRefresh, refreshCalls), refreshCalls)
            cache.remove(key)
            val waiterCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val data = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).block(timeout)

            val millis = millisSince(startedAtNanos)
            assertEquals(product, data?.value)
            assertEquals(1, waiterCalls.get())
            // The wait ends with the lock timeout of 300 ms, not with the 60 reads of about 3 s
            assertTrue(millis in 150..1_500, "the request took $millis ms")
        } finally {
            finishRefresh.complete(Product("refreshed", "refreshed"))
        }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun aRequestShouldCreateTheEntryWhenARefreshOutlastsItsReads() {
        val finishRefresh = CompletableFuture<Product?>()
        val refreshCalls = AtomicInteger()
        // Two reads, 50 ms apart, are all that a request spends on the refresh
        val shield = shield(maxAttemptGetCache = 2)
        try {
            startRefresh(shield, blockedSupplier(finishRefresh, refreshCalls), refreshCalls)
            cache.remove(key)
            val readsBefore = cacheReads.get()
            val waiterCalls = AtomicInteger()
            val startedAtNanos = System.nanoTime()

            val data = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).block(timeout)

            assertEquals(product, data?.value)
            assertEquals(1, waiterCalls.get())
            assertTrue(millisSince(startedAtNanos) >= 50, "the request waited for one poll interval")
            // The read on the miss, the two reads during the refresh, and the read under the creation lock
            assertEquals(readsBefore + 4, cacheReads.get())
        } finally {
            finishRefresh.complete(Product("refreshed", "refreshed"))
        }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun theWaitForAHolderShouldHaveItsOwnReadsAfterTheWaitForARefresh() {
        // Every creation lock attempt loses, as to a holder in another JVM. A refresh takes its lock as usual.
        val localLock = KeyLocalLock(3000)
        val lock =
            object : KeyLock by localLock {
                override fun tryLock(
                    key: String,
                    lockType: LockType,
                ): Mono<String> = if (lockType == LockType.CREATE) Mono.empty() else localLock.tryLock(key, lockType)
            }
        val finishRefresh = CompletableFuture<Product?>()
        val refreshCalls = AtomicInteger()
        val shield = shield(maxAttemptGetCache = 2, keyLock = lock)
        try {
            startRefresh(shield, blockedSupplier(finishRefresh, refreshCalls), refreshCalls)
            cache.remove(key)
            val readsBefore = cacheReads.get()
            val waiterCalls = AtomicInteger()

            val data = shield.getAndSetReqShieldData(key, countingSupplier(waiterCalls), 10_000).block(timeout)

            assertEquals(product, data?.value)
            assertEquals(1, waiterCalls.get())
            // The read on the miss, two reads during the refresh, and two reads for the lost creation lock
            assertEquals(readsBefore + 5, cacheReads.get())
        } finally {
            finishRefresh.complete(Product("refreshed", "refreshed"))
        }
        await().atMost(Duration.ofSeconds(2)).until { created.refreshSignals.find(key) == null }
    }

    @Test
    fun noRefreshSignalShouldStayAfterARefreshWroteTheEntry() {
        val signalDuringRefresh = AtomicReference<CompletableFuture<Unit>?>()
        cache[key] = oldEntry()

        shield().getAndSetReqShieldData(key, recordingRefreshSupplier(signalDuringRefresh) { Mono.just(product) }, 10_000).block(timeout)

        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        awaitNoRefreshSignal(signalDuringRefresh)
    }

    @Test
    fun noRefreshSignalShouldStayAfterARefreshUnderTheLocalRefreshLockWroteTheEntry() {
        val signalDuringRefresh = AtomicReference<CompletableFuture<Unit>?>()
        cache[key] = oldEntry()

        shield(workMode = ReqShieldWorkMode.ONLY_CREATE_CACHE)
            .getAndSetReqShieldData(key, recordingRefreshSupplier(signalDuringRefresh) { Mono.just(product) }, 10_000)
            .block(timeout)

        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(product, cache[key]?.value) }
        awaitNoRefreshSignal(signalDuringRefresh)
    }

    @Test
    fun noRefreshSignalShouldStayWhenTheRefreshSupplierFails() {
        val signalDuringRefresh = AtomicReference<CompletableFuture<Unit>?>()
        cache[key] = oldEntry()

        shield()
            .getAndSetReqShieldData(
                key,
                recordingRefreshSupplier(signalDuringRefresh) { Mono.error(IllegalStateException("the refresh fails")) },
                10_000,
            ).block(timeout)

        awaitNoRefreshSignal(signalDuringRefresh)
        assertEquals("old", cache[key]?.value?.id)
    }

    @Test
    fun noRefreshSignalShouldStayWhenALateRefreshWriteIsSkipped() {
        val other = Product("other", "other")
        val signalDuringRefresh = AtomicReference<CompletableFuture<Unit>?>()
        cache[key] = oldEntry()
        // The lock times out during the refresh, and another request writes the entry in that time
        val shield = shield(lockTimeoutMillis = 20)

        shield
            .getAndSetReqShieldData(
                key,
                recordingRefreshSupplier(signalDuringRefresh) {
                    Mono.delay(Duration.ofMillis(60)).map {
                        cache[key] = ReqShieldData(other, 10_000)
                        product
                    }
                },
                10_000,
            ).block(timeout)

        awaitNoRefreshSignal(signalDuringRefresh)
        // The write runs after the value is emitted. If it ran, it would be there well before 300 ms.
        Thread.sleep(300)
        assertEquals(other, cache[key]?.value)
    }

    @Test
    fun noSignalShouldStayAfterACacheWrite() {
        val signalDuringWrite = AtomicReference<CompletableFuture<Unit>?>()
        val shield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { cacheKey, data, _ ->
                        Mono.fromCallable {
                            signalDuringWrite.set(created.creationSignals.find(cacheKey))
                            cache[cacheKey] = data
                            true
                        }
                    },
                    getCacheFunction = { cacheKey -> Mono.fromCallable { cache[cacheKey] } },
                ),
            ).also { created = it }

        shield.getAndSetReqShieldData(key, { Mono.just(product) }, 10_000).block(timeout)

        awaitNoSignal()
        assertEquals(product, cache[key]?.value)
        assertNotNull(signalDuringWrite.get(), "the holder registers its signal before the write")
    }

    @Test
    fun noSignalShouldStayWhenTheEntryAppearsBeforeTheLock() {
        val entry = ReqShieldData(product, 10_000)
        val reads = AtomicInteger()
        val signalDuringRecheck = AtomicReference<CompletableFuture<Unit>?>()
        // Missing at the first read, there at the read under the lock
        val shield =
            shield(getCacheFunction = { cacheKey ->
                Mono.fromCallable {
                    if (reads.incrementAndGet() == 1) {
                        null
                    } else {
                        signalDuringRecheck.set(created.creationSignals.find(cacheKey))
                        entry
                    }
                }
            })

        assertEquals(product, shield.getAndSetReqShieldData(key, { error("not called") }, 10_000).block(timeout)?.value)

        awaitNoSignal()
        assertNotNull(signalDuringRecheck.get(), "the holder registers its signal before the recheck")
    }

    @Test
    fun noSignalShouldStayWhenTheSupplierFails() {
        val signalDuringSupplier = AtomicReference<CompletableFuture<Unit>?>()

        assertThrows(ClientException::class.java) {
            shield()
                .getAndSetReqShieldData(
                    key,
                    {
                        signalDuringSupplier.set(created.creationSignals.find(key))
                        Mono.error(IllegalStateException("the supplier fails"))
                    },
                    10_000,
                ).block(timeout)
        }

        awaitNoSignal()
        assertNotNull(signalDuringSupplier.get())
    }

    @Test
    fun noSignalShouldStayWhenTheSchedulerRejectsTheCacheWrite() {
        val scheduler = RejectingScheduler()
        val signalDuringSupplier = AtomicReference<CompletableFuture<Unit>?>()
        // The supplier runs right before the write is scheduled, so only the write is rejected
        val supplier =
            Callable<Mono<Product?>> {
                signalDuringSupplier.set(created.creationSignals.find(key))
                scheduler.rejecting.set(true)
                Mono.just(product)
            }

        assertEquals(product, shield(scheduler = scheduler).getAndSetReqShieldData(key, supplier, 10_000).block(timeout)?.value)

        awaitNoSignal()
        assertNotNull(signalDuringSupplier.get())
        assertEquals(null, cache[key], "the rejected write never ran")
    }

    @Test
    fun noSignalShouldStayWhenALateCacheWriteIsSkipped() {
        val other = Product("other", "other")
        val signalDuringSupplier = AtomicReference<CompletableFuture<Unit>?>()
        // The lock times out during the supplier call, and another request writes the entry in that time
        val shield = shield(lockTimeoutMillis = 20)

        shield
            .getAndSetReqShieldData(
                key,
                {
                    signalDuringSupplier.set(created.creationSignals.find(key))
                    Mono.delay(Duration.ofMillis(60)).map {
                        cache[key] = ReqShieldData(other, 10_000)
                        product
                    }
                },
                10_000,
            ).block(timeout)

        awaitNoSignal()
        assertNotNull(signalDuringSupplier.get())
        // The write runs after the value is emitted. If it ran, it would be there well before 300 ms.
        Thread.sleep(300)
        assertEquals(other, cache[key]?.value)
    }

    @Test
    fun theLockAndTheSignalShouldBeReleasedWhenTheSchedulerRejectsTheReadBeforeALateWrite() {
        val scheduler = RejectingScheduler()
        val signalDuringSupplier = AtomicReference<CompletableFuture<Unit>?>()
        // The lock times out during the supplier call, so the write first reads the cache on the scheduler
        val shield = shield(lockTimeoutMillis = 20, scheduler = scheduler)

        val data =
            shield
                .getAndSetReqShieldData(
                    key,
                    {
                        signalDuringSupplier.set(created.creationSignals.find(key))
                        Mono.delay(Duration.ofMillis(60)).map {
                            scheduler.rejecting.set(true)
                            product
                        }
                    },
                    10_000,
                ).block(timeout)

        assertEquals(product, data?.value)
        assertNotNull(signalDuringSupplier.get())
        awaitNoSignal()
        // Only releaseLock completes a signal here: no other holder registers for this key
        assertTrue(signalDuringSupplier.get()!!.isDone, "the release completes the signal")
        assertEquals(null, cache[key], "the rejected write never ran")
    }

    /** Runs tasks inline until [rejecting] is set, then rejects them the way a disposed or saturated scheduler does. */
    private class RejectingScheduler : Scheduler by Schedulers.immediate() {
        val rejecting = AtomicBoolean(false)

        override fun createWorker(): Scheduler.Worker {
            val delegate = Schedulers.immediate().createWorker()
            return object : Scheduler.Worker by delegate {
                override fun schedule(task: Runnable): Disposable {
                    if (rejecting.get()) throw RejectedExecutionException("rejected by test")
                    return delegate.schedule(task)
                }
            }
        }
    }

    @Test
    fun aTokenThatTheLockEmitsAfterTheCallerCancelledShouldBeReleased() {
        val localLock = KeyLocalLock(60_000)
        val lockCaller = AtomicReference<CoreSubscriber<in String>>()
        val lockCallCancelled = AtomicBoolean(false)
        // A lock call that ignores the cancel and emits its token later, as when the answer crosses the cancel
        val lock =
            object : KeyLock by localLock {
                override fun tryLock(
                    key: String,
                    lockType: LockType,
                ): Mono<String> =
                    object : Mono<String>() {
                        override fun subscribe(actual: CoreSubscriber<in String>) {
                            actual.onSubscribe(
                                object : Subscription {
                                    override fun request(n: Long) {}

                                    override fun cancel() = lockCallCancelled.set(true)
                                },
                            )
                            lockCaller.set(actual)
                        }
                    }
            }
        val supplierCalls = AtomicInteger()
        val caller = shield(keyLock = lock).getAndSetReqShieldData(key, countingSupplier(supplierCalls), 10_000).subscribe()
        await().atMost(Duration.ofSeconds(2)).until { lockCaller.get() != null }

        caller.dispose()
        assertTrue(lockCallCancelled.get(), "the cancel did not reach the lock call")
        val token = localLock.tryLock(key, LockType.CREATE).block(timeout)
        lockCaller.get().onNext(token!!)
        lockCaller.get().onComplete()

        // The request that got the token released it, although its caller was gone
        await().atMost(Duration.ofSeconds(2)).until {
            val freeToken = localLock.tryLock(key, LockType.CREATE).block(timeout)
            freeToken?.let { localLock.unLock(key, LockType.CREATE, it).block(timeout) }
            freeToken != null
        }
        awaitNoSignal()
        assertEquals(0, supplierCalls.get())
    }
}
