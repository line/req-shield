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

package com.linecorp.cse.reqshield.config

import com.linecorp.cse.reqshield.KeyGlobalLock
import com.linecorp.cse.reqshield.KeyLocalLock
import com.linecorp.cse.reqshield.KeyLock
import com.linecorp.cse.reqshield.support.constant.ConfigValues.DEFAULT_DECISION_FOR_UPDATE
import com.linecorp.cse.reqshield.support.constant.ConfigValues.DEFAULT_LOCK_TIMEOUT_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_ATTEMPT_GET_CACHE
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

data class ReqShieldConfiguration<T>(
    val setCacheFunction: (String, ReqShieldData<T>, Long) -> Boolean,
    val getCacheFunction: (String) -> ReqShieldData<T>?,
    /**
     * (lockKey, token, ttlMillis) -> acquired. Required when [isLocalLock] is false.
     * The implementation must acquire only when the key is absent and must let the lock expire
     * on its own, e.g. `SET key token NX PX ttl`.
     */
    val globalLockFunction: ((String, String, Long) -> Boolean)? = null,
    /**
     * (lockKey, token) -> released. Required when [isLocalLock] is false.
     * The implementation must be a compare-and-delete: delete the key only while its value still
     * equals the token, so an expired holder cannot release the lock of the next holder.
     */
    val globalUnLockFunction: ((String, String) -> Boolean)? = null,
    val isLocalLock: Boolean = true,
    val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    /**
     * Executor used for the asynchronous cache writes. Only [Executor.execute] is called, so any
     * pool works, and the library never shuts the pool down - a caller-supplied one stays the
     * caller's to manage. Defaults to a single pool shared by every configuration instance.
     */
    val executor: Executor = sharedExecutor,
    val decisionForUpdate: Int = DEFAULT_DECISION_FOR_UPDATE,
    val keyLock: KeyLock = defaultKeyLock(isLocalLock, globalLockFunction, globalUnLockFunction, lockTimeoutMillis),
    val maxAttemptGetCache: Int = MAX_ATTEMPT_GET_CACHE,
    val reqShieldWorkMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
) {
    companion object {
        private val executorThreadCounter = AtomicLong(0)

        /**
         * Shared by every configuration instance: one ReqShield per cache key must not mean one
         * thread pool per cache key. Threads are daemons so the pool never blocks JVM shutdown.
         */
        private val sharedExecutor: ScheduledExecutorService by lazy {
            Executors.newScheduledThreadPool(
                maxOf(2, Runtime.getRuntime().availableProcessors() * 2),
            ) { runnable ->
                Thread(runnable, "req-shield-executor-${executorThreadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }

    init {
        if (!isLocalLock) {
            requireNotNull(globalLockFunction) {
                ErrorCode.DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION.message
            }
            requireNotNull(globalUnLockFunction) {
                ErrorCode.DOES_NOT_EXIST_GLOBAL_UNLOCK_FUNCTION.message
            }
        }
    }
}

/**
 * Builds the [KeyLock] used when the caller does not pass one.
 *
 * A default parameter expression is evaluated before the init block, so the global lock functions
 * must be validated here as well to report a missing one as an [IllegalArgumentException].
 */
private fun defaultKeyLock(
    isLocalLock: Boolean,
    globalLockFunction: ((String, String, Long) -> Boolean)?,
    globalUnLockFunction: ((String, String) -> Boolean)?,
    lockTimeoutMillis: Long,
): KeyLock =
    if (isLocalLock) {
        KeyLocalLock(lockTimeoutMillis)
    } else {
        KeyGlobalLock(
            requireNotNull(globalLockFunction) { ErrorCode.DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION.message },
            requireNotNull(globalUnLockFunction) { ErrorCode.DOES_NOT_EXIST_GLOBAL_UNLOCK_FUNCTION.message },
            lockTimeoutMillis,
        )
    }

enum class ReqShieldWorkMode {
    CREATE_AND_UPDATE_CACHE,
    ONLY_CREATE_CACHE,
    ONLY_UPDATE_CACHE,
}
