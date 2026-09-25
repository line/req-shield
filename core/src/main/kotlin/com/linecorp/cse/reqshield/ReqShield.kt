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

private val log = LoggerFactory.getLogger(ReqShield::class.java)

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    fun getAndSetReqShieldData(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val currentReqShieldData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
        currentReqShieldData?.let {
            if (shouldUpdateCache(it)) {
                updateReqShieldData(key, callable, timeToLiveMillis)
            }
            return it
        } ?: run {
            return handleLockForCacheCreation(key, callable, timeToLiveMillis)
        }
    }

    private fun shouldUpdateCache(reqShieldData: ReqShieldData<T>): Boolean =
        decideToUpdateCache(reqShieldData.createdAt, reqShieldData.timeToLiveMillis, reqShieldConfig.decisionForUpdate)

    private fun updateReqShieldData(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ) {
        val lockType = LockType.UPDATE
        val onlyCreateCache = reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE

        // ONLY_CREATE_CACHE collapses requests on cache creation only, so the update runs without a lock
        val token = if (onlyCreateCache) null else reqShieldConfig.keyLock.tryLock(key, lockType)

        if (onlyCreateCache || token != null) {
            runInBackground(key, lockType, token) {
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable(callable, key, lockType, token),
                        timeToLiveMillis,
                    )
                executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
            }
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
                    reqShieldConfig.keyLock.unLock(key, lockType, token)
                }
            }
        }

        return if (onlyUpdateCache || token != null) {
            createReqShieldData(key, callable, timeToLiveMillis, lockType, token)
        } else {
            handleLockFailure(key, callable, timeToLiveMillis)
        }
    }

    private fun createReqShieldData(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
        lockType: LockType,
        token: String?,
    ): ReqShieldData<T> {
        val reqShieldData =
            buildReqShieldData(
                executeCallable(callable, key, lockType, token),
                timeToLiveMillis,
            )
        runInBackground(key, lockType, token) {
            executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
        }

        return reqShieldData
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
                // A failed release must not cost the caller its data; the lock then expires on its own
                try {
                    reqShieldConfig.keyLock.unLock(key, lockType, token)
                } catch (unlockError: Exception) {
                    log.error("Failed to unlock key '{}' after the executor rejected its task", key, unlockError)
                }
            }
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
                val unlocked = reqShieldConfig.keyLock.unLock(key, lockType, token)
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
                reqShieldConfig.keyLock.unLock(key, lockType, token)
            }
            throw ClientException(ErrorCode.SUPPLIER_ERROR, cause = it)
        }

    private fun logAsyncFailure(
        key: String,
        throwable: Throwable,
    ) {
        val cause = if (throwable is CompletionException) throwable.cause ?: throwable else throwable
        log.error("Asynchronous cache task failed for key '{}'", key, cause)
    }
}
