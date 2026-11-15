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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GLOBAL_CREATION_FRONT_KEY_PREFIX
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * With a global lock, one request of a JVM asks the lock store for the creation lock of a key. The other requests of
 * that JVM wait for that request, and do not call the lock store.
 */
class ReqShieldGlobalCreationFrontTest {
    // The signals and the local locks are shared by all instances, so each test has its own key
    private val key = "global-front-${UUID.randomUUID()}"
    private val lockKey = "reqshield:lock:${key}_CREATE"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val cacheReads = AtomicInteger()

    // The time of each cache read, by its number
    private val readAtNanos = ConcurrentHashMap<Int, Long>()

    /** The lock store: lock key to token. */
    private val locks = ConcurrentHashMap<String, String>()
    private val lockCalls = AtomicInteger()
    private val unlockCalls = AtomicInteger()

    // For each unlock call: true when the creation signal of the key was still there
    private val signalAtUnlock = ConcurrentHashMap<Int, Boolean>()
    private val supplierCalls = AtomicInteger()
    private val remoteProduct = Product("remote", "remote")
    private val localProduct = Product("local", "local")
    private val timeout = Duration.ofSeconds(5)

    /** The default lock function: a `SET NX` on [locks]. */
    private val takeTheLock: (String, String) -> Mono<Boolean> = { lockKey, token ->
        Mono.fromSupplier { locks.putIfAbsent(lockKey, token) == null }
    }

    private fun shield(
        maxAttemptGetCache: Int = 60,
        lockFunction: (String, String) -> Mono<Boolean> = takeTheLock,
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    Mono.fromSupplier {
                        cache[cacheKey] = data
                        true
                    }
                },
                getCacheFunction = { cacheKey ->
                    Mono.fromCallable {
                        readAtNanos[cacheReads.incrementAndGet()] = System.nanoTime()
                        cache[cacheKey]
                    }
                },
                globalLockFunction = { lockKey, token, _ ->
                    lockCalls.incrementAndGet()
                    lockFunction(lockKey, token)
                },
                globalUnLockFunction = { lockKey, token ->
                    Mono.fromSupplier {
                        signalAtUnlock[unlockCalls.incrementAndGet()] = ReqShield.creationSignals.find(key) != null
                        locks.remove(lockKey, token)
                    }
                },
                isLocalLock = false,
                maxAttemptGetCache = maxAttemptGetCache,
            ),
        )

    private fun supplier(millis: Long = 0) =
        Callable<Mono<Product?>> {
            supplierCalls.incrementAndGet()
            if (millis > 0) Mono.delay(Duration.ofMillis(millis)).thenReturn(localProduct) else Mono.just(localProduct)
        }

    /** A request of another JVM holds the creation lock of the key. */
    private fun lockByAnotherJvm() {
        locks[lockKey] = REMOTE_TOKEN
    }

    private fun unlockByAnotherJvm() {
        locks.remove(lockKey, REMOTE_TOKEN)
    }

    /** Starts [CALLERS] requests for the key, each on its own worker. */
    private fun startCallers(
        shield: ReqShield<Product>,
        supplier: Callable<Mono<Product?>>,
    ): List<CompletableFuture<ReqShieldData<Product>>> =
        (1..CALLERS).map {
            shield
                .getAndSetReqShieldData(key, supplier, 10_000)
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture()
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

        val data = shield.getAndSetReqShieldData(key, supplier(), 10_000).block(timeout)

        assertEquals(localProduct, data?.value)
        assertEquals(lockCallsBefore + 1, lockCalls.get())
        assertEquals(supplierCallsBefore + 1, supplierCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() == unlockCallsBefore + 1 && locks.isEmpty() }
        await().atMost(Duration.ofSeconds(2)).until { ReqShield.creationSignals.find(key) == null }
    }

    private fun millisSince(startNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    /**
     * Waits for cache read [readNumber] and sleeps until 70 ms after it. A poll at a fixed 50 ms interval that
     * started at that read is then 30 ms away from its next read. Returns the time it woke at.
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
    fun requestsShouldAskTheStoreOnceAndGetTheEntryOfAnotherJvm() {
        lockByAnotherJvm()
        // The number of the request that asked the store, from the Reactor Context of the lock call
        val representative = AtomicReference<Int>()
        val shield =
            shield(lockFunction = { lockKey, token ->
                Mono.deferContextual { context ->
                    // The request of assertTheNextRequestGetsTheLock has no number
                    representative.compareAndSet(null, context.getOrDefault("caller", 0))
                    takeTheLock(lockKey, token)
                }
            })

        // For each request: true when the signal of the key was still there as its result went on
        val signalAtResult = ConcurrentHashMap<Int, Boolean>()
        val futures =
            (1..CALLERS).map { caller ->
                shield
                    .getAndSetReqShieldData(key, supplier(), 10_000)
                    .doOnNext { signalAtResult[caller] = ReqShield.creationSignals.find(key) != null }
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite { it.put("caller", caller) }
                    .toFuture()
            }
        // The other JVM writes the entry after 30 ms, then gives its lock back
        Thread.sleep(30)
        cache[key] = ReqShieldData(remoteProduct, 10_000)
        unlockByAnotherJvm()

        val begin = System.nanoTime()
        futures.forEach { assertEquals(remoteProduct, it.get(2, TimeUnit.SECONDS).value) }
        // The request that read for the JVM gave the front lock back before its own result went on. A waiter can
        // find the entry by its own poll before that, so only the result of the reading request is checked.
        assertEquals(false, signalAtResult[representative.get()])
        // The requests of this JVM do not each wait for a poll interval of their own after the write
        assertTrue(millisSince(begin) < 1_000)
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
        await().atMost(Duration.ofSeconds(2)).until { ReqShield.creationSignals.find(key) == null }
        // The global lock was released before its waiters were woken
        assertEquals(true, signalAtUnlock[1])
    }

    @Test
    fun aWaiterShouldBeTheNextHolderRightAfterTheHolderOfThisJvmFailed() {
        val shield = shield()
        val failHolder = CompletableFuture<Product?>()
        val holderCalls = AtomicInteger()
        val holder =
            shield
                .getAndSetReqShieldData(
                    key,
                    Callable {
                        holderCalls.incrementAndGet()
                        Mono.fromFuture(failHolder)
                    },
                    10_000,
                ).toFuture()
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until { holderCalls.get() == 1 }
        val waiter =
            shield
                .getAndSetReqShieldData(key, supplier(), 10_000)
                .map { it.value to System.nanoTime() }
                .toFuture()
        // Holder: 2 reads. Waiter: 1 read before the front lock, then its first read in the wait for the holder.
        val failedAtNanos = sleepUntil70MillisAfterRead(4)
        // The waiter lost the front lock and waits for the signal of the holder, without a call of the store
        assertFalse(waiter.isDone)
        assertEquals(1, lockCalls.get())

        failHolder.completeExceptionally(IllegalStateException("the supplier of the holder failed"))

        val (value, endedAtNanos) = waiter.get(2, TimeUnit.SECONDS)
        assertEquals(localProduct, value)
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(endedAtNanos - failedAtNanos)
        // A poll at the fixed interval would read 30 ms after the failure: the release of the holder woke the waiter
        assertTrue(waitedMillis < 25, "the waiter got its value $waitedMillis ms after the failure")
        assertThrows(Exception::class.java) { holder.get(2, TimeUnit.SECONDS) }
        assertEquals(1, supplierCalls.get())
        assertEquals(2, lockCalls.get())
        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() == 2 && locks.isEmpty() }
        await().atMost(Duration.ofSeconds(2)).until { ReqShield.creationSignals.find(key) == null }
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
                takeTheLock(lockKey, token)
            })

        val signalAtError = AtomicReference<Boolean>()

        assertThrows(IllegalStateException::class.java) {
            shield
                .getAndSetReqShieldData(key, supplier(), 10_000)
                .doOnError { signalAtError.set(ReqShield.creationSignals.find(key) != null) }
                .block(timeout)
        }

        // The front lock and its signal were given back before the error went on
        assertEquals(false, signalAtError.get())

        assertEquals(0, supplierCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun theFrontLockShouldBeFreeAfterTheRequestThatReadsForTheJvmWasCancelled() {
        lockByAnotherJvm()
        val shield = shield()
        val caller = shield.getAndSetReqShieldData(key, supplier(), 10_000).subscribe()
        // One read before the front lock, then two reads of the poll
        await().atMost(Duration.ofSeconds(2)).until { cacheReads.get() >= 3 }
        assertEquals(1, lockCalls.get())
        // Runs inside the completion of the signal: checks whether the front lock is free at that moment
        val frontFreeAtSignal = AtomicReference<Boolean>()
        checkNotNull(ReqShield.creationSignals.find(key)).thenRun {
            val probe = KeyLocalLock(3_000)
            val token = probe.tryLock(GLOBAL_CREATION_FRONT_KEY_PREFIX + key, LockType.CREATE).toFuture().getNow(null)
            frontFreeAtSignal.set(token != null)
            if (token != null) probe.unLock(GLOBAL_CREATION_FRONT_KEY_PREFIX + key, LockType.CREATE, token).subscribe()
        }

        caller.dispose()

        // The front lock was given back before the signal woke the waiters
        assertEquals(true, frontFreeAtSignal.get())

        // The poll stops: at most a read that had already started still runs
        val readsAfterCancel = cacheReads.get()
        Thread.sleep(120)
        assertTrue(cacheReads.get() <= readsAfterCancel + 1, "reads went on after the cancel: ${cacheReads.get()}")
        assertEquals(0, supplierCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun theFrontLockShouldBeFreeAfterACancelDuringALockCallThatNeverAnswers() {
        val firstCall = AtomicBoolean(true)
        // The first call takes the lock in the store and gives no answer, like a store call whose answer is still on its way
        val shield =
            shield(lockFunction = { lockKey, token ->
                if (firstCall.getAndSet(false)) {
                    Mono.fromRunnable<Boolean> { locks[lockKey] = token }.then(Mono.never())
                } else {
                    takeTheLock(lockKey, token)
                }
            })
        val caller = shield.getAndSetReqShieldData(key, supplier(), 10_000).subscribe()
        await().atMost(Duration.ofSeconds(2)).until { locks.isNotEmpty() }

        caller.dispose()

        // KeyGlobalLock gives back the lock that the store took, before the waiters of this JVM are woken
        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() == 1 && locks.isEmpty() }
        assertEquals(true, signalAtUnlock[1])
        assertEquals(0, supplierCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun theFrontLockShouldBeFreeAfterACancelDuringALockCallThatAnswersLate() {
        val lateAnswer = AtomicReference<CoreSubscriber<in Boolean>>()
        // The first call takes the lock in the store, ignores the cancel and answers true later
        val shield =
            shield(lockFunction = { lockKey, token ->
                if (lateAnswer.get() != null) {
                    takeTheLock(lockKey, token)
                } else {
                    object : Mono<Boolean>() {
                        override fun subscribe(actual: CoreSubscriber<in Boolean>) {
                            locks[lockKey] = token
                            actual.onSubscribe(
                                object : Subscription {
                                    override fun request(n: Long) {}

                                    override fun cancel() {}
                                },
                            )
                            lateAnswer.set(actual)
                        }
                    }
                }
            })
        val caller = shield.getAndSetReqShieldData(key, supplier(), 10_000).subscribe()
        await().atMost(Duration.ofSeconds(2)).until { lateAnswer.get() != null }

        caller.dispose()
        lateAnswer.get().onNext(true)
        lateAnswer.get().onComplete()

        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.get() >= 1 && locks.isEmpty() }
        assertEquals(1, lockCalls.get())
        assertEquals(0, supplierCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun theRequestThatReadsForTheJvmShouldReadAtGrowingWaits() {
        lockByAnotherJvm()
        // One read before the front lock, then four reads of the poll with three waits between them
        val shield = shield(maxAttemptGetCache = 4)

        assertEquals(localProduct, shield.getAndSetReqShieldData(key, supplier(), 10_000).block(timeout)?.value)

        assertEquals(5, cacheReads.get())
        val waits = (2..4).map { TimeUnit.NANOSECONDS.toMillis(readAtNanos.getValue(it + 1) - readAtNanos.getValue(it)) }
        // The waits are 5, 10 and 20 ms. A poll at the fixed interval waits 50 ms each time.
        assertTrue(waits[0] >= 5 && waits[1] >= 10 && waits[2] >= 20, "waits: $waits")
        assertTrue(waits.sum() < 100, "waits: $waits")
        assertEquals(1, lockCalls.get())
        assertTheNextRequestGetsTheLock(shield)
    }

    @Test
    fun theRequestThatReadsForTheJvmShouldNotReadOnTheThreadOfTheLockAnswer() {
        lockByAnotherJvm()
        // The lock answers on its own thread, like the event loop of a Redis client
        val lockClient = Schedulers.newSingle("lock-client")
        val readThreads = CopyOnWriteArrayList<String>()
        val shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    getCacheFunction = { cacheKey ->
                        Mono.fromCallable {
                            readThreads.add(Thread.currentThread().name)
                            cache[cacheKey]
                        }
                    },
                    globalLockFunction = { lockKey, token, _ -> takeTheLock(lockKey, token).publishOn(lockClient) },
                    globalUnLockFunction = { lockKey, token -> Mono.fromSupplier { locks.remove(lockKey, token) } },
                    isLocalLock = false,
                    maxAttemptGetCache = 2,
                ),
            )

        try {
            val supplierThread = AtomicReference<String>()
            val data =
                shield
                    .getAndSetReqShieldData(
                        key,
                        Callable {
                            supplierThread.set(Thread.currentThread().name)
                            Mono.just(localProduct)
                        },
                        10_000,
                    ).block(timeout)

            assertEquals(localProduct, data?.value)
            assertEquals(3, readThreads.size)
            assertTrue(readThreads.none { it.startsWith("lock-client") }, "reads ran on: $readThreads")
            assertFalse(supplierThread.get().startsWith("lock-client"), "the supplier ran on: ${supplierThread.get()}")
        } finally {
            lockClient.dispose()
        }
    }

    private companion object {
        const val CALLERS = 20
        const val REMOTE_TOKEN = "token-of-another-jvm"
    }
}
