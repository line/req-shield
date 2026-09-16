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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ReqShield::class.java)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun updateReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
    ) {
        val lockType = LockType.UPDATE
        // ONLY_CREATE_CACHE collapses requests on creation only, so the update runs without a lock.
        val onlyCreateCache = reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE
        val token = if (onlyCreateCache) null else reqShieldConfig.keyLock.tryLock(key, lockType)

        if (!onlyCreateCache && token == null) return

        reqShieldConfig.scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val reqShieldData =
                    buildReqShieldData(
                        executeCallable(callable, key, lockType, token),
                        timeToLiveMillis,
                    )
                executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
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
        val token = if (onlyUpdateCache) null else reqShieldConfig.keyLock.tryLock(key, lockType)

        return if (onlyUpdateCache || token != null) {
            createReqShieldData(key, callable, timeToLiveMillis, lockType, token)
        } else {
            handleLockFailure(key, callable, timeToLiveMillis)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun createReqShieldData(
        key: String,
        callable: suspend () -> T?,
        timeToLiveMillis: Long,
        lockType: LockType,
        token: String?,
    ): ReqShieldData<T> {
        val reqShieldData =
            buildReqShieldData(
                executeCallable(callable, key, lockType, token),
                timeToLiveMillis,
            )
        reqShieldConfig.scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                executeSetCacheFunction(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("[Req-Shield] failed to create the cache of key '{}'", key, e)
            }
        }
        return reqShieldData
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ClientException(ErrorCode.SET_CACHE_ERROR, cause = e)
        } finally {
            // Only the holder of a token has a lock to release.
            if (token != null) {
                withContext(NonCancellable) {
                    reqShieldConfig.keyLock.unLock(key, lockType, token)
                }
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
                withContext(NonCancellable) {
                    reqShieldConfig.keyLock.unLock(key, lockType, token)
                }
            }
            if (e is CancellationException) throw e
            throw ClientException(ErrorCode.SUPPLIER_ERROR, cause = e)
        }
}
