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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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
            CompletableFuture.runAsync({
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable(callable, key, lockType, token),
                        timeToLiveMillis,
                    )
                executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
            }, reqShieldConfig.executor)
                .whenComplete { _, e -> if (e != null) logAsyncFailure(key, e) }
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
        CompletableFuture.runAsync({
            executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
        }, reqShieldConfig.executor)
            .whenComplete { _, e -> if (e != null) logAsyncFailure(key, e) }

        return reqShieldData
    }

    /**
     * Another request holds the lock: poll the cache until that request publishes its result.
     *
     * The supplier is never called from the polling task - it is called on this thread only after
     * the wait gave up, so at most one extra supplier call per waiting request happens.
     */
    private fun handleLockFailure(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val future = CompletableFuture<ReqShieldData<T>?>()
        val scheduled = scheduleTask(reqShieldConfig.executor, future, reqShieldConfig.getCacheFunction, key)

        // The polling task gives up on its own; this timeout only guards against a task that never runs
        val waitTimeoutMillis =
            reqShieldConfig.maxAttemptGetCache * GET_CACHE_INTERVAL_MILLIS + GET_CACHE_INTERVAL_MILLIS * 10

        val cachedData =
            try {
                future.get(waitTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                log.warn("Timed out waiting for the cache to be created for key '{}', falling back to the supplier", key)
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ClientException(ErrorCode.GET_CACHE_ERROR, cause = e)
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is ClientException) cause else ClientException(ErrorCode.GET_CACHE_ERROR, cause = cause)
            } finally {
                scheduled.cancel(false)
            }

        // No lock was acquired by this request, so there is nothing to release on failure
        return cachedData ?: buildReqShieldData(executeCallable(callable, key, null, null), timeToLiveMillis)
    }

    private fun buildReqShieldData(
        value: T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> =
        ReqShieldData(
            value = value,
            timeToLiveMillis = timeToLiveMillis,
        )

    /**
     * Polls the cache on a fixed delay and completes [future] with the cached data once it appears.
     *
     * The future is completed with null to signal "stop waiting, fall back to the supplier", which
     * happens when [ReqShieldConfiguration.maxAttemptGetCache] successful-but-empty reads were made
     * or when [MAX_CONSECUTIVE_GET_CACHE_FAILURES] reads failed in a row (the cache looks unavailable).
     */
    private fun scheduleTask(
        executor: ScheduledExecutorService,
        future: CompletableFuture<ReqShieldData<T>?>,
        cacheGetter: (String) -> ReqShieldData<T>?,
        key: String,
    ): ScheduledFuture<*> {
        val attemptCount = AtomicInteger(0)
        val consecutiveFailureCount = AtomicInteger(0)

        val scheduled: ScheduledFuture<*> =
            executor.scheduleWithFixedDelay({
                // Early exit if future is already completed to avoid unnecessary work
                if (future.isDone) {
                    return@scheduleWithFixedDelay
                }

                try {
                    val cachedData = cacheGetter.invoke(key)
                    if (cachedData != null) {
                        // complete() is a no-op when another thread already completed the future
                        future.complete(cachedData)
                        return@scheduleWithFixedDelay
                    }

                    consecutiveFailureCount.set(0)
                    if (attemptCount.incrementAndGet() >= reqShieldConfig.maxAttemptGetCache) {
                        future.complete(null)
                    }
                } catch (e: Exception) {
                    log.warn("Cache read failed while waiting for the cache to be created for key '{}'", key, e)
                    if (consecutiveFailureCount.incrementAndGet() >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) {
                        future.complete(null)
                    }
                }
            }, GET_CACHE_INTERVAL_MILLIS, GET_CACHE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)

        future.whenComplete { _, _ -> scheduled.cancel(false) }

        return scheduled
    }

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
