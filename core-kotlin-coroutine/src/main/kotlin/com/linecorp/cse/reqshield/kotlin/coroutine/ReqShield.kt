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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_ATTEMPT_SET_CACHE
import com.linecorp.cse.reqshield.support.constant.ConfigValues.SET_CACHE_RETRY_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    suspend fun getAndSetReqShieldData(
        key: String,
        callable: suspend () -> T?,
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

    private suspend fun updateReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ) {
        val lockType = LockType.UPDATE
        if (reqShieldConfig.keyLock.tryLock(key, lockType)) {
            CoroutineScope(Dispatchers.IO).launch {
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable({ callable() }, true, key, lockType),
                        timeToLiveMillis,
                    )
                setReqShieldData(
                    reqShieldConfig.setCacheFunction,
                    key,
                    reqShieldData,
                    lockType,
                )
            }
        }
    }

    private suspend fun handleLockForCacheCreation(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val lockType = LockType.CREATE
        return if (reqShieldConfig.keyLock.tryLock(key, lockType)) {
            createReqShieldData(key, callable, timeToLiveMillis, lockType)
        } else {
            handleLockFailure(key, callable, timeToLiveMillis)
        }
    }

    private suspend fun createReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
        lockType: LockType,
    ): ReqShieldData<T> {
        val reqShieldData =
            buildReqShieldData(
                executeCallable({ callable() }, true, key, lockType),
                timeToLiveMillis,
            )
        CoroutineScope(Dispatchers.IO).launch {
            setReqShieldData(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType)
        }
        return reqShieldData
    }

    private suspend fun handleLockFailure(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        val counter = createCounter()

        val result = scheduleTask(counter, reqShieldConfig.getCacheFunction, callable, key).await()

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

    private suspend fun setReqShieldData(
        cacheSetter: suspend (String, ReqShieldData<T>, Long) -> Boolean,
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
    ) {
        executeSetCacheFunction(cacheSetter, key, reqShieldData, lockType)
    }

    private fun createCounter(): AtomicInteger = AtomicInteger(0)

    private fun scheduleTask(
        counter: AtomicInteger,
        cacheGetter: suspend (String) -> ReqShieldData<T>?,
        callable: suspend () -> T?,
        key: String,
    ): Deferred<T?> =
        CoroutineScope(Dispatchers.IO).async {
            while (counter.incrementAndGet() <= reqShieldConfig.maxAttemptGetCache) {
                executeGetCacheFunction(cacheGetter, key)?.let {
                    return@async it.value
                }
                delay(GET_CACHE_INTERVAL_MILLIS)
            }

            return@async executeCallable({ callable() }, false)
        }

    private suspend fun executeGetCacheFunction(
        getFunction: suspend (String) -> ReqShieldData<T>?,
        key: String,
    ): ReqShieldData<T>? =
        runCatching {
            getFunction(key)
        }.getOrElse {
            throw ClientException(ErrorCode.GET_CACHE_ERROR, originErrorMessage = it.message)
        }

    private suspend fun executeSetCacheFunction(
        setFunction: suspend (String, ReqShieldData<T>, Long) -> Boolean,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
    ) {
        try {
            setFunction(key, value, value.timeToLiveMillis)
        } catch (e: Exception) {
            throw ClientException(ErrorCode.SET_CACHE_ERROR, originErrorMessage = e.message)
        } finally {
            unlockWithRetry(key, lockType)
        }
    }

    private suspend fun unlockWithRetry(
        key: String,
        lockType: LockType,
    ) {
        repeat(MAX_ATTEMPT_SET_CACHE) { attempt ->
            if (reqShieldConfig.keyLock.unLock(key, lockType)) {
                return
            } else if (attempt < MAX_ATTEMPT_SET_CACHE - 1) {
                delay(SET_CACHE_RETRY_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun executeCallable(
        callable: suspend () -> T?,
        isUnlockWhenException: Boolean,
        key: String? = null,
        lockType: LockType? = null,
    ): T? =
        runCatching {
            callable()
        }.getOrElse {
            if (isUnlockWhenException && key != null && lockType != null) {
                reqShieldConfig.keyLock.unLock(key, lockType)
            }
            throw ClientException(ErrorCode.SUPPLIER_ERROR, originErrorMessage = it.message)
        }
}
