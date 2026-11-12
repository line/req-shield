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
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(ReqShield::class.java)

/**
 * Internal signal telling the lock waiter to stop polling the cache because the cache
 * itself looks unavailable. Never leaves [ReqShield]: it is always resumed into the
 * supplier fallback. Stack trace is disabled because it carries no diagnostic value.
 */
private class GetCacheUnavailableException : RuntimeException(null, null, false, false)

/**
 * The owner of an acquired creation lock. The caller can cancel on another thread while a signal is still on its way,
 * so the recheck, the creation and the cancel hooks each take the lock over with compareAndSet: exactly one of them
 * releases it or hands it to the cache write.
 */
private enum class LockOwner { RECHECK, CREATION, CACHE_WRITE, RELEASED }

class ReqShield<T>(
    private val reqShieldConfig: ReqShieldConfiguration<T>,
) {
    /**
     * Guards the refresh of an entry in [ReqShieldWorkMode.ONLY_CREATE_CACHE]. That mode does not call the configured
     * lock for a refresh, so without a guard each request for an old entry would subscribe its own refresh. This lock
     * lives in this JVM only and expires like any other local lock.
     */
    private val localRefreshLock: KeyLock by lazy { KeyLocalLock(reqShieldConfig.lockTimeoutMillis) }

    private val lockTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(reqShieldConfig.lockTimeoutMillis)

    fun getAndSetReqShieldData(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> =
        Mono.deferContextual { callerContext ->
            executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
                .flatMap { reqShieldData ->
                    if (reqShieldData != null && shouldUpdateCache(reqShieldData)) {
                        updateReqShieldData(key, callable, timeToLiveMillis, reqShieldData.createdAt, callerContext)
                    }
                    Mono.justOrEmpty(reqShieldData)
                }.switchIfEmpty(
                    Mono.defer {
                        handleLockForCacheCreation(key, callable, timeToLiveMillis, callerContext)
                    },
                )
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
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        // createdAt of the entry this request read, which tells that entry apart from one written during the refresh
        seenCreatedAt: Long,
        // The refresh is subscribed on its own, so it sees the caller's Reactor Context only when it is handed over.
        callerContext: ContextView,
    ) {
        val lockType = LockType.UPDATE
        val lockedAtNanos = System.nanoTime()

        fun write(
            reqShieldData: ReqShieldData<T>,
            token: String,
        ): Mono<ReqShieldData<T>> =
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, seenCreatedAt, callerContext)
                .thenReturn(reqShieldData)

        fun processMono(token: String): Mono<ReqShieldData<T>> =
            executeCallable(callable)
                .doOnError {
                    // A failed supplier never reaches the cache write, which would release the lock.
                    releaseLock(key, lockType, token, callerContext)
                }.map { data -> buildReqShieldData(data, timeToLiveMillis) }
                .flatMap { reqShieldData -> write(reqShieldData, token) }
                .switchIfEmpty(Mono.defer { write(buildReqShieldData(null, timeToLiveMillis), token) })

        // Empty means another request already holds the update lock: nothing to do here.
        // Deferred so a lock call that throws fails this refresh only, not the caller's cache hit.
        val updateMono =
            Mono
                .defer { lockFor(lockType).tryLock(key, lockType) }
                .flatMap { token -> processMono(token) }

        updateMono
            .subscribeOn(reqShieldConfig.scheduler)
            .contextWrite(callerContext)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to update cache for key '{}': {}", key, e.message, e) },
            )
    }

    private fun handleLockForCacheCreation(
        key: String,
        callable: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> {
        val lockType = LockType.CREATE
        val lockedAtNanos = System.nanoTime()

        if (reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_UPDATE_CACHE) {
            // This mode never creates a cache entry through the lock, so no token is taken.
            return createReqShieldData(key, callable, timeToLiveMillis, lockType, null, null, lockedAtNanos, callerContext)
        }

        return reqShieldConfig.keyLock
            .tryLock(key, lockType)
            .flatMap { token ->
                val owner = AtomicReference(LockOwner.RECHECK)
                // Another request may have filled the cache between our initial miss and lock acquisition.
                Mono.defer { executeGetCacheFunction(reqShieldConfig.getCacheFunction, key) }
                    // A global lock can emit on its client's event loop; keep cache reads off that thread.
                    .subscribeOn(reqShieldConfig.scheduler)
                    .flatMap { Mono.justOrEmpty(it) }
                    .switchIfEmpty(
                        Mono.defer {
                            // A cancel that already released the lock leaves nothing to create.
                            if (owner.compareAndSet(LockOwner.RECHECK, LockOwner.CREATION)) {
                                createReqShieldData(key, callable, timeToLiveMillis, lockType, token, owner, lockedAtNanos, callerContext)
                            } else {
                                Mono.empty()
                            }
                        },
                    ).doFinally {
                        // A cache hit, read failure, or cancellation during the recheck must release our token.
                        // Once creation owns it, createReqShieldData releases it (error, cancel or after the write).
                        if (owner.compareAndSet(LockOwner.RECHECK, LockOwner.RELEASED)) {
                            releaseLock(key, lockType, token, callerContext)
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
        // The owner of [token] (null without a lock): the cache write, the error and the cancel take it over once.
        owner: AtomicReference<LockOwner>?,
        lockedAtNanos: Long,
        callerContext: ContextView,
    ): Mono<ReqShieldData<T>> {
        // Hands the lock to the asynchronous cache write, which releases it after writing. False when a cancel
        // already released it: the write is then skipped, because it would run without the lock.
        fun handToCacheWrite(): Boolean = owner == null || owner.compareAndSet(LockOwner.CREATION, LockOwner.CACHE_WRITE)

        fun releaseUnlessHandedOver() {
            if (token != null && owner != null && owner.compareAndSet(LockOwner.CREATION, LockOwner.RELEASED)) {
                releaseLock(key, lockType, token, callerContext)
            }
        }

        fun writeCache(reqShieldData: ReqShieldData<T>) {
            // Async fire-and-forget cache storage (matches coroutine implementation)
            writeUnlessSuperseded(key, reqShieldData, lockType, token, lockedAtNanos, null, callerContext)
                // Subscribed on its own, so the cache function sees the caller's Reactor Context only when it is handed over.
                .contextWrite(callerContext)
                .subscribe(
                    { /* success - no action needed */ },
                    { e -> log.error("Failed to set cache for key '{}': {}", key, e.message, e) },
                )
        }

        return executeCallable(callable)
            .doOnError { releaseUnlessHandedOver() }
            .map { data -> buildReqShieldData(data, timeToLiveMillis) }
            .switchIfEmpty(Mono.fromSupplier { buildReqShieldData(null, timeToLiveMillis) })
            .doOnNext { reqShieldData ->
                if (handToCacheWrite()) {
                    writeCache(reqShieldData)
                }
            }.doOnCancel {
                // A caller cancelled while the supplier runs (client disconnect, timeout()) never reaches
                // the cache write, so the lock is released here instead of lingering until it expires.
                releaseUnlessHandedOver()
            }
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
        callerContext: ContextView,
    ): Mono<Boolean> =
        Mono.defer {
            if (token == null || System.nanoTime() - lockedAtNanos < lockTimeoutNanos) {
                return@defer setReqShieldData(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token, callerContext)
            }

            isSuperseded(key, seenCreatedAt).flatMap { superseded ->
                if (superseded) {
                    log.debug("Skipped the cache write for key '{}': its lock timed out and a newer entry exists", key)
                    releaseLock(key, lockType, token, callerContext)
                    Mono.just(false)
                } else {
                    setReqShieldData(reqShieldConfig.setCacheFunction, key, reqShieldData, lockType, token, callerContext)
                }
            }
        }

    private fun isSuperseded(
        key: String,
        seenCreatedAt: Long?,
    ): Mono<Boolean> =
        executeGetCacheFunction(reqShieldConfig.getCacheFunction, key)
            // The supplier can emit on its client's event loop; keep cache reads off that thread.
            .subscribeOn(reqShieldConfig.scheduler)
            .flatMap { Mono.justOrEmpty(it) }
            .map { cachedData -> cachedData.createdAt != seenCreatedAt }
            .defaultIfEmpty(false)
            .onErrorResume { e ->
                // Without the read nothing shows a newer entry, so the write goes on as it did before
                log.warn("Failed to read cache for key '{}' before its late cache write: {}", key, e.message)
                Mono.just(false)
            }

    /**
     * Waits for the request that owns the lock to fill the cache.
     *
     * The cache is polled up to `maxAttemptGetCache` times, the first time at once. A read failure is logged and counted;
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
        val maxAttempts = reqShieldConfig.maxAttemptGetCache.toLong()
        val polls =
            if (maxAttempts <= 0) {
                Mono.empty()
            } else {
                Mono
                    .defer { getCacheWhileWaitingForLock(key, consecutiveGetCacheFailures) }
                    // The companion emits the index of each empty poll; maxAttempts - 1 repeats make maxAttempts polls.
                    .repeatWhenEmpty { companion ->
                        companion
                            .takeWhile { index -> index < maxAttempts - 1 }
                            .delayElements(Duration.ofMillis(GET_CACHE_INTERVAL_MILLIS))
                    }
            }

        return polls
            .onErrorResume(GetCacheUnavailableException::class.java) { Mono.empty() }
            .switchIfEmpty(
                Mono.defer {
                    executeCallable(callable)
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
        callerContext: ContextView,
    ): Mono<Boolean> = executeSetCacheFunction(cacheSetter, key, reqShieldData, lockType, token, callerContext)

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
        callerContext: ContextView,
    ): Mono<Boolean> =
        // Deferred so a client function that throws synchronously fails as an onError signal,
        // which keeps the lock release in doFinally reachable.
        Mono
            .defer { setFunction(key, value, value.timeToLiveMillis) }
            .onErrorMap { e -> ClientException(ErrorCode.SET_CACHE_ERROR, cause = e) }
            .subscribeOn(reqShieldConfig.scheduler)
            .doFinally {
                // Only the holder of a token took a lock, so only it may release one.
                // Placed after subscribeOn: a scheduler that rejects the write (disposed or saturated) never
                // subscribes the operators above it, so a release there would never run.
                if (token != null) {
                    releaseLock(key, lockType, token, callerContext)
                }
            }

    private fun releaseLock(
        key: String,
        lockType: LockType,
        token: String,
        // The release is subscribed on its own. A lock function that reads the Reactor Context (a lock store per
        // tenant, for example) has to see the Context that tryLock saw, or it releases in another store.
        callerContext: ContextView,
    ) {
        // No retry needed: false means lock already released or expired (not an error).
        Mono.defer { lockFor(lockType).unLock(key, lockType, token) }
            .doOnNext { unlocked ->
                if (!unlocked) {
                    log.debug("Lock already released or expired for key '{}'", key)
                }
            }.contextWrite(callerContext)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to unlock key '{}': {}", key, e.message, e) },
            )
    }

    private fun executeCallable(callable: Callable<Mono<T?>>): Mono<T?> =
        // Deferred so a supplier that throws synchronously fails as an onError signal,
        // which keeps the lock release of the caller reachable.
        Mono
            .defer { callable.call() }
            .onErrorMap { e -> ClientException(ErrorCode.SUPPLIER_ERROR, cause = e) }
}
