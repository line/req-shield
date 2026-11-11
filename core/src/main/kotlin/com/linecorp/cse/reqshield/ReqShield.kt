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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
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
        val lockedAtNanos = System.nanoTime()

        val token =
            try {
                lockFor(lockType).tryLock(key, lockType)
            } catch (e: Exception) {
                // The caller already has its data: a failing lock store only skips this refresh
                log.warn("Failed to lock key '{}' for a cache refresh, so the refresh is skipped", key, e)
                return
            } ?: return // another request already refreshes this key

        runInBackground(key, lockType, token) {
            val reqShieldData =
                buildReqShieldData(
                    executeCallable(callable, key, lockType, token),
                    timeToLiveMillis,
                )
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt)
        }
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE
        val onlyUpdateCache = reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE

        // ONLY_UPDATE_CACHE collapses requests on cache update only, so the creation runs without a lock
        val lockedAtNanos = System.nanoTime()
        val token = if (onlyUpdateCache) null else reqShieldConfig.keyLock.tryLock(key, lockType)

        if (token != null) {
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

        return if (onlyUpdateCache || token != null) {
            createReqShieldData(key, callable, timeToLiveMillis, lockType, token, lockedAtNanos)
        } else {
            handleLockFailure(key, callable, timeToLiveMillis)
        }
    }

    /**
     * Calls the supplier and writes its result to the cache before it returns. The write runs on the caller's thread:
     * a write that waits on the executor could run after an evict that followed the reply and put the evicted value
     * back, and it would wait behind the refreshes queued there while other requests wait for this entry.
     */
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
        try {
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt = null)
        } catch (e: Exception) {
            // The caller has its data: a failed cache write must not cost it the reply
            log.error("Failed to write the cache for key '{}'", key, e)
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
     * Runs the refresh [task] on the configured executor. The task releases the lock identified by [token] itself, so when the
     * executor rejects it - a saturated bounded pool, or one already shut down - the lock is released here instead.
     * Only that refresh is dropped: the caller still gets its data.
     */
    private fun runInBackground(
        key: String,
        lockType: LockType,
        token: String,
        task: () -> Unit,
    ) {
        try {
            CompletableFuture
                .runAsync(task, reqShieldConfig.executor)
                .whenComplete { _, e -> if (e != null) logAsyncFailure(key, e) }
        } catch (e: RejectedExecutionException) {
            log.warn("Executor rejected the cache refresh for key '{}', so it is skipped", key, e)
            releaseQuietly(key, lockType, token, "after the executor rejected its task")
        }
    }

    /**
     * Another request holds the lock: poll the cache until that request publishes its result.
     *
     * Polling runs on the caller's thread, which is blocked for the duration of the wait either way.
     * Keeping it here gives the wait a single termination condition and means a saturated executor
     * cannot stall it. The supplier is never called from the polling loop - it is called on this
     * thread only after the wait gave up, so at most one extra supplier call per waiting request
     * happens.
     *
     * The wait ends after [ReqShieldConfiguration.maxAttemptGetCache] polls, or earlier when
     * [MAX_CONSECUTIVE_GET_CACHE_FAILURES] reads failed in a row (the cache looks unavailable).
     * A poll whose cache read fails still counts as an attempt, so the wait stays bounded.
     */
    private fun handleLockFailure(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        var attempts = 0
        var consecutiveFailures = 0

        while (attempts < reqShieldConfig.maxAttemptGetCache) {
            attempts++
            sleepBetweenPolls(key)

            try {
                val cachedData = reqShieldConfig.getCacheFunction.invoke(key)
                if (cachedData != null) return cachedData

                consecutiveFailures = 0
            } catch (e: Exception) {
                log.warn("Cache read failed while waiting for the cache to be created for key '{}'", key, e)
                if (++consecutiveFailures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) break
            }
        }

        // No lock was acquired by this request, so there is nothing to release on failure
        return buildReqShieldData(executeCallable(callable, key, null, null), timeToLiveMillis)
    }

    private fun sleepBetweenPolls(key: String) {
        try {
            Thread.sleep(GET_CACHE_INTERVAL_MILLIS)
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
                // No retry needed: false means lock already released or expired (not an error)
                val unlocked = lockFor(lockType).unLock(key, lockType, token)
                if (!unlocked) {
                    log.debug("Lock already released or expired for key '{}'", key)
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
        }
    }

    private fun logAsyncFailure(
        key: String,
        throwable: Throwable,
    ) {
        val cause = if (throwable is CompletionException) throwable.cause ?: throwable else throwable
        log.error("Asynchronous cache task failed for key '{}'", key, cause)
    }
}
