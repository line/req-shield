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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

        fun executeAsyncTask() {
            CompletableFuture.runAsync({
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable({ callable.call() }, true, key, lockType),
                        timeToLiveMillis,
                    )
                setReqShieldData(
                    reqShieldConfig.setCacheFunction,
                    key,
                    reqShieldData,
                    lockType,
                )
            }, reqShieldConfig.executor)
        }

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE ||
            reqShieldConfig.keyLock.tryLock(key, lockType)
        ) {
            return executeAsyncTask()
        }
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE

        return if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE ||
            reqShieldConfig.keyLock.tryLock(key, lockType)
        ) {
            createReqShieldData(key, callable, timeToLiveMillis, lockType)
        } else {
            handleLockFailure(key, callable, timeToLiveMillis)
        }
    }

    private fun createReqShieldData(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
        lockType: LockType,
    ): ReqShieldData<T> {
        val reqShieldData =
            buildReqShieldData(
                executeCallable({ callable.call() }, true, key, lockType),
                timeToLiveMillis,
            )
        CompletableFuture.runAsync({
            setReqShieldData(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType)
        }, reqShieldConfig.executor)

        return reqShieldData
    }

    private fun handleLockFailure(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val future = createFuture()
        val counter = createCounter()

        scheduleTask(reqShieldConfig.executor, future, counter, reqShieldConfig.getCacheFunction, callable, key)

        val result = future.get()

        return buildReqShieldData(result, timeToLiveMillis)
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
        cacheSetter: (String, ReqShieldData<T>, Long) -> Boolean,
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
    ) {
        executeSetCacheFunction(cacheSetter, key, reqShieldData, lockType)
    }

    private fun createFuture(): CompletableFuture<T> = CompletableFuture()

    private fun createCounter(): AtomicInteger = AtomicInteger(0)

    private fun scheduleTask(
        executor: ScheduledExecutorService,
        future: CompletableFuture<T>,
        counter: AtomicInteger,
        cacheGetter: (String) -> ReqShieldData<T>?,
        callable: Callable<T?>,
        key: String,
    ) {
        val scheduled: ScheduledFuture<*> =
            executor.scheduleAtFixedRate({
                try {
                    // Early exit if future is already completed to avoid unnecessary work
                    if (future.isDone) {
                        return@scheduleAtFixedRate
                    }

                    val funcResult = executeGetCacheFunction(cacheGetter, key)
                    if (funcResult != null) {
                        // Use CAS-like complete to handle race condition safely
                        // If another thread already completed, this is a no-op
                        future.complete(funcResult.value)
                        return@scheduleAtFixedRate
                    }

                    // Increment first, then check - ensures atomic decision making
                    val attempts = counter.incrementAndGet()
                    if (attempts >= reqShieldConfig.maxAttemptGetCache && !future.isDone) {
                        // Use complete() which handles concurrent completion safely
                        // If another thread completed between our check and this call, it's ignored
                        future.complete(executeCallable({ callable.call() }, false))
                    }
                } catch (e: Exception) {
                    // Handle exception to prevent scheduleAtFixedRate from stopping
                    // Fallback to callable to ensure service availability
                    log.error("Error in scheduled cache getter for key '{}', falling back to callable", key, e)
                    if (!future.isDone) {
                        try {
                            future.complete(executeCallable({ callable.call() }, false))
                        } catch (fallbackException: Exception) {
                            log.error("Fallback callable also failed for key '{}'", key, fallbackException)
                            future.completeExceptionally(fallbackException)
                        }
                    }
                }
            }, GET_CACHE_INTERVAL_MILLIS, GET_CACHE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)

        future.whenComplete { _, _ -> scheduled.cancel(false) }
    }

    private fun executeGetCacheFunction(
        getFunction: (String) -> ReqShieldData<T>?,
        key: String,
    ): ReqShieldData<T>? =
        runCatching {
            getFunction.invoke(key)
        }.getOrElse {
            throw ClientException(ErrorCode.GET_CACHE_ERROR, originErrorMessage = it.message)
        }

    private fun executeSetCacheFunction(
        setFunction: (String, ReqShieldData<T>, Long) -> Boolean,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
    ) {
        try {
            setFunction.invoke(key, value, value.timeToLiveMillis)
        } catch (e: Exception) {
            throw ClientException(ErrorCode.SET_CACHE_ERROR, originErrorMessage = e.message)
        } finally {
            if (shouldAttemptUnlock(lockType)) {
                // No retry needed: false means lock already released or expired (not an error)
                val unlocked = reqShieldConfig.keyLock.unLock(key, lockType)
                if (!unlocked) {
                    log.debug("Lock already released or expired for key '{}'", key)
                }
            }
        }
    }

    private fun executeCallable(
        callable: Callable<T?>,
        isUnlockWhenException: Boolean,
        key: String? = null,
        lockType: LockType? = null,
    ): T? =
        runCatching {
            callable.call()
        }.getOrElse {
            if (isUnlockWhenException && key != null && lockType != null) {
                reqShieldConfig.keyLock.unLock(key, lockType)
            }
            throw ClientException(ErrorCode.SUPPLIER_ERROR, originErrorMessage = it.message)
        }

    private fun shouldAttemptUnlock(lockType: LockType): Boolean =
        (lockType == LockType.UPDATE && reqShieldConfig.reqShieldWorkMode != ReqShieldWorkMode.ONLY_CREATE_CACHE) ||
            (lockType == LockType.CREATE && reqShieldConfig.reqShieldWorkMode != ReqShieldWorkMode.ONLY_UPDATE_CACHE)
}
