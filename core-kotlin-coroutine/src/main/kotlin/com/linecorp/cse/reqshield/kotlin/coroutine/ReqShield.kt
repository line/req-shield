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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

private val log = LoggerFactory.getLogger(ReqShield::class.java)

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    /**
     * Guards the refresh of an entry in [ReqShieldWorkMode.ONLY_CREATE_CACHE]. That mode does not call the configured
     * lock for a refresh, so without a guard each request for an old entry would launch its own refresh. This lock
     * lives in this JVM only and expires like any other local lock.
     */
    private val localRefreshLock: KeyLock by lazy { KeyLocalLock(reqShieldConfig.lockTimeoutMillis) }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun updateReqShieldData(
        key: String,
        callable: suspend () -> T?,
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
                rethrowIfCallerCancelled(e)
                // The caller already has its data: a failing lock store only skips this refresh
                log.warn("[Req-Shield] failed to lock key '{}' for a cache refresh, so the refresh is skipped", key, e)
                return
            } ?: return // another request already refreshes this key

        // The refresh can outlive the caller's request, so it takes the caller's context elements
        // (Reactor context, MDC, security). It never takes the caller's Job, dispatcher or exception handler: the
        // refresh must not become a child of the request or run on its thread. The elements of the scope win.
        val refreshContext =
            currentCoroutineContext()
                .minusKey(Job)
                .minusKey(ContinuationInterceptor)
                .minusKey(CoroutineExceptionHandler) + reqShieldConfig.scope.coroutineContext

        reqShieldConfig.scope.launch(refreshContext, start = CoroutineStart.ATOMIC) {
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

    private suspend fun handleLockForCacheCreation(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE
        // ONLY_UPDATE_CACHE collapses requests on update only, so the creation runs without a lock.
        val onlyUpdateCache = reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE
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
                // A hit, read failure, or cancellation must release the token; creation owns it on a miss.
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
     * Calls the supplier, writes its result to the cache and returns after that write ended. A caller that returned
     * before the write could evict the entry first, and the write would then put the evicted value back.
     *
     * The write runs on the configured scope and the caller only waits for it. So the write goes on when the caller
     * is cancelled after its supplier call ended: the requests that wait for this entry would each call the supplier
     * themselves if the write stopped. The scope can still cancel a write that does not return.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
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
        reqShieldConfig.scope
            .launch(start = CoroutineStart.ATOMIC) {
                try {
                    writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("[Req-Shield] failed to create the cache of key '{}'", key, e)
                }
            }.join()
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
    private suspend fun writeUnlessSuperseded(
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
        lockedAtNanos: Long,
        seenCreatedAt: Long?,
    ) {
        if (token != null && isSuperseded(key, lockedAtNanos, seenCreatedAt)) {
            log.debug("[Req-Shield] skipped the cache write of key '{}': its lock timed out and a newer entry exists", key)
            releaseQuietly(key, lockType, token, "after its late cache write was skipped")
            return
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
     * Another request owns the create lock, so wait for it to fill the cache.
     *
     * Polling runs on the caller's coroutine: [delay] is a cancellation point, so a cancelled caller
     * stops polling immediately. Consecutive cache-read failures are treated as a cache outage and
     * end the wait early, after which the supplier is called on this coroutine as the last resort.
     */
    private suspend fun handleLockFailure(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        var attempts = 0
        var consecutiveFailures = 0

        while (attempts < reqShieldConfig.maxAttemptGetCache) {
            val cachedData =
                try {
                    executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                        .also { consecutiveFailures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ClientException) {
                    log.warn("[Req-Shield] failed to read the cache of key '{}' while waiting", key, e)
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) break
                    null
                }

            if (cachedData != null) return cachedData

            attempts++
            delay(GET_CACHE_INTERVAL_MILLIS)
        }

        // The other request never filled the cache: fall back to the supplier. No lock was acquired
        // here, so there is nothing to release, and a supplier failure propagates as SUPPLIER_ERROR.
        return buildReqShieldData(executeCallable(callable, key, null, null), timeToLiveMillis)
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
        } catch (e: Exception) {
            // Release the lock only when this call actually acquired one.
            if (token != null && lockType != null) {
                releaseQuietly(key, lockType, token, "after the supplier failed")
            }
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
        }
    }
}
