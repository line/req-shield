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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val log = LoggerFactory.getLogger(ReqShield::class.java)

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
    internal val creationSignals: ReleaseSignals<CompletableDeferred<Unit>> =
        if (reqShieldConfig.keyLock is KeyLocalLock) Companion.creationSignals else ReleaseSignals { it.complete(Unit) }

    /**
     * The refreshes of this instance. A request that finds an entry gone waits for the refresh of its key. The
     * registry is never shared: a request of another instance, with a cache of its own, must not wait for a refresh
     * that writes to this cache. In version 1.2.0 such a request created its entry at once.
     */
    internal val refreshSignals: ReleaseSignals<CompletableDeferred<Unit>> = ReleaseSignals { it.complete(Unit) }

    /**
     * Guards the refresh of an entry in [ReqShieldWorkMode.ONLY_CREATE_CACHE]. That mode does not call the configured
     * lock for a refresh, so without a guard each request for an old entry would launch its own refresh. This lock
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

    suspend fun getAndSetReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val currentReqShieldData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
        currentReqShieldData?.let {
            if (shouldUpdateCache(it)) {
                updateReqShieldData(key, callable, timeToLiveMillis, it.createdAt)
            }
            return it
        } ?: run {
            return handleLockForCacheCreation(key, callable, timeToLiveMillis)
        }
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

    private suspend fun updateReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
        // createdAt of the entry this request read, which tells that entry apart from one written during the refresh
        seenCreatedAt: Long,
    ) {
        val lockType = LockType.UPDATE
        val lock = lockFor(lockType)
        if (lock is KeyGlobalLock && !mayAskForGlobalRefreshLock(key)) return
        val nanosBeforeAttempt = nanosBeforeAttempt(lock)
        val token =
            try {
                lock.tryLock(key, lockType)
            } catch (e: Exception) {
                rethrowIfCallerCancelled(e)
                // The caller already has its data: a failing lock store only skips this refresh
                log.warn("[Req-Shield] failed to lock key '{}' for a cache refresh, so the refresh is skipped", key, e)
                return
            } ?: return // another request already refreshes this key
        val lockedAtNanos = lockStartNanos(lock, nanosBeforeAttempt)
        // A request that finds the entry gone during this refresh waits for this signal instead of a supplier call
        refreshSignals.register(key, token, CompletableDeferred())

        // The refresh can outlive the caller's request, so it takes the caller's context elements
        // (Reactor context, MDC, security). It never takes the caller's Job, dispatcher or exception handler: the
        // refresh must not become a child of the request or run on its thread. The elements of the scope win.
        val refreshContext =
            currentCoroutineContext()
                .minusKey(Job)
                .minusKey(ContinuationInterceptor)
                .minusKey(CoroutineExceptionHandler) + reqShieldConfig.scope.coroutineContext

        launchInScope(key, lockType, token, refreshContext) {
            try {
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable(callable, key, lockType, token),
                        timeToLiveMillis,
                    )
                writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("[Req-Shield] failed to update the cache of key '{}'", key, e)
            }
        }
    }

    /**
     * Whether a read of an old entry may ask the store of a global lock for the refresh lock.
     * It may not while a refresh of this JVM runs: the store would only answer no. Else it may when no request of
     * this JVM asked in the last [GET_CACHE_INTERVAL_MILLIS]. So a refresh in another JVM, or a store that keeps
     * the lock of a failed refresh, costs one call of the store in that time, not one for each read.
     */
    private suspend fun mayAskForGlobalRefreshLock(key: String): Boolean {
        if (refreshSignals.find(key) != null) return false
        return refreshAttemptGuard.tryLock(GLOBAL_REFRESH_ATTEMPT_KEY_PREFIX + key, LockType.UPDATE) != null
    }

    private suspend fun handleLockForCacheCreation(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE
        // ONLY_UPDATE_CACHE collapses requests on update only, so the creation runs without a lock.
        val onlyUpdateCache = reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE
        val budget = ReadBudget()

        // The entry is gone, but a refresh that runs in this JVM is about to write it. Waiting for that refresh
        // saves a second supplier call. If the refresh ends without an entry, this request goes on as on any miss.
        refreshSignals.find(key)?.let { refresh ->
            // The wait for the refresh has its own reads. If they were taken from the reads for the creation lock, a
            // refresh that outlasts them would leave none: each request that then loses the lock would call the
            // supplier at once, without a lock.
            val waited = readUntilReleased(key, refreshSignals, ReadBudget(), refresh)
            if (waited is Waited.Found) return waited.data
        }

        while (true) {
            val lock = reqShieldConfig.keyLock
            // With a global lock, only the request that gets the front lock of this JVM asks the store
            val frontToken =
                if (!onlyUpdateCache && lock is KeyGlobalLock) {
                    creationFrontLock.tryLock(frontKeyOf(key), lockType)
                } else {
                    null
                }
            val asksTheLock = !onlyUpdateCache && (lock !is KeyGlobalLock || frontToken != null)

            var token: String? = null
            // Without a token there is no lock, and no late-write check reads this value
            var lockedAtNanos = 0L
            if (asksTheLock) {
                // The requests of this JVM that lost the front lock wait for this signal from now on.
                // Nothing between the front lock and the try below suspends, so no cancellation skips the release.
                if (frontToken != null) creationSignals.register(key, frontToken, CompletableDeferred())
                val nanosBeforeAttempt = nanosBeforeAttempt(lock)
                token =
                    try {
                        lock.tryLock(key, lockType)
                    } catch (e: Throwable) {
                        // A failed or cancelled store call gives the front lock back, and the error goes on unchanged
                        if (frontToken != null) releaseFrontLock(key, frontToken)
                        throw e
                    }
                if (token != null) lockedAtNanos = lockStartNanos(lock, nanosBeforeAttempt)
            }

            if (token != null) {
                if (frontToken != null) {
                    // The release of the global lock gives the front lock back too, and completes its signal
                    frontTokens[token] = frontToken
                } else {
                    // From here on, requests of this JVM that lose the lock wait for this signal instead of a full poll interval.
                    creationSignals.register(key, token, CompletableDeferred())
                }
                var cacheCreationRequired = false
                try {
                    // Another request may have filled the cache between our initial miss and lock acquisition.
                    val cachedData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                    if (cachedData != null) return cachedData
                    cacheCreationRequired = true
                } finally {
                    // A hit, read failure, or cancellation must release the token; creation owns it on a miss.
                    if (!cacheCreationRequired) {
                        releaseQuietly(key, lockType, token, "after the cache recheck")
                    }
                }
            }

            if (onlyUpdateCache || token != null) {
                return createReqShieldData(key, callable, timeToLiveMillis, lockType, token, lockedAtNanos)
            }

            val waited =
                if (frontToken != null) {
                    // A request of another JVM holds the global lock. This request reads the cache for all requests
                    // of this JVM, and they wait for its signal. The finally also runs on a cancellation.
                    try {
                        pollAsRepresentative(key, budget)
                    } finally {
                        releaseFrontLock(key, frontToken)
                    }
                } else {
                    readUntilReleased(key, creationSignals, budget)
                }
            when (waited) {
                is Waited.Found -> return waited.data
                // The holder gave the lock back and wrote no entry (its supplier failed, for example), so this
                // request tries to be the next holder. The requests that lose again wait for the new holder.
                Waited.Released -> continue
                // The other request never filled the cache: fall back to the supplier. No lock was acquired here,
                // so there is nothing to release, and a supplier failure propagates as SUPPLIER_ERROR.
                Waited.GaveUp -> return buildReqShieldData(executeCallable(callable, key, null, null), timeToLiveMillis)
            }
        }
    }

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

    private suspend fun createReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
        lockType: LockType,
        token: String?,
        lockedAtNanos: Long,
    ): ReqShieldData<T> {
        val reqShieldData =
            buildReqShieldData(
                executeCallable(callable, key, lockType, token),
                timeToLiveMillis,
            )
        launchInScope(key, lockType, token) {
            try {
                writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("[Req-Shield] failed to create the cache of key '{}'", key, e)
            }
        }
        return reqShieldData
    }

    /**
     * Launches [task] on the configured scope. The task releases the lock of [token] itself. If the dispatcher of
     * the scope throws, `launch` throws and the task never runs, so the lock is released here.
     *
     * The caller already has its data, so an exception of the dispatcher only skips that cache write or refresh: it
     * is logged, and the caller gets its data. The caller's own cancellation and an [Error] go on after the release.
     * A null [token] means this request holds no lock, so nothing is released.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun launchInScope(
        key: String,
        lockType: LockType,
        token: String?,
        context: CoroutineContext = EmptyCoroutineContext,
        task: suspend CoroutineScope.() -> Unit,
    ) {
        try {
            // ATOMIC: a cancelled scope still runs the task, which then releases the lock
            reqShieldConfig.scope.launch(context, start = CoroutineStart.ATOMIC, block = task)
        } catch (e: Throwable) {
            if (token != null) releaseQuietly(key, lockType, token, "after the scope failed to start its task")
            if (e !is Exception) throw e
            rethrowIfCallerCancelled(e)
            log.warn("[Req-Shield] the scope could not start the background task of key '{}', so it is skipped", key, e)
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
     *
     * Each path out of this function releases the lock: the skipped write, the write, and a read before the late
     * write that ends with an exception (the scope was cancelled during the read, for example).
     */
    private suspend fun writeUnlessSuperseded(
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
        lockedAtNanos: Long,
        seenCreatedAt: Long?,
    ) {
        if (token != null) {
            val superseded =
                try {
                    isSuperseded(key, lockedAtNanos, seenCreatedAt)
                } catch (e: Throwable) {
                    // The scope was cancelled during the read, or the read threw an Error. Without this release the
                    // lock stays until it expires.
                    releaseQuietly(key, lockType, token, "after the read before its late cache write ended")
                    throw e
                }
            if (superseded) {
                log.debug("[Req-Shield] skipped the cache write of key '{}': its lock timed out and a newer entry exists", key)
                releaseQuietly(key, lockType, token, "after its late cache write was skipped")
                return
            }
        }
        executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
    }

    private suspend fun isSuperseded(
        key: String,
        lockedAtNanos: Long,
        seenCreatedAt: Long?,
    ): Boolean {
        if (System.nanoTime() - lockedAtNanos < lockTimeoutNanos) return false

        val cachedData =
            try {
                executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without the read nothing shows a newer entry, so the write goes on as it did before
                log.warn("[Req-Shield] failed to read the cache of key '{}' before its late cache write", key, e)
                return false
            }
        return cachedData != null && cachedData.createdAt != seenCreatedAt
    }

    /**
     * The cache reads that one request may spend on one kind of wait. The wait for a refresh has its own budget.
     * All waits for the holder of the creation lock share one, so the lock retries cannot extend the wait.
     */
    private class ReadBudget {
        var reads = 0
        var consecutiveFailures = 0
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
     * Another request holds the lock of [signals] for [key]: read the cache until that request publishes its result.
     *
     * Between two reads this request waits for the signal of the holder, for at most
     * [GET_CACHE_INTERVAL_MILLIS]. A holder in this JVM completes the signal when it gives the lock back, so the
     * next read follows its cache write at once. If that read finds no entry, the holder wrote none and the wait
     * ends as [Waited.Released]. Without a signal - the holder runs in another JVM - the wait is the whole
     * interval, which makes this a poll at a fixed interval.
     *
     * The reads run on the caller's coroutine, and each wait is a cancellation point: a cancelled caller stops at
     * once. The supplier is never called from the loop.
     *
     * The wait gives up after [ReqShieldConfiguration.maxAttemptGetCache] reads, counted in [budget] over all
     * waits of the request, or earlier when [MAX_CONSECUTIVE_GET_CACHE_FAILURES] reads failed in a row (the cache
     * looks unavailable). A read that fails still counts, so the wait stays bounded. No wait follows the last read.
     */
    private suspend fun readUntilReleased(
        key: String,
        signals: ReleaseSignals<CompletableDeferred<Unit>>,
        budget: ReadBudget,
        // The signal that made the request wait, when the caller already looked it up
        firstSignal: CompletableDeferred<Unit>? = null,
    ): Waited<T> {
        var released = false
        // The last signal that this wait saw. When it is gone from the map and complete, its holder gave the lock back,
        // also if that happened between two steps of this loop.
        var lastSignal = firstSignal

        while (budget.reads < reqShieldConfig.maxAttemptGetCache) {
            budget.reads++
            // Looked up before the read: the holder completes the signal after its cache write, so a write that
            // this read misses still ends the wait below at once.
            var signal = signals.find(key)
            if (signal == null && lastSignal?.isCompleted == true) released = true

            try {
                val cachedData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                if (cachedData != null) return Waited.Found(cachedData)

                budget.consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                log.warn("[Req-Shield] failed to read the cache of key '{}' while waiting", key, e)
                if (++budget.consecutiveFailures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) return Waited.GaveUp
            }

            if (released) return Waited.Released

            if (budget.reads < reqShieldConfig.maxAttemptGetCache) {
                // A holder that got the lock a moment ago may have registered its signal only during the read
                if (signal == null) signal = signals.find(key)
                var waitMillis = GET_CACHE_INTERVAL_MILLIS
                if (signal == null) {
                    // Or just after it: look once more after a short pause, which is taken off the interval
                    delay(SIGNAL_REGISTRATION_GRACE_MILLIS)
                    waitMillis -= SIGNAL_REGISTRATION_GRACE_MILLIS
                    signal = signals.find(key)
                }
                if (signal != null) lastSignal = signal
                released = waitForNextRead(signal, waitMillis)
            }
        }

        return Waited.GaveUp
    }

    /**
     * Reads the cache while a request of another JVM holds the global lock. No signal of that request reaches this
     * JVM, so the wait between two reads starts short and grows to the poll interval: see [representativePollMillis].
     * The reads count in [budget] like the reads of any wait, and the supplier is never called from the loop.
     *
     * @return [Waited.Found] with the entry, or [Waited.GaveUp] when the reads are used up or the cache looks
     * unavailable.
     */
    private suspend fun pollAsRepresentative(
        key: String,
        budget: ReadBudget,
    ): Waited<T> {
        var waits = 0

        while (budget.reads < reqShieldConfig.maxAttemptGetCache) {
            budget.reads++

            try {
                val cachedData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                if (cachedData != null) return Waited.Found(cachedData)

                budget.consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                log.warn("[Req-Shield] failed to read the cache of key '{}' while waiting", key, e)
                if (++budget.consecutiveFailures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) return Waited.GaveUp
            }

            if (budget.reads < reqShieldConfig.maxAttemptGetCache) delay(representativePollMillis(waits++))
        }

        return Waited.GaveUp
    }

    /**
     * Waits until the holder of [signal] gave its lock back, for at most [millis]. A timeout of this wait
     * cancels only this await: the signal stays open for the other waiters.
     *
     * @return true when the holder gave the lock back in that time.
     */
    private suspend fun waitForNextRead(
        signal: CompletableDeferred<Unit>?,
        millis: Long,
    ): Boolean {
        if (signal == null) {
            delay(millis)
            return false
        }
        // The signal is read again: a release at the moment of the timeout must not look like a holder that still works
        return withTimeoutOrNull(millis) { signal.await() } != null || signal.isCompleted
    }

    private fun buildReqShieldData(
        value: T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> =
        ReqShieldData(
            value = value,
            timeToLiveMillis = timeToLiveMillis,
        )

    private suspend fun executeGetCacheFunction(
        getFunction: suspend (String) -> ReqShieldData<T>?,
        key: String,
    ): ReqShieldData<T>? =
        try {
            getFunction(key)
        } catch (e: Exception) {
            rethrowIfCallerCancelled(e)
            throw ClientException(ErrorCode.GET_CACHE_ERROR, cause = e)
        }

    private suspend fun executeSetCacheFunction(
        setFunction: suspend (String, ReqShieldData<T>, Long) -> Boolean,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
    ) {
        try {
            currentCoroutineContext().ensureActive()
            setFunction(key, value, value.timeToLiveMillis)
        } catch (e: Exception) {
            rethrowIfCallerCancelled(e)
            throw ClientException(ErrorCode.SET_CACHE_ERROR, cause = e)
        } finally {
            // Only the holder of a token has a lock to release. A release that fails, or times out inside the lock
            // function, is logged: thrown from here it would replace the result of the write, and a
            // CancellationException would end the background coroutine as cancelled without any log.
            if (token != null) {
                releaseQuietly(key, lockType, token, "after the cache write")
            }
        }
    }

    private suspend fun executeCallable(
        callable: suspend () -> T?,
        key: String,
        lockType: LockType?,
        token: String?,
    ): T? =
        try {
            currentCoroutineContext().ensureActive()
            callable()
        } catch (e: Throwable) {
            // Release the lock only when this call actually acquired one.
            if (token != null && lockType != null) {
                releaseQuietly(key, lockType, token, "after the supplier failed")
            }
            // An Error goes on unchanged: only an Exception is a failure of the supplier
            if (e !is Exception) throw e
            rethrowIfCallerCancelled(e)
            throw ClientException(ErrorCode.SUPPLIER_ERROR, cause = e)
        }

    /**
     * Rethrows [e] when it is the cancellation of the coroutine that called the client function.
     *
     * A client function can also throw a [CancellationException] while its caller is still active: a `withTimeout`
     * inside a supplier, or a job the function started and cancelled. That is a failure of the function, so the
     * caller of this method goes on to report it as one instead of ending an active caller like a cancelled one.
     */
    private suspend fun rethrowIfCallerCancelled(e: Exception) {
        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
    }

    /**
     * Releases the lock identified by [token], even when the caller is cancelled. A failed release must not cost the
     * caller a result it already has (its data, the error it is about to get, or its cancellation), so it is only
     * logged; the lock then expires on its own.
     *
     * Every release of a lock goes through here. It also wakes the requests of this JVM that wait for the holder of
     * [token]: the waiters of a creation for a create lock, the waiters of a refresh for an update lock. It wakes
     * them also when the release failed: the waiters then read the cache and go on as they would have after their
     * poll interval. A create lock of a global lock also gives back the front lock that its request holds, and its
     * waiters wait for the signal of that front lock.
     */
    private suspend fun releaseQuietly(
        key: String,
        lockType: LockType,
        token: String,
        situation: String,
    ) {
        try {
            withContext(NonCancellable) {
                lockFor(lockType).unLock(key, lockType, token)
            }
        } catch (unlockError: Exception) {
            log.error("[Req-Shield] failed to unlock key '{}' {}", key, situation, unlockError)
        } finally {
            if (lockType == LockType.UPDATE) {
                refreshSignals.release(key, token)
            } else {
                // A request that got a global lock holds the front lock of its JVM too, and registered its signal with it
                val frontToken = frontTokens.remove(token)
                if (frontToken != null) releaseFrontLock(key, frontToken) else creationSignals.release(key, token)
            }
        }
    }

    private fun frontKeyOf(key: String): String = GLOBAL_CREATION_FRONT_KEY_PREFIX + key

    /**
     * Gives the front lock back and then wakes the requests that wait for [frontToken], also when the caller is
     * cancelled. The order matters: a request that wakes while the front lock is still held loses it again, finds no
     * signal, and reads the cache at the poll interval until its reads are used up.
     */
    private suspend fun releaseFrontLock(
        key: String,
        frontToken: String,
    ) {
        try {
            withContext(NonCancellable) {
                creationFrontLock.unLock(frontKeyOf(key), LockType.CREATE, frontToken)
            }
        } catch (unlockError: Exception) {
            log.error("[Req-Shield] failed to unlock the front lock of key '{}'", key, unlockError)
        } finally {
            creationSignals.release(key, frontToken)
        }
    }

    companion object {
        private val instanceSequence = AtomicLong()

        /**
         * For instances with a local lock. Shared like the local lock map: requests for one key collapse across
         * those instances, so a waiter of one instance must see the creation that another instance runs.
         */
        internal val creationSignals = ReleaseSignals<CompletableDeferred<Unit>> { it.complete(Unit) }
    }
}
