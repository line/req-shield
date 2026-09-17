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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger(ReqShield::class.java)

/**
 * Internal signal telling the lock waiter to stop polling the cache because the cache
 * itself looks unavailable. Never leaves [ReqShield]: it is always resumed into the
 * supplier fallback. Stack trace is disabled because it carries no diagnostic value.
 */
private class GetCacheUnavailableException : RuntimeException(null, null, false, false)

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
                Mono.justOrEmpty(reqShieldData)
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

        fun processMono(token: String?): Mono<ReqShieldData<T>> =
            executeCallable({ callable.call() }, key, lockType, token)
                .map { data -> buildReqShieldData(data, timeToLiveMillis) }
                .flatMap { reqShieldData ->
                    setReqShieldData(
                        reqShieldConfig.setCacheFunction,
                        key,
                        reqShieldData,
                        lockType,
                        token,
                    ).thenReturn(reqShieldData)
                }.switchIfEmpty(
                    Mono.defer {
                        val reqShieldData = buildReqShieldData(null, timeToLiveMillis)
                        setReqShieldData(
                            reqShieldConfig.setCacheFunction,
                            key,
                            reqShieldData,
                            lockType,
                            token,
                        ).thenReturn(reqShieldData)
                    },
                )

        val updateMono =
            if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE) {
                // This mode never refreshes an existing entry through the lock, so no token is taken.
                processMono(null)
            } else {
                // Empty means another request already holds the update lock: nothing to do here.
                reqShieldConfig.keyLock
                    .tryLock(key, lockType)
                    .flatMap { token -> processMono(token) }
            }

        updateMono
            .subscribeOn(reqShieldConfig.scheduler)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to update cache for key '{}': {}", key, e.message, e) },
            )
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> {
        val lockType = LockType.CREATE

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE) {
            // This mode never creates a cache entry through the lock, so no token is taken.
            return createReqShieldData(key, callable, timeToLiveMillis, lockType, null)
        }

        return reqShieldConfig.keyLock
            .tryLock(key, lockType)
            .flatMap { token ->
                val cacheCreationStarted = AtomicBoolean(false)
                // Another request may have filled the cache between our initial miss and lock acquisition.
                Mono.defer { executeGetCacheFunction(reqShieldConfig.getCacheFunction, key) }
                    // A global lock can emit on its client's event loop; keep cache reads off that thread.
                    .subscribeOn(reqShieldConfig.scheduler)
                    .flatMap { Mono.justOrEmpty(it) }
                    .switchIfEmpty(
                        Mono.defer {
                            val creation = createReqShieldData(key, callable, timeToLiveMillis, lockType, token)
                            // The existing creation path releases the lock after its asynchronous cache write.
                            cacheCreationStarted.set(true)
                            creation
                        },
                    ).doFinally {
                        // A cache hit, read failure, or cancellation during the recheck must release our token.
                        if (!cacheCreationStarted.get()) {
                            releaseLock(key, lockType, token)
                        }
                    }
            }
            .switchIfEmpty(
                Mono.defer {
                    handleLockFailure(key, callable, timeToLiveMillis)
                },
            )
    }

    private fun createReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        lockType: LockType,
        token: String?,
    ): Mono<ReqShieldData<T>> =
        executeCallable({ callable.call() }, key, lockType, token)
            .map { data -> buildReqShieldData(data, timeToLiveMillis) }
            .doOnNext { reqShieldData ->
                // Async fire-and-forget cache storage (matches coroutine implementation)
                setReqShieldData(
                    reqShieldConfig.setCacheFunction,
                    key,
                    reqShieldData,
                    lockType,
                    token,
                ).subscribeOn(reqShieldConfig.scheduler)
                    .subscribe(
                        { /* success - no action needed */ },
                        { e -> log.error("Failed to set cache for key '{}': {}", key, e.message, e) },
                    )
            }.switchIfEmpty(
                Mono.defer {
                    val reqShieldData = buildReqShieldData(null, timeToLiveMillis)
                    // Async fire-and-forget cache storage (matches coroutine implementation)
                    setReqShieldData(
                        reqShieldConfig.setCacheFunction,
                        key,
                        reqShieldData,
                        lockType,
                        token,
                    ).subscribeOn(reqShieldConfig.scheduler)
                        .subscribe(
                            { /* success - no action needed */ },
                            { e -> log.error("Failed to set cache for key '{}': {}", key, e.message, e) },
                        )
                    Mono.just(reqShieldData)
                },
            )

    /**
     * Waits for the request that owns the lock to fill the cache.
     *
     * The cache is polled up to `maxAttemptGetCache` times. A read failure is logged and counted;
     * [MAX_CONSECUTIVE_GET_CACHE_FAILURES] consecutive failures are treated as a cache outage and
     * stop the polling immediately. Once polling gives up, the supplier is called directly, and a
     * failing supplier surfaces as `ClientException(SUPPLIER_ERROR)` instead of a null-valued entry.
     */
    private fun handleLockFailure(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> {
        val consecutiveGetCacheFailures = AtomicInteger(0)

        return Mono
            .defer { getCacheWhileWaitingForLock(key, consecutiveGetCacheFailures) }
            .repeatWhenEmpty { companion ->
                companion
                    .take(reqShieldConfig.maxAttemptGetCache.toLong())
                    .delayElements(Duration.ofMillis(GET_CACHE_INTERVAL_MILLIS))
            }.onErrorResume(GetCacheUnavailableException::class.java) { Mono.empty() }
            .switchIfEmpty(
                Mono.defer {
                    executeCallable({ callable.call() }, key, null, null)
                        .map { data -> buildReqShieldData(data, timeToLiveMillis) }
                        .switchIfEmpty(Mono.fromSupplier { buildReqShieldData(null, timeToLiveMillis) })
                },
            ).subscribeOn(reqShieldConfig.scheduler)
    }

    /**
     * Reads the cache once while waiting for the lock holder.
     *
     * Completes empty while the cache is not filled yet, which drives the retry loop.
     * A transient read failure also completes empty so the loop continues, but
     * [MAX_CONSECUTIVE_GET_CACHE_FAILURES] consecutive failures raise [GetCacheUnavailableException]
     * to bail out of the loop. Any successful read resets the failure counter.
     */
    private fun getCacheWhileWaitingForLock(
        key: String,
        consecutiveGetCacheFailures: AtomicInteger,
    ): Mono<ReqShieldData<T>> =
        executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
            .doOnSuccess { consecutiveGetCacheFailures.set(0) }
            .flatMap { reqShieldData -> Mono.justOrEmpty(reqShieldData) }
            .onErrorResume { e ->
                val failures = consecutiveGetCacheFailures.incrementAndGet()
                log.warn(
                    "Failed to read cache for key '{}' while waiting for the lock holder (consecutive failures: {}): {}",
                    key,
                    failures,
                    e.message,
                )
                if (failures >= MAX_CONSECUTIVE_GET_CACHE_FAILURES) {
                    Mono.error(GetCacheUnavailableException())
                } else {
                    Mono.empty()
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

    private fun setReqShieldData(
        cacheSetter: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
        key: String,
        reqShieldData: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
    ): Mono<Boolean> = executeSetCacheFunction(cacheSetter, key, reqShieldData, lockType, token)

    private fun executeGetCacheFunction(
        getFunction: (String) -> Mono<ReqShieldData<T>?>,
        key: String,
    ): Mono<ReqShieldData<T>?> =
        // Deferred so a client function that throws synchronously fails as an onError signal.
        Mono
            .defer { getFunction(key) }
            .onErrorMap { e -> ClientException(ErrorCode.GET_CACHE_ERROR, cause = e) }

    private fun executeSetCacheFunction(
        setFunction: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
        key: String,
        value: ReqShieldData<T>,
        lockType: LockType,
        token: String?,
    ): Mono<Boolean> =
        // Deferred so a client function that throws synchronously fails as an onError signal,
        // which keeps the lock release in doFinally reachable.
        Mono
            .defer { setFunction(key, value, value.timeToLiveMillis) }
            .onErrorMap { e -> ClientException(ErrorCode.SET_CACHE_ERROR, cause = e) }
            .doFinally {
                // Only the holder of a token took a lock, so only it may release one.
                if (token != null) {
                    releaseLock(key, lockType, token)
                }
            }.subscribeOn(reqShieldConfig.scheduler)

    private fun releaseLock(
        key: String,
        lockType: LockType,
        token: String,
    ) {
        // No retry needed: false means lock already released or expired (not an error).
        Mono.defer { reqShieldConfig.keyLock.unLock(key, lockType, token) }
            .doOnNext { unlocked ->
                if (!unlocked) {
                    log.debug("Lock already released or expired for key '{}'", key)
                }
            }.subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to unlock key '{}': {}", key, e.message, e) },
            )
    }

    private fun executeCallable(
        callable: Callable<Mono<T?>>,
        key: String,
        lockType: LockType?,
        token: String?,
    ): Mono<T?> =
        // Deferred so a supplier that throws synchronously fails as an onError signal,
        // which keeps the lock release below reachable.
        Mono
            .defer { callable.call() }
            .doOnError { _ ->
                // Only the holder of a token took a lock, so only it may release one.
                if (lockType != null && token != null) {
                    reqShieldConfig.keyLock
                        .unLock(key, lockType, token)
                        .subscribe(
                            { /* success - no action needed */ },
                            { unlockError ->
                                log.error(
                                    "Failed to unlock key '{}' after callable error: {}",
                                    key,
                                    unlockError.message,
                                    unlockError,
                                )
                            },
                        )
                }
            }.onErrorMap { e ->
                ClientException(ErrorCode.SUPPLIER_ERROR, cause = e)
            }
}
