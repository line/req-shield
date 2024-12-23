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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_ATTEMPT_SET_CACHE
import com.linecorp.cse.reqshield.support.constant.ConfigValues.SET_CACHE_RETRY_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReqShield<T>(
    private val reqShieldConfiguration: ReqShieldConfiguration<T>,
) {
    fun getAndSetReqShieldData(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val currentReqShieldData = executeGetCacheFunction(reqShieldConfiguration.getCacheFunction, key)
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
        decideToUpdateCache(reqShieldData.createdAt, reqShieldData.timeToLiveMillis, reqShieldConfiguration.decisionForUpdate)

    private fun updateReqShieldData(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ) {
        val lockType = LockType.UPDATE
        if (reqShieldConfiguration.keyLock.tryLock(key, lockType)) {
            CompletableFuture.runAsync({
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable({ callable.call() }, true, key, lockType),
                        timeToLiveMillis,
                    )
                setReqShieldData(
                    reqShieldConfiguration.setCacheFunction,
                    key,
                    reqShieldData,
                    LockType.UPDATE,
                )
            }, reqShieldConfiguration.executor)
        }
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE
        return if (reqShieldConfiguration.keyLock.tryLock(key, lockType)) {
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
            setReqShieldData(reqShieldConfiguration.setCacheFunction, key, reqShieldData, lockType)
        }, reqShieldConfiguration.executor)

        return reqShieldData
    }

    private fun handleLockFailure(
        key: String,
        callable: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val future = createFuture()
        val counter = createCounter()

        scheduleTask(reqShieldConfiguration.executor, future, counter, reqShieldConfiguration.getCacheFunction, callable, key)

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
        fun schedule(): ScheduledFuture<*> =
            executor.schedule({
                if (!future.isDone) {
                    val funcResult = executeGetCacheFunction(cacheGetter, key)
                    if (funcResult != null) {
                        future.complete(funcResult.value)
                    } else if (counter.incrementAndGet() >= reqShieldConfiguration.maxAttemptGetCache) {
                        future.complete(
                            executeCallable({ callable.call() }, false),
                        )
                    }
                    if (!future.isDone) {
                        schedule() // Schedule the next execution
                    }
                }
            }, GET_CACHE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)

        val scheduleFuture = schedule()

        future.whenComplete { _, _ -> scheduleFuture.cancel(false) }
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
        var unlockSuccess = false
        var retryCount = 0

        try {
            setFunction.invoke(key, value, value.timeToLiveMillis)
        } catch (e: Exception) {
            throw ClientException(ErrorCode.SET_CACHE_ERROR, originErrorMessage = e.message)
        } finally {
            while (!unlockSuccess && retryCount < MAX_ATTEMPT_SET_CACHE) {
                if (reqShieldConfiguration.keyLock.unLock(key, lockType)) {
                    unlockSuccess = true
                } else {
                    retryCount++
                    Thread.sleep(SET_CACHE_RETRY_INTERVAL_MILLIS)
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
                reqShieldConfiguration.keyLock.unLock(key, lockType)
            }
            throw ClientException(ErrorCode.SUPPLIER_ERROR, originErrorMessage = it.message)
        }
}
