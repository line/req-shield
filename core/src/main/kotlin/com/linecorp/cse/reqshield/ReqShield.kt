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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GLOBAL_REFRESH_ATTEMPT_KEY_PREFIX
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.constant.ConfigValues.SIGNAL_REGISTRATION_GRACE_MILLIS
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.signal.ReleaseSignals
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(ReqShield::class.java)

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    /**
     * Guards the refresh of an entry in [ReqShieldWorkMode.ONLY_CREATE_CACHE]. That mode does not call the configured
     * lock for a refresh, so without a guard each request for an old entry would queue its own refresh on the
     * executor. This lock lives in this JVM only and expires like any other local lock.
     */
    private val localRefreshLock: KeyLock by lazy { KeyLocalLock(reqShieldConfig.lockTimeoutMillis) }

    /**
     * Limits how often this JVM asks the lock store for the refresh lock of one key: once in
     * [GET_CACHE_INTERVAL_MILLIS]. Without it each read of an old entry would call the store while a request of
     * another JVM holds the lock. An attempt takes this local lock and never gives it back: it expires.
     */
    private val refreshAttemptGuard: KeyLock by lazy { KeyLocalLock(GET_CACHE_INTERVAL_MILLIS) }

    private val lockTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(reqShieldConfig.lockTimeoutMillis)

    fun getAndSetReqShieldData(
        key: String,
        callable: Callable<T?>,
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

    private fun updateReqShieldData(
        key: String,
        callable: Callable<T?>,
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
                // The caller already has its data: a failing lock store only skips this refresh
                log.warn("Failed to lock key '{}' for a cache refresh, so the refresh is skipped", key, e)
                return
            } ?: return // another request already refreshes this key
        val lockedAtNanos = lockStartNanos(lock, nanosBeforeAttempt)
        // A request that finds the entry gone during this refresh waits for this signal instead of a supplier call
        refreshSignals.register(key, token, CountDownLatch(1))

        runInBackground(key, lockType, token) {
            val reqShieldData =
                buildReqShieldData(
                    executeCallable(callable, key, lockType, token),
                    timeToLiveMillis,
                )
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt)
        }
    }

    /**
     * Whether a read of an old entry may ask the store of a global lock for the refresh lock.
     * It may not while a refresh of this JVM runs: the store would only answer no. Else it may when no request of
     * this JVM asked in the last [GET_CACHE_INTERVAL_MILLIS]. So a refresh in another JVM, or a store that keeps
     * the lock of a failed refresh, costs one call of the store in that time, not one for each read.
     */
    private fun mayAskForGlobalRefreshLock(key: String): Boolean {
        if (refreshSignals.find(key) != null) return false
        return refreshAttemptGuard.tryLock(GLOBAL_REFRESH_ATTEMPT_KEY_PREFIX + key, LockType.UPDATE) != null
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE
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
            // ONLY_UPDATE_CACHE collapses requests on cache update only, so the creation runs without a lock
            val lock = reqShieldConfig.keyLock
            val nanosBeforeAttempt = if (onlyUpdateCache) 0L else nanosBeforeAttempt(lock)
            val token = if (onlyUpdateCache) null else lock.tryLock(key, lockType)
            // Without a token there is no lock, and no late-write check reads this value
            val lockedAtNanos = if (token == null) 0L else lockStartNanos(lock, nanosBeforeAttempt)

            if (token != null) {
                // From here on, requests of this JVM that lose the lock wait for this signal instead of a full poll interval.
                creationSignals.register(key, token, CountDownLatch(1))
                var cacheCreationRequired = false
                try {
                    // Another request may have filled the cache between our initial miss and lock acquisition.
                    val cachedData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                    if (cachedData != null) return cachedData
                    cacheCreationRequired = true
                } finally {
                    // On a miss, the existing creation path keeps the lock until its asynchronous write finishes.
                    if (!cacheCreationRequired) {
                        releaseQuietly(key, lockType, token, "after the cache recheck")
                    }
                }
            }

            if (onlyUpdateCache || token != null) {
                return createReqShieldData(key, callable, timeToLiveMillis, lockType, token, lockedAtNanos)
            }

            when (val waited = readUntilReleased(key, creationSignals, budget)) {
                is Waited.Found -> return waited.data
                // The holder gave the lock back and wrote no entry (its supplier failed, for example), so this
                // request tries to be the next holder. The requests that lose again wait for the new holder.
                Waited.Released -> continue
                // No lock was acquired by this request, so there is nothing to release on failure
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

    private fun createReqShieldData(
        key: String,
        callable: Callable<T?>,
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
        runInBackground(key, lockType, token) {
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt = null)
        }

        return reqShieldData
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
    ) {
        if (token != null && isSuperseded(key, lockedAtNanos, seenCreatedAt)) {
            log.debug("Skipped the cache write for key '{}': its lock timed out and a newer entry exists", key)
            releaseQuietly(key, lockType, token, "after its late cache write was skipped")
            return
        }
        executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
    }

    private fun isSuperseded(
        key: String,
        lockedAtNanos: Long,
        seenCreatedAt: Long?,
    ): Boolean {
        if (System.nanoTime() - lockedAtNanos < lockTimeoutNanos) return false

        val cachedData =
            try {
                reqShieldConfig.getCacheFunction.invoke(key)
            } catch (e: Exception) {
                // Without the read nothing shows a newer entry, so the write goes on as it did before
                log.warn("Cache read failed before the late cache write for key '{}'", key, e)
                return false
            }
        return cachedData != null && cachedData.createdAt != seenCreatedAt
    }

    /**
     * Runs [task] on the configured executor. The task releases the lock identified by [token] itself, so when the
     * executor rejects it - a saturated bounded pool, or one already shut down - the lock is released here instead.
     * Only that background cache write or refresh is dropped: the caller still gets its data.
     */
    private fun runInBackground(
        key: String,
        lockType: LockType,
        token: String?,
        task: () -> Unit,
    ) {
        try {
            CompletableFuture
                .runAsync(task, reqShieldConfig.executor)
                .whenComplete { _, e -> if (e != null) logAsyncFailure(key, e) }
        } catch (e: RejectedExecutionException) {
            log.warn("Executor rejected the background cache task for key '{}', so it is skipped", key, e)
            if (token != null) {
                releaseQuietly(key, lockType, token, "after the executor rejected its task")
            }
        }
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
     * The reads run on the caller's thread, which is blocked for the duration of the wait either way.
     * Keeping them here gives the wait a single termination condition and means a saturated executor
     * cannot stall it. The supplier is never called from the loop.
     *
     * The wait gives up after [ReqShieldConfiguration.maxAttemptGetCache] reads, counted in [budget] over all
     * waits of the request, or earlier when [MAX_CONSECUTIVE_GET_CACHE_FAILURES] reads failed in a row (the cache
     * looks unavailable). A read that fails still counts, so the wait stays bounded.
     */
    private fun readUntilReleased(
        key: String,
        signals: ReleaseSignals<CountDownLatch>,
        budget: ReadBudget,
        // The signal that made the request wait, when the caller already looked it up
        firstSignal: CountDownLatch? = null,
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
            if (signal == null && lastSignal?.count == 0L) released = true

            try {
                val cachedData = reqShieldConfig.getCacheFunction.invoke(key)
                if (cachedData != null) return Waited.Found(cachedData)

                budget.consecutiveFailures = 0
            } catch (e: Exception) {
                log.warn("Cache read failed while waiting for the cache to be created for key '{}'", key, e)
                if (++budget.consecutiveFailures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) return Waited.GaveUp
            }

            if (released) return Waited.Released

            if (budget.reads < reqShieldConfig.maxAttemptGetCache) {
                // A holder that got the lock a moment ago may have registered its signal only during the read
                if (signal == null) signal = signals.find(key)
                var waitMillis = GET_CACHE_INTERVAL_MILLIS
                if (signal == null) {
                    // Or just after it: look once more after a short pause, which is taken off the interval
                    waitForNextRead(key, null, SIGNAL_REGISTRATION_GRACE_MILLIS)
                    waitMillis -= SIGNAL_REGISTRATION_GRACE_MILLIS
                    signal = signals.find(key)
                }
                if (signal != null) lastSignal = signal
                released = waitForNextRead(key, signal, waitMillis)
            }
        }

        return Waited.GaveUp
    }

    /**
     * Waits until the holder of [signal] gave its lock back, for at most [millis].
     *
     * @return true when the holder gave the lock back in that time.
     */
    private fun waitForNextRead(
        key: String,
        signal: CountDownLatch?,
        millis: Long,
    ): Boolean {
        try {
            if (signal == null) {
                Thread.sleep(millis)
                return false
            }
            // The count is read again: a release at the moment of the timeout must not look like a holder that still works
            return signal.await(millis, TimeUnit.MILLISECONDS) || signal.count == 0L
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Interrupted while waiting for the cache to be created for key '{}'", key)
            throw ClientException(ErrorCode.GET_CACHE_ERROR, cause = e)
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

    private fun executeGetCacheFunction(
        getFunction: (String) -> ReqShieldData<T>?,
        key: String,
    ): ReqShieldData<T>? =
        runCatching {
            getFunction.invoke(key)
        }.getOrElse {
            throw ClientException(ErrorCode.GET_CACHE_ERROR, cause = it)
        }

    private fun executeSetCacheFunction(
        setFunction: (String, ReqShieldData<T>, Long) -> Boolean,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
    ) {
        try {
            setFunction.invoke(key, value, value.timeToLiveMillis)
        } catch (e: Exception) {
            throw ClientException(ErrorCode.SET_CACHE_ERROR, cause = e)
        } finally {
            if (token != null) {
                try {
                    // No retry needed: false means lock already released or expired (not an error)
                    val unlocked = lockFor(lockType).unLock(key, lockType, token)
                    if (!unlocked) {
                        log.debug("Lock already released or expired for key '{}'", key)
                    }
                } finally {
                    signalRelease(key, lockType, token)
                }
            }
        }
    }

    /**
     * Runs the client supplier, releasing the lock identified by [token] when it fails.
     * A null [token] means this request holds no lock, so nothing is released.
     */
    private fun executeCallable(
        callable: Callable<T?>,
        key: String,
        lockType: LockType?,
        token: String?,
    ): T? =
        runCatching {
            callable.call()
        }.getOrElse {
            if (token != null && lockType != null) {
                releaseQuietly(key, lockType, token, "after the supplier failed")
            }
            throw ClientException(ErrorCode.SUPPLIER_ERROR, cause = it)
        }

    /**
     * Releases the lock identified by [token]. A failed release must not cost the caller a result it already has
     * (its data, or the error it is about to get), so it is only logged; the lock then expires on its own.
     */
    private fun releaseQuietly(
        key: String,
        lockType: LockType,
        token: String,
        situation: String,
    ) {
        try {
            lockFor(lockType).unLock(key, lockType, token)
        } catch (unlockError: Exception) {
            log.error("Failed to unlock key '{}' {}", key, situation, unlockError)
        } finally {
            signalRelease(key, lockType, token)
        }
    }

    /**
     * Wakes the requests of this JVM that wait for the holder of [token]. Called wherever a lock is given back,
     * also when the release itself failed: the waiters then read the cache and go on as they would have after
     * their poll interval.
     */
    private fun signalRelease(
        key: String,
        lockType: LockType,
        token: String,
    ) {
        val signals = if (lockType == LockType.CREATE) creationSignals else refreshSignals
        signals.release(key, token)
    }

    private fun logAsyncFailure(
        key: String,
        throwable: Throwable,
    ) {
        val cause = if (throwable is CompletionException) throwable.cause ?: throwable else throwable
        log.error("Asynchronous cache task failed for key '{}'", key, cause)
    }

    companion object {
        /**
         * Shared by every instance, like the local lock map: requests for one key collapse across instances, so a
         * waiter of one instance must see the creation that another instance runs.
         */
        internal val creationSignals = ReleaseSignals<CountDownLatch> { it.countDown() }

        /** The refreshes that run in this JVM. A request that finds an entry gone waits for the refresh of its key. */
        internal val refreshSignals = ReleaseSignals<CountDownLatch> { it.countDown() }
    }
}
