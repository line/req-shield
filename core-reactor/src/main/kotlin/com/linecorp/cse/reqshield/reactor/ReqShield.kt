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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GLOBAL_CREATION_FRONT_KEY_PREFIX
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GLOBAL_REFRESH_ATTEMPT_KEY_PREFIX
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.constant.ConfigValues.SIGNAL_REGISTRATION_GRACE_MILLIS
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.signal.ReleaseSignals
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import com.linecorp.cse.reqshield.support.utils.representativePollMillis
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
     * In front of the keys of the local locks that guard something inside this instance. Those locks share the lock
     * map of the JVM, and without this text a request of one instance would wait for a request of another instance
     * that uses the same key for another cache or another lock store.
     */
    private val instanceScope = "instance-${instanceSequence.incrementAndGet()}:"

    /**
     * The creations that requests of this instance wait for. With a local lock the registry is the one of the JVM,
     * because the local lock map is shared too: a request waits for a creation of the same key in another instance.
     * With any other lock the registry belongs to this instance: two instances with stores of their own never
     * wait for each other.
     */
    internal val creationSignals: ReleaseSignals<CompletableFuture<Unit>> =
        if (reqShieldConfig.keyLock is KeyLocalLock) Companion.creationSignals else ReleaseSignals { it.complete(Unit) }

    /**
     * The refreshes that requests of this instance wait for. Shared in the JVM only when the refresh lock is the
     * configured local lock. In [ReqShieldWorkMode.ONLY_CREATE_CACHE] the refresh lock belongs to this instance.
     */
    internal val refreshSignals: ReleaseSignals<CompletableFuture<Unit>> =
        if (reqShieldConfig.keyLock is KeyLocalLock && reqShieldConfig.reqShieldWorkMode != ReqShieldWorkMode.ONLY_CREATE_CACHE) {
            Companion.refreshSignals
        } else {
            ReleaseSignals { it.complete(Unit) }
        }

    /**
     * Guards the refresh of an entry in [ReqShieldWorkMode.ONLY_CREATE_CACHE]. That mode does not call the configured
     * lock for a refresh, so without a guard each request for an old entry would subscribe its own refresh. This lock
     * lives in this JVM only and expires like any other local lock.
     */
    internal val localRefreshLock: KeyLock by lazy { KeyLocalLock(reqShieldConfig.lockTimeoutMillis, instanceScope) }

    /**
     * Limits how often this JVM asks the lock store for the refresh lock of one key: once in
     * [GET_CACHE_INTERVAL_MILLIS]. Without it each read of an old entry would call the store while a request of
     * another JVM holds the lock. An attempt takes this local lock and never gives it back: it expires.
     */
    private val refreshAttemptGuard: KeyLock by lazy { KeyLocalLock(GET_CACHE_INTERVAL_MILLIS, instanceScope) }

    /**
     * With a global lock, chooses the one request of this JVM that asks the lock store for the creation lock of a
     * key. The other requests of this JVM wait for the signal of that request. Without it, each of them would call
     * the store, and each would read the cache every 50 ms while a request of another JVM holds the lock.
     */
    internal val creationFrontLock: KeyLock by lazy { KeyLocalLock(reqShieldConfig.lockTimeoutMillis, instanceScope) }

    /** The front lock token of a request that got the global lock, by the token of that global lock. */
    private val frontTokens = ConcurrentHashMap<String, String>()

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
        val lock = lockFor(lockType)
        val nanosBeforeAttempt = nanosBeforeAttempt(lock)

        fun write(
            reqShieldData: ReqShieldData<T>,
            token: String,
            lockedAtNanos: Long,
        ): Mono<ReqShieldData<T>> =
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt, callerContext)
                .thenReturn(reqShieldData)

        fun processMono(
            token: String,
            lockedAtNanos: Long,
        ): Mono<ReqShieldData<T>> =
            executeCallable(callable)
                .doOnError {
                    // A failed supplier never reaches the cache write, which would release the lock.
                    releaseLock(key, lockType, token, callerContext)
                }.map { data -> buildReqShieldData(data, timeToLiveMillis) }
                .flatMap { reqShieldData -> write(reqShieldData, token, lockedAtNanos) }
                .switchIfEmpty(Mono.defer { write(buildReqShieldData(null, timeToLiveMillis), token, lockedAtNanos) })

        // Empty means another request already holds the update lock: nothing to do here.
        // Deferred so a lock call that throws fails this refresh only, not the caller's cache hit.
        val token = Mono.defer { lock.tryLock(key, lockType) }

        val updateMono =
            if (lock is KeyLocalLock) {
                // A local lock answers at once, on the thread that asks, as it does on the creation path. So only the
                // request that gets the token hands work to the scheduler. A request that loses the lock - each
                // other read of this key while the refresh runs - creates no task.
                token.flatMap { acquired ->
                    // A request that finds the entry gone during this refresh waits for this signal instead of a supplier call
                    refreshSignals.register(key, acquired, CompletableFuture())
                    val lockedAtNanos = lockStartNanos(lock, nanosBeforeAttempt)
                    val started = AtomicBoolean(false)
                    Mono
                        .defer {
                            started.set(true)
                            processMono(acquired, lockedAtNanos)
                        }.subscribeOn(reqShieldConfig.scheduler)
                        // After subscribeOn: a scheduler that rejects the task never subscribes the chain above, so
                        // the releases inside processMono never run and the token is released here.
                        .doFinally { if (!started.get()) releaseLock(key, lockType, acquired, callerContext) }
                }
            } else {
                // A global or custom lock can take time and can emit on its client's event loop, so its call and all
                // that follows stay on the scheduler.
                val attempt =
                    token
                        .flatMap { acquired ->
                            refreshSignals.register(key, acquired, CompletableFuture())
                            processMono(acquired, lockStartNanos(lock, nanosBeforeAttempt))
                        }.subscribeOn(reqShieldConfig.scheduler)
                if (lock is KeyGlobalLock) {
                    // A refresh of this JVM runs: the store would only answer no
                    if (refreshSignals.find(key) != null) return
                    // The guard is a local lock, so it answers on this thread. Only the read that gets it reaches the
                    // scheduler and the store: one read in each interval, see refreshAttemptGuard.
                    refreshAttemptGuard.tryLock(GLOBAL_REFRESH_ATTEMPT_KEY_PREFIX + key, lockType).flatMap { attempt }
                } else {
                    attempt
                }
            }

        updateMono
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

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE) {
            // This mode never creates a cache entry through the lock, so no token is taken and no lock start is read.
            return createReqShieldData(key, callable, timeToLiveMillis, lockType, null, null, 0L, callerContext)
        }

        val lock = reqShieldConfig.keyLock
        if (lock is KeyGlobalLock) {
            // With a global lock, only the request that gets the front lock of this JVM asks the store
            return creationFrontLock
                .tryLock(frontKeyOf(key), lockType)
                .flatMap { frontToken -> createAsRepresentative(key, callable, timeToLiveMillis, budget, callerContext, lock, frontToken) }
                .switchIfEmpty(
                    Mono.defer {
                        handleLockFailure(key, callable, timeToLiveMillis, budget, callerContext)
                    },
                )
        }

        val nanosBeforeAttempt = nanosBeforeAttempt(lock)
        return lock
            .tryLock(key, lockType)
            .flatMap { token ->
                // From here on, requests of this JVM that lose the lock wait for this signal instead of a full poll interval.
                creationSignals.register(key, token, CompletableFuture())
                createUnderLock(key, callable, timeToLiveMillis, token, lockStartNanos(lock, nanosBeforeAttempt), callerContext)
            }.switchIfEmpty(
                Mono.defer {
                    handleLockFailure(key, callable, timeToLiveMillis, budget, callerContext)
                },
            )
    }

    /**
     * Runs the request that got the front lock of [key] in this JVM. It asks the store for the global lock. If it
     * gets the lock, it creates the entry, and the release of the global lock gives the front lock back. If a request
     * of another JVM holds the lock, it reads the cache for all requests of this JVM, see [pollAsRepresentative].
     *
     * The front lock is given back exactly once, also on an error or a cancel, and always before its signal completes.
     */
    private fun createAsRepresentative(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        budget: ReadBudget,
        callerContext: ContextView,
        lock: KeyGlobalLock,
        frontToken: String,
    ): Mono<ReqShieldData<T>> {
        val lockType = LockType.CREATE
        // True while this request must give the front lock back itself. Either the release or the handover sets it to false.
        val holdsFront = AtomicBoolean(true)

        fun releaseFrontOnce() {
            if (holdsFront.compareAndSet(true, false)) releaseFrontLock(key, frontToken)
        }

        // The requests of this JVM that lost the front lock wait for this signal from now on
        creationSignals.register(key, frontToken, CompletableFuture())
        val nanosBeforeAttempt = nanosBeforeAttempt(lock)
        return lock
            .tryLock(key, lockType)
            .flatMap { token ->
                // The release of the global lock gives the front lock back too, and completes its signal. After a
                // cancel the front lock is already free: the recheck below is cancelled and releases the token.
                if (holdsFront.compareAndSet(true, false)) frontTokens[token] = frontToken
                createUnderLock(key, callable, timeToLiveMillis, token, lockStartNanos(lock, nanosBeforeAttempt), callerContext)
            }.switchIfEmpty(
                Mono.defer {
                    // A request of another JVM holds the global lock
                    pollAsRepresentative(key, budget)
                        .flatMap { waited ->
                            // Given back before the result goes on, so the waiters of this JVM read the cache at once
                            releaseFrontOnce()
                            if (waited is Waited.Found) Mono.just(waited.data) else callWithoutLock(callable, timeToLiveMillis)
                        }
                        // A lock can emit on its client's event loop; keep the reads and the supplier call off that thread.
                        .subscribeOn(reqShieldConfig.scheduler)
                },
            )
            // A failed lock call: the front lock is given back before the error goes on
            .doOnError { releaseFrontOnce() }
            // A cancel: doFinally cancels the upstream first, so KeyGlobalLock releases its token before the waiters wake.
            .doFinally { releaseFrontOnce() }
    }

    /**
     * Holds the creation lock [token]: reads the cache once more and creates the entry on a miss. Each path ends in
     * exactly one release of [token].
     */
    private fun createUnderLock(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        token: String,
        lockedAtNanos: Long,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> {
        val lockType = LockType.CREATE
        val owner = AtomicReference(LockOwner.RECHECK)
        // Another request may have filled the cache between our initial miss and lock acquisition.
        return Mono
            .defer { executeGetCacheFunction(reqShieldConfig.getCacheFunction, key) }
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
    }

    /** Calls the supplier without a lock, after a wait gave up. No lock was acquired, so nothing is released on failure. */
    private fun callWithoutLock(
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> =
        executeCallable(callable)
            .map { data -> buildReqShieldData(data, timeToLiveMillis) }
            .switchIfEmpty(Mono.fromSupplier { buildReqShieldData(null, timeToLiveMillis) })

    /**
     * The clock before a lock attempt, for a lock whose start is read there. A lock in a store starts when the store
     * takes it, before the answer arrives, so its start is the time before the attempt. A local lock is not read
     * here: see [lockStartNanos].
     */
    private fun nanosBeforeAttempt(lock: KeyLock): Long = if (lock is KeyLocalLock) 0L else System.nanoTime()

    /**
     * The start of a lock that this request got, for the check whether the lock timed out before a late write.
     * A local lock starts inside tryLock, so its start is read after the attempt, by the request that got the lock.
     * A request that loses a local lock - each read of an old entry while its refresh runs - reads no clock:
     * System.nanoTime() can be slow when many threads call it at once.
     */
    private fun lockStartNanos(
        lock: KeyLock,
        nanosBeforeAttempt: Long,
    ): Long = if (lock is KeyLocalLock) System.nanoTime() else nanosBeforeAttempt

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
                    Waited.GaveUp -> callWithoutLock(callable, timeToLiveMillis)
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
                        val wait =
                            if (signal != null) {
                                lastSignal.set(signal)
                                waitForNextRead(signal, GET_CACHE_INTERVAL_MILLIS)
                            } else {
                                // Or just after it: look once more after a short pause, which is taken off the interval
                                Mono.delay(Duration.ofMillis(SIGNAL_REGISTRATION_GRACE_MILLIS)).flatMap {
                                    val lateSignal = signals.find(key)
                                    if (lateSignal != null) lastSignal.set(lateSignal)
                                    waitForNextRead(lateSignal, GET_CACHE_INTERVAL_MILLIS - SIGNAL_REGISTRATION_GRACE_MILLIS)
                                }
                            }
                        wait
                            .doOnNext { released.set(it) }
                            .thenReturn(index)
                    }
            }.onErrorResume(GetCacheUnavailableException::class.java) { Mono.just(Waited.GaveUp) }
            .defaultIfEmpty(Waited.GaveUp)
    }

    /**
     * Reads the cache while a request of another JVM holds the global lock. No signal of that request reaches this
     * JVM, so the wait between two reads starts short and grows to the poll interval: see [representativePollMillis].
     * The reads count in [budget] like the reads of any wait, and the supplier is never called from the loop.
     *
     * Emits [Waited.Found] with the entry, or [Waited.GaveUp] when the reads are used up or the cache looks unavailable.
     */
    private fun pollAsRepresentative(
        key: String,
        budget: ReadBudget,
    ): Mono<Waited<T>> {
        val maxReads = reqShieldConfig.maxAttemptGetCache
        return Mono
            .defer {
                if (budget.reads.get() >= maxReads) return@defer Mono.just<Waited<T>>(Waited.GaveUp)
                budget.reads.incrementAndGet()
                getCacheWhileWaitingForLock(key, budget.consecutiveFailures).map<Waited<T>> { Waited.Found(it) }
            }.repeatWhenEmpty { emptyReads ->
                emptyReads
                    // No wait after the last read
                    .takeWhile { budget.reads.get() < maxReads }
                    .concatMap { index -> waitForNextRead(null, representativePollMillis(index.toInt())).thenReturn(index) }
            }.onErrorResume(GetCacheUnavailableException::class.java) { Mono.just(Waited.GaveUp) }
            .defaultIfEmpty(Waited.GaveUp)
    }

    /**
     * Waits until the holder of [signal] gave its lock back, for at most [millis].
     * Emits true when the holder gave the lock back in that time.
     */
    private fun waitForNextRead(
        signal: CompletableFuture<Unit>?,
        millis: Long,
    ): Mono<Boolean> {
        val delay = Mono.delay(Duration.ofMillis(millis))
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
                if (lockType == LockType.UPDATE) {
                    refreshSignals.release(key, token)
                    return@doFinally
                }
                // A request that got a global lock holds the front lock of its JVM too, and registered its signal with it
                val frontToken = frontTokens.remove(token)
                if (frontToken != null) releaseFrontLock(key, frontToken) else creationSignals.release(key, token)
            }.contextWrite(callerContext)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to unlock key '{}': {}", key, e.message, e) },
            )
    }

    private fun frontKeyOf(key: String): String = GLOBAL_CREATION_FRONT_KEY_PREFIX + key

    /**
     * Gives the front lock back and then wakes the requests that wait for [frontToken]. The order matters: a
     * request that wakes while the front lock is still held loses it again, finds no signal, and reads the cache at
     * the poll interval until its reads are used up.
     */
    private fun releaseFrontLock(
        key: String,
        frontToken: String,
    ) {
        // The front lock is a local lock: its unlock runs at once on this thread, before doFinally completes the signal
        Mono
            .defer { creationFrontLock.unLock(frontKeyOf(key), LockType.CREATE, frontToken) }
            .doFinally { creationSignals.release(key, frontToken) }
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to unlock the front lock of key '{}': {}", key, e.message, e) },
            )
    }

    private fun executeCallable(callable: Callable<Mono<T?>>): Mono<T?> =
        // Deferred so a supplier that throws synchronously fails as an onError signal,
        // which keeps the lock release of the caller reachable.
        Mono
            .defer { callable.call() }
            .onErrorMap { e -> ClientException(ErrorCode.SUPPLIER_ERROR, cause = e) }

    companion object {
        private val instanceSequence = AtomicLong()

        /**
         * For instances with a local lock. Shared like the local lock map: requests for one key collapse across
         * those instances, so a waiter of one instance must see the creation that another instance runs.
         */
        internal val creationSignals = ReleaseSignals<CompletableFuture<Unit>> { it.complete(Unit) }

        /** For instances with a local lock: the refreshes that run in this JVM under that lock. */
        internal val refreshSignals = ReleaseSignals<CompletableFuture<Unit>> { it.complete(Unit) }
    }
}
