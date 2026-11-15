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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.signal.ReleaseSignals
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(ReqShield::class.java)

/**
 * Internal signal telling a waiting request to stop reading the cache because the cache
 * itself looks unavailable. Never leaves [ReqShield]: the wait always ends with it as given up.
 * Stack trace is disabled because it carries no diagnostic value.
 */
private class GetCacheUnavailableException : RuntimeException(null, null, false, false)

/**
 * The owner of an acquired creation lock. The caller can cancel on another thread while a signal is still on its way,
 * so the recheck, the creation and the cancel hooks each take the lock over with compareAndSet: exactly one of them
 * releases it or hands it to the cache write.
 */
private enum class LockOwner { RECHECK, CREATION, CACHE_WRITE, RELEASED }

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    /**
     * Guards the refresh of an entry in [ReqShieldWorkMode.ONLY_CREATE_CACHE]. That mode does not call the configured
     * lock for a refresh, so without a guard each request for an old entry would subscribe its own refresh. This lock
     * lives in this JVM only and expires like any other local lock.
     */
    private val localRefreshLock: KeyLock by lazy { KeyLocalLock(reqShieldConfig.lockTimeoutMillis) }

    private val lockTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(reqShieldConfig.lockTimeoutMillis)

    fun getAndSetReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> =
        Mono.deferContextual { callerContext ->
            executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                .flatMap { reqShieldData ->
                    if (reqShieldData != null && shouldUpdateCache(reqShieldData)) {
                        updateReqShieldData(key, callable, timeToLiveMillis, reqShieldData.createdAt, callerContext)
                    }
                    Mono.justOrEmpty(reqShieldData)
                }.switchIfEmpty(
                    Mono.defer {
                        handleLockForCacheCreation(key, callable, timeToLiveMillis, callerContext)
                    },
                )
        }

    private fun shouldUpdateCache(reqShieldData: ReqShieldData<T>): Boolean =
        decideToUpdateCache(reqShieldData.createdAt, reqShieldData.timeToLiveMillis, reqShieldConfig.decisionForUpdate)

    /** The lock that collapses requests of [lockType]: the configured one, except for a refresh in ONLY_CREATE_CACHE. */
    private fun lockFor(lockType: LockType): KeyLock =
        if (lockType == LockType.UPDATE && reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE) {
            localRefreshLock
        } else {
            reqShieldConfig.keyLock
        }

    private fun updateReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        // createdAt of the entry this request read, which tells that entry apart from one written during the refresh
        seenCreatedAt: Long,
        // The refresh is subscribed on its own, so it sees the caller's Reactor Context only when it is handed over.
        callerContext: ContextView,
    ) {
        val lockType = LockType.UPDATE
        val lockedAtNanos = System.nanoTime()

        fun write(
            reqShieldData: ReqShieldData<T>,
            token: String,
        ): Mono<ReqShieldData<T>> =
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt, callerContext)
                .thenReturn(reqShieldData)

        fun processMono(token: String): Mono<ReqShieldData<T>> =
            executeCallable(callable)
                .doOnError {
                    // A failed supplier never reaches the cache write, which would release the lock.
                    releaseLock(key, lockType, token, callerContext)
                }.map { data -> buildReqShieldData(data, timeToLiveMillis) }
                .flatMap { reqShieldData -> write(reqShieldData, token) }
                .switchIfEmpty(Mono.defer { write(buildReqShieldData(null, timeToLiveMillis), token) })

        // Empty means another request already holds the update lock: nothing to do here.
        // Deferred so a lock call that throws fails this refresh only, not the caller's cache hit.
        val updateMono =
            Mono
                .defer { lockFor(lockType).tryLock(key, lockType) }
                .flatMap { token ->
                    // A request that finds the entry gone during this refresh waits for this signal instead of a supplier call
                    refreshSignals.register(key, token, CompletableFuture())
                    processMono(token)
                }

        updateMono
            .subscribeOn(reqShieldConfig.scheduler)
            .contextWrite(callerContext)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to update cache for key '{}': {}", key, e.message, e) },
            )
    }

    /**
     * Creates the entry of [key] after a cache miss.
     *
     * If a refresh of [key] runs in this JVM, it is about to write the entry. This request then first reads the cache
     * until the refresh released its lock, and calls no supplier in that time. If the refresh ends without an entry,
     * or the reads are used up, this request goes on as on any miss. All waits of this request share one [ReadBudget].
     */
    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> {
        val budget = ReadBudget()
        val refresh =
            refreshSignals.find(key)
                ?: return createOrWaitForHolder(key, callable, timeToLiveMillis, budget, callerContext)

        // The wait for the refresh has its own reads. If they were taken from the reads for the creation lock, a
        // refresh that outlasts them would leave none: each request that then loses the lock would call the
        // supplier at once, without a lock.
        return readUntilReleased(key, refreshSignals, ReadBudget(), refresh).flatMap { waited ->
            if (waited is Waited.Found) {
                Mono.just(waited.data)
            } else {
                createOrWaitForHolder(key, callable, timeToLiveMillis, budget, callerContext)
            }
        }
    }

    /**
     * Tries the creation lock once. The request that gets it creates the entry. A request that loses waits for the
     * holder and, if the holder gives the lock back without an entry, tries the lock again. Each try after the first
     * spends at least two reads of [budget], so the number of tries is at most `maxAttemptGetCache / 2 + 1`.
     */
    private fun createOrWaitForHolder(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        budget: ReadBudget,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> {
        val lockType = LockType.CREATE
        val lockedAtNanos = System.nanoTime()

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE) {
            // This mode never creates a cache entry through the lock, so no token is taken.
            return createReqShieldData(key, callable, timeToLiveMillis, lockType, null, null, lockedAtNanos, callerContext)
        }

        return reqShieldConfig.keyLock
            .tryLock(key, lockType)
            .flatMap { token ->
                // From here on, requests of this JVM that lose the lock wait for this signal instead of a full poll interval.
                creationSignals.register(key, token, CompletableFuture())
                val owner = AtomicReference(LockOwner.RECHECK)
                // Another request may have filled the cache between our initial miss and lock acquisition.
                Mono.defer { executeGetCacheFunction(reqShieldConfig.getCacheFunction, key) }
                    // A global lock can emit on its client's event loop; keep cache reads off that thread.
                    .subscribeOn(reqShieldConfig.scheduler)
                    .flatMap { Mono.justOrEmpty(it) }
                    .switchIfEmpty(
                        Mono.defer {
                            // A cancel that already released the lock leaves nothing to create.
                            if (owner.compareAndSet(LockOwner.RECHECK, LockOwner.CREATION)) {
                                createReqShieldData(key, callable, timeToLiveMillis, lockType, token, owner, lockedAtNanos, callerContext)
                            } else {
                                Mono.empty()
                            }
                        },
                    ).doFinally {
                        // A cache hit, read failure, or cancellation during the recheck must release our token.
                        // Once creation owns it, createReqShieldData releases it (error, cancel or after the write).
                        if (owner.compareAndSet(LockOwner.RECHECK, LockOwner.RELEASED)) {
                            releaseLock(key, lockType, token, callerContext)
                        }
                    }
            }.switchIfEmpty(
                Mono.defer {
                    handleLockFailure(key, callable, timeToLiveMillis, budget, callerContext)
                },
            )
    }

    private fun createReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        lockType: LockType,
        token: String?,
        // The owner of [token] (null without a lock): the cache write, the error and the cancel take it over once.
        owner: AtomicReference<LockOwner>?,
        lockedAtNanos: Long,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> {
        // Hands the lock to the asynchronous cache write, which releases it after writing. False when a cancel
        // already released it: the write is then skipped, because it would run without the lock.
        fun handToCacheWrite(): Boolean = owner == null || owner.compareAndSet(LockOwner.CREATION, LockOwner.CACHE_WRITE)

        fun releaseUnlessHandedOver() {
            if (token != null && owner != null && owner.compareAndSet(LockOwner.CREATION, LockOwner.RELEASED)) {
                releaseLock(key, lockType, token, callerContext)
            }
        }

        fun writeCache(reqShieldData: ReqShieldData<T>) {
            // Async fire-and-forget cache storage (matches coroutine implementation)
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, null, callerContext)
                // Subscribed on its own, so the cache function sees the caller's Reactor Context only when it is handed over.
                .contextWrite(callerContext)
                .subscribe(
                    { /* success - no action needed */ },
                    { e -> log.error("Failed to set cache for key '{}': {}", key, e.message, e) },
                )
        }

        return executeCallable(callable)
            .doOnError { releaseUnlessHandedOver() }
            .map { data -> buildReqShieldData(data, timeToLiveMillis) }
            .switchIfEmpty(Mono.fromSupplier { buildReqShieldData(null, timeToLiveMillis) })
            .doOnNext { reqShieldData ->
                if (handToCacheWrite()) {
                    writeCache(reqShieldData)
                }
            }.doOnCancel {
                // A caller cancelled while the supplier runs (client disconnect, timeout()) never reaches
                // the cache write, so the lock is released here instead of lingering until it expires.
                releaseUnlessHandedOver()
            }
    }

    /**
     * Writes [reqShieldData] and releases the lock, unless the lock timed out during the supplier call and another
     * request wrote the entry in that time. So after a lock timeout the first write stays. In the usual case, one slow
     * call that a later and faster call overtook, the entry that stays is the newer one, and the late write would
     * replace it with older data.
     *
     * An entry does not hold the start time of its supplier call, so this rule cannot order two calls that both
     * outlast the lock timeout: the entry of the call that ended first stays, also when that call started first.
     *
     * The cache is read again only after a lock timeout, so a supplier that is faster than the timeout costs no
     * extra read. [seenCreatedAt] is the createdAt of the entry this request read before its supplier call, or null
     * when there was none.
     */
    private fun writeUnlessSuperseded(
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
        lockedAtNanos: Long,
        seenCreatedAt: Long?,
        callerContext: ContextView,
    ): Mono<Boolean> =
        Mono.defer {
            if (token == null || System.nanoTime() - lockedAtNanos < lockTimeoutNanos) {
                return@defer setReqShieldData(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token, callerContext)
            }

            isSuperseded(key, seenCreatedAt).flatMap { superseded ->
                if (superseded) {
                    log.debug("Skipped the cache write for key '{}': its lock timed out and a newer entry exists", key)
                    releaseLock(key, lockType, token, callerContext)
                    Mono.just(false)
                } else {
                    setReqShieldData(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token, callerContext)
                }
            }
        }

    private fun isSuperseded(
        key: String,
        seenCreatedAt: Long?,
    ): Mono<Boolean> =
        executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
            // The supplier can emit on its client's event loop; keep cache reads off that thread.
            .subscribeOn(reqShieldConfig.scheduler)
            .flatMap { Mono.justOrEmpty(it) }
            .map { cachedData -> cachedData.createdAt != seenCreatedAt }
            .defaultIfEmpty(false)
            .onErrorResume { e ->
                // Without the read nothing shows a newer entry, so the write goes on as it did before
                log.warn("Failed to read cache for key '{}' before its late cache write: {}", key, e.message)
                Mono.just(false)
            }

    /**
     * Waits for the request that owns the creation lock to fill the cache.
     *
     * [readUntilReleased] reads the cache. If the holder gives the lock back without an entry (its supplier failed,
     * for example), this request tries to be the next holder, and the requests that lose again wait for the new
     * holder. Only when the reads of [budget] are used up, or [MAX_CONSECUTIVE_GET_CACHE_FAILURES] reads failed in a
     * row, does this request call the supplier itself without a lock. A failing supplier surfaces as
     * `ClientException(SUPPLIER_ERROR)` instead of a null-valued entry.
     */
    private fun handleLockFailure(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        budget: ReadBudget,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> =
        readUntilReleased(key, creationSignals, budget)
            .flatMap { waited ->
                when (waited) {
                    is Waited.Found -> Mono.just(waited.data)
                    Waited.Released -> createOrWaitForHolder(key, callable, timeToLiveMillis, budget, callerContext)
                    // No lock was acquired by this request, so there is nothing to release on failure
                    Waited.GaveUp ->
                        executeCallable(callable)
                            .map { data -> buildReqShieldData(data, timeToLiveMillis) }
                            .switchIfEmpty(Mono.fromSupplier { buildReqShieldData(null, timeToLiveMillis) })
                }
            }
            // A lock can emit on its client's event loop; keep the reads and the supplier call off that thread.
            .subscribeOn(reqShieldConfig.scheduler)

    /**
     * The cache reads that one request may spend on one kind of wait. The wait for a refresh has its own budget.
     * All waits for the holder of the creation lock share one, so the lock retries cannot extend the wait.
     */
    private class ReadBudget {
        val reads = AtomicInteger()
        val consecutiveFailures = AtomicInteger()
    }

    private sealed interface Waited<out T> {
        class Found<T>(
            val data: ReqShieldData<T>,
        ) : Waited<T>

        /** The holder gave its lock back, and the read after that found no entry. */
        object Released : Waited<Nothing>

        /** The reads are used up, or the cache looks unavailable. */
        object GaveUp : Waited<Nothing>
    }

    /**
     * Another request holds the lock of [signals] for [key]: reads the cache until that request publishes its result.
     *
     * The first read runs at once. Between two reads this request waits for the signal of the holder, for at most
     * [GET_CACHE_INTERVAL_MILLIS]. A holder in this JVM completes the signal when it gives the lock back, so the next
     * read follows its cache write at once. If that read finds no entry, the holder wrote none and the wait ends as
     * [Waited.Released]. Without a signal - the holder runs in another JVM - the wait is the whole interval, which
     * makes this a poll at a fixed interval. The supplier is never called from the wait.
     *
     * The wait gives up after [ReqShieldConfiguration.maxAttemptGetCache] reads, counted in [budget] over all waits of
     * the request, with no wait after the last read. It gives up earlier when [MAX_CONSECUTIVE_GET_CACHE_FAILURES]
     * reads failed in a row (the cache looks unavailable). A read that fails still counts, so the wait stays bounded.
     */
    private fun readUntilReleased(
        key: String,
        signals: ReleaseSignals<CompletableFuture<Unit>>,
        budget: ReadBudget,
        // The signal that made the request wait, when the caller already looked it up
        firstSignal: CompletableFuture<Unit>? = null,
    ): Mono<Waited<T>> {
        val maxReads = reqShieldConfig.maxAttemptGetCache
        // The signal that the current read found before it ran
        val signalOfRead = AtomicReference<CompletableFuture<Unit>?>()
        // True when the holder gave its lock back during the last wait
        val released = AtomicBoolean(false)
        // The last signal that this wait saw. When it is gone from the map and complete, its holder gave the lock back,
        // also if that happened between two steps of this loop.
        val lastSignal = AtomicReference(firstSignal)

        return Mono
            .defer {
                if (budget.reads.get() >= maxReads) return@defer Mono.just<Waited<T>>(Waited.GaveUp)
                budget.reads.incrementAndGet()
                // Looked up before the read: the holder completes the signal after its cache write, so a write that
                // this read misses still ends the wait at once.
                val found = signals.find(key)
                signalOfRead.set(found)
                if (found == null && lastSignal.get()?.isDone == true) released.set(true)
                getCacheWhileWaitingForLock(key, budget.consecutiveFailures)
                    .map<Waited<T>> { Waited.Found(it) }
                    // Empty drives the next read, after the next wait
                    .switchIfEmpty(Mono.defer { if (released.get()) Mono.just(Waited.Released) else Mono.empty() })
            }.repeatWhenEmpty { emptyReads ->
                emptyReads
                    .takeWhile { budget.reads.get() < maxReads }
                    .concatMap { index ->
                        // A holder that got the lock a moment ago may have registered its signal only during the read
                        val signal = signalOfRead.get() ?: signals.find(key)
                        if (signal != null) lastSignal.set(signal)
                        waitForNextRead(signal)
                            .doOnNext { released.set(it) }
                            .thenReturn(index)
                    }
            }.onErrorResume(GetCacheUnavailableException::class.java) { Mono.just(Waited.GaveUp) }
            .defaultIfEmpty(Waited.GaveUp)
    }

    /**
     * Waits until the holder of [signal] gave its lock back, for at most the poll interval.
     * Emits true when the holder gave the lock back in that time.
     */
    private fun waitForNextRead(signal: CompletableFuture<Unit>?): Mono<Boolean> {
        val delay = Mono.delay(Duration.ofMillis(GET_CACHE_INTERVAL_MILLIS))
        if (signal == null) return delay.thenReturn(false)

        // The signal is read again after the delay: a release at the moment of the timeout must not look like a
        // holder that still works.
        val interval = delay.map { signal.isDone }
        return Mono.firstWithSignal(
            // Waiters share the signal: a waiter that stops waiting must not cancel it for the others.
            Mono
                .fromFuture(signal, true)
                .thenReturn(true)
                // The signal completes on the thread of the release. The next read moves to the scheduler of the
                // interval timer, where a read after a full interval runs too, so it cannot be rejected.
                .publishOn(Schedulers.parallel()),
            interval,
        )
    }

    /**
     * Reads the cache once while waiting for the lock holder.
     *
     * Completes empty while the cache is not filled yet, which drives the retry loop.
     * A transient read failure also completes empty so the loop continues, but
     * [MAX_CONSECUTIVE_GET_CACHE_FAILURES] consecutive failures raise [GetCacheUnavailableException]
     * to bail out of the loop. Any successful read resets the failure counter.
     */
    private fun getCacheWhileWaitingForLock(
        key: String,
        consecutiveGetCacheFailures: AtomicInteger,
    ): Mono<ReqShieldData<T>> =
        executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
            .doOnSuccess { consecutiveGetCacheFailures.set(0) }
            .flatMap { reqShieldData -> Mono.justOrEmpty(reqShieldData) }
            .onErrorResume { e ->
                val failures = consecutiveGetCacheFailures.incrementAndGet()
                log.warn(
                    "Failed to read cache for key '{}' while waiting for the lock holder (consecutive failures: {}): {}",
                    key,
                    failures,
                    e.message,
                )
                if (failures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) {
                    Mono.error(GetCacheUnavailableException())
                } else {
                    Mono.empty()
                }
            }

    private fun buildReqShieldData(
        value: T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> =
        ReqShieldData(
            value = value,
            timeToLiveMillis = timeToLiveMillis,
        )

    private fun setReqShieldData(
        cacheSetter: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
        callerContext: ContextView,
    ): Mono<Boolean> = executeSetCacheFunction(cacheSetter, key, reqShieldData, lockType, token, callerContext)

    private fun executeGetCacheFunction(
        getFunction: (String) -> Mono<ReqShieldData<T>?>,
        key: String,
    ): Mono<ReqShieldData<T>?> =
        // Deferred so a client function that throws synchronously fails as an onError signal.
        Mono
            .defer { getFunction(key) }
            .onErrorMap { e -> ClientException(ErrorCode.GET_CACHE_ERROR, cause = e) }

    private fun executeSetCacheFunction(
        setFunction: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
        callerContext: ContextView,
    ): Mono<Boolean> =
        // Deferred so a client function that throws synchronously fails as an onError signal,
        // which keeps the lock release in doFinally reachable.
        Mono
            .defer { setFunction(key, value, value.timeToLiveMillis) }
            .onErrorMap { e -> ClientException(ErrorCode.SET_CACHE_ERROR, cause = e) }
            .subscribeOn(reqShieldConfig.scheduler)
            .doFinally {
                // Only the holder of a token took a lock, so only it may release one.
                // Placed after subscribeOn: a scheduler that rejects the write (disposed or saturated) never
                // subscribes the operators above it, so a release there would never run.
                if (token != null) {
                    releaseLock(key, lockType, token, callerContext)
                }
            }

    private fun releaseLock(
        key: String,
        lockType: LockType,
        token: String,
        // The release is subscribed on its own. A lock function that reads the Reactor Context (a lock store per
        // tenant, for example) has to see the Context that tryLock saw, or it releases in another store.
        callerContext: ContextView,
    ) {
        // No retry needed: false means lock already released or expired (not an error).
        Mono.defer { lockFor(lockType).unLock(key, lockType, token) }
            .doOnNext { unlocked ->
                if (!unlocked) {
                    log.debug("Lock already released or expired for key '{}'", key)
                }
            }.doFinally {
                // Every lock release goes through here. The waiters are woken also after a failed release: they then
                // read the cache and go on as after a poll interval.
                val signals = if (lockType == LockType.CREATE) creationSignals else refreshSignals
                signals.release(key, token)
            }.contextWrite(callerContext)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to unlock key '{}': {}", key, e.message, e) },
            )
    }

    private fun executeCallable(callable: Callable<Mono<T?>>): Mono<T?> =
        // Deferred so a supplier that throws synchronously fails as an onError signal,
        // which keeps the lock release of the caller reachable.
        Mono
            .defer { callable.call() }
            .onErrorMap { e -> ClientException(ErrorCode.SUPPLIER_ERROR, cause = e) }

    companion object {
        /**
         * Shared by every instance, like the local lock map: requests for one key collapse across instances, so a
         * waiter of one instance must see the creation that another instance runs.
         */
        internal val creationSignals = ReleaseSignals<CompletableFuture<Unit>> { it.complete(Unit) }

        /** The refreshes that run in this JVM. A request that finds an entry gone waits for the refresh of its key. */
        internal val refreshSignals = ReleaseSignals<CompletableFuture<Unit>> { it.complete(Unit) }
    }
}
