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

package com.linecorp.cse.reqshield.reactor

import com.linecorp.cse.reqshield.reactor.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.reactor.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_ATTEMPT_SET_CACHE
import com.linecorp.cse.reqshield.support.constant.ConfigValues.SET_CACHE_RETRY_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.Callable

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    fun getAndSetReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> {
        val currentReqShieldData = executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)

        return currentReqShieldData
            .flatMap { reqShieldData ->
                if (shouldUpdateCache(reqShieldData)) {
                    updateReqShieldData(key, callable, timeToLiveMillis)
                }
                Mono.justOrEmpty(reqShieldData!!)
            }.switchIfEmpty(
                Mono.defer {
                    handleLockForCacheCreation(key, callable, timeToLiveMillis)
                },
            )
    }

    private fun shouldUpdateCache(reqShieldData: ReqShieldData<T>?): Boolean =
        reqShieldData != null &&
            decideToUpdateCache(
                reqShieldData.createdAt,
                reqShieldData.timeToLiveMillis,
                reqShieldConfig.decisionForUpdate,
            )

    private fun updateReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ) {
        val lockType = LockType.UPDATE

        fun processMono(): Mono<ReqShieldData<T>> =
            executeCallable({ callable.call() }, true, key, lockType)
                .map { data -> buildReqShieldData(data, timeToLiveMillis) }
                .doOnNext { reqShieldData ->
                    setReqShieldData(
                        reqShieldConfig.setCacheFunction,
                        key,
                        reqShieldData,
                        lockType,
                    )
                }.switchIfEmpty(
                    Mono.defer {
                        val reqShieldData = buildReqShieldData(null, timeToLiveMillis)
                        setReqShieldData(
                            reqShieldConfig.setCacheFunction,
                            key,
                            reqShieldData,
                            lockType,
                        )
                        Mono.just(reqShieldData)
                    },
                )

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE) {
            processMono()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe()
        } else {
            reqShieldConfig.keyLock
                .tryLock(key, lockType)
                .filter { it }
                .flatMap { processMono() }
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe()
        }
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> {
        val lockType = LockType.CREATE

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE) {
            return createReqShieldData(key, callable, timeToLiveMillis, lockType)
        }

        return reqShieldConfig.keyLock
            .tryLock(key, lockType)
            .flatMap { acquired ->
                if (acquired) {
                    createReqShieldData(key, callable, timeToLiveMillis, lockType)
                } else {
                    handleLockFailure(key, callable, timeToLiveMillis)
                }
            }
    }

    private fun createReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        lockType: LockType,
    ): Mono<ReqShieldData<T>> =
        executeCallable({ callable.call() }, true, key, lockType)
            .map { data -> buildReqShieldData(data, timeToLiveMillis) }
            .flatMap { reqShieldData ->

                setReqShieldData(
                    reqShieldConfig.setCacheFunction,
                    key,
                    reqShieldData,
                    lockType,
                )

                Mono.just(reqShieldData)
            }.switchIfEmpty(
                Mono.defer {
                    val reqShieldData = buildReqShieldData(null, timeToLiveMillis)

                    setReqShieldData(
                        reqShieldConfig.setCacheFunction,
                        key,
                        reqShieldData,
                        lockType,
                    )

                    Mono.just(reqShieldData)
                },
            )

    private fun handleLockFailure(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> =
        reqShieldConfig
            .getCacheFunction(key)
            .repeatWhenEmpty {
                Flux
                    .range(1, reqShieldConfig.maxAttemptGetCache)
                    .delayElements(Duration.ofMillis(GET_CACHE_INTERVAL_MILLIS))
            }.flatMap { reqShieldData ->
                if (reqShieldData != null) {
                    Mono.just(reqShieldData)
                } else {
                    Mono.empty()
                }
            }.switchIfEmpty(
                executeCallable({ callable.call() }, false)
                    .map {
                        buildReqShieldData(it, timeToLiveMillis)
                    }.onErrorResume {
                        Mono.just(buildReqShieldData(null, timeToLiveMillis))
                    }.switchIfEmpty(
                        Mono.defer {
                            val reqShieldData = buildReqShieldData(null, timeToLiveMillis)
                            Mono.just(reqShieldData)
                        },
                    ),
            ).subscribeOn(Schedulers.boundedElastic())

    private fun buildReqShieldData(
        value: T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> =
        ReqShieldData(
            value = value,
            timeToLiveMillis = timeToLiveMillis,
        )

    private fun setReqShieldData(
        cacheSetter: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
    ) {
        executeSetCacheFunction(cacheSetter, key, reqShieldData, lockType).subscribe()
    }

    private fun executeGetCacheFunction(
        getFunction: (String) -> Mono<ReqShieldData<T>?>,
        key: String,
    ): Mono<ReqShieldData<T>?> =
        getFunction(key)
            .doOnError { e ->
                throw ClientException(ErrorCode.GET_CACHE_ERROR, originErrorMessage = e.message)
            }

    private fun executeSetCacheFunction(
        setFunction: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
    ): Mono<Boolean> =
        setFunction(key, value, value.timeToLiveMillis)
            .doOnError { e ->
                throw ClientException(ErrorCode.SET_CACHE_ERROR, originErrorMessage = e.message)
            }.doFinally {
                if (shouldAttemptUnlock(lockType)) {
                    reqShieldConfig.keyLock
                        .unLock(key, lockType)
                        .retryWhen(
                            Retry.fixedDelay(
                                MAX_ATTEMPT_SET_CACHE - 1L,
                                Duration.ofMillis(SET_CACHE_RETRY_INTERVAL_MILLIS),
                            ),
                        ).subscribe()
                }
            }.subscribeOn(Schedulers.boundedElastic())

    private fun executeCallable(
        callable: Callable<Mono<T?>>,
        isUnlockWhenException: Boolean,
        key: String? = null,
        lockType: LockType? = null,
    ): Mono<T?> =
        callable
            .call()
            .doOnError { e ->
                if (isUnlockWhenException && key != null && lockType != null) {
                    reqShieldConfig.keyLock.unLock(key, lockType).subscribe()
                }
                throw ClientException(ErrorCode.SUPPLIER_ERROR, originErrorMessage = e.message)
            }

    private fun shouldAttemptUnlock(lockType: LockType): Boolean =
        (lockType == LockType.UPDATE && reqShieldConfig.reqShieldWorkMode != ReqShieldWorkMode.ONLY_CREATE_CACHE) ||
            (lockType == LockType.CREATE && reqShieldConfig.reqShieldWorkMode != ReqShieldWorkMode.ONLY_UPDATE_CACHE)
}
