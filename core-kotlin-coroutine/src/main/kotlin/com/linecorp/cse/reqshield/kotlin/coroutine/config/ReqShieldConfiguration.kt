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

package com.linecorp.cse.reqshield.kotlin.coroutine.config

import com.linecorp.cse.reqshield.kotlin.coroutine.KeyGlobalLock
import com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock
import com.linecorp.cse.reqshield.kotlin.coroutine.KeyLock
import com.linecorp.cse.reqshield.support.constant.ConfigValues.DEFAULT_DECISION_FOR_UPDATE
import com.linecorp.cse.reqshield.support.constant.ConfigValues.DEFAULT_LOCK_TIMEOUT_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_ATTEMPT_GET_CACHE
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

data class ReqShieldConfiguration<T>(
    val setCacheFunction: suspend (String, ReqShieldData<T>, Long) -> Boolean,
    val getCacheFunction: suspend (String) -> ReqShieldData<T>?,
    /**
     * Acquires the global lock. Invoked with (lockKey, token, ttlMillis) and must return true only
     * when this caller is the one that acquired the lock.
     *
     * Recommended Redis implementation: `SET lockKey token NX PX ttlMillis`.
     */
    val globalLockFunction: (suspend (String, String, Long) -> Boolean)? = null,
    /**
     * Releases the global lock. Invoked with (lockKey, token) and must release the lock only when
     * the stored value is still equal to the given token (compare-and-delete), so an owner whose
     * lock already expired cannot release the lock of the next owner.
     *
     * Recommended Redis implementation: a Lua script that compares `GET lockKey` with the token and
     * deletes the key only on a match.
     */
    val globalUnLockFunction: (suspend (String, String) -> Boolean)? = null,
    val isLocalLock: Boolean = true,
    val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    val decisionForUpdate: Int = DEFAULT_DECISION_FOR_UPDATE,
    val keyLock: KeyLock =
        if (isLocalLock) {
            KeyLocalLock(lockTimeoutMillis)
        } else {
            KeyGlobalLock(globalLockFunction!!, globalUnLockFunction!!, lockTimeoutMillis)
        },
    val maxAttemptGetCache: Int = MAX_ATTEMPT_GET_CACHE,
    val reqShieldWorkMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
    /**
     * Scope that runs every fire-and-forget cache write.
     *
     * The default is a process-wide shared scope, so creating a configuration never leaks a Job.
     * Callers that need lifecycle control (e.g. draining pending writes on shutdown) should pass
     * their own scope and cancel it themselves.
     */
    val scope: CoroutineScope = defaultScope,
) {
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

    companion object {
        private val log = LoggerFactory.getLogger(ReqShieldConfiguration::class.java)

        /**
         * Shared scope for background cache writes, created on first use.
         *
         * SupervisorJob keeps one failed write from cancelling the others, and the exception handler
         * is the last-resort backstop for anything the write path did not already log.
         */
        private val defaultScope: CoroutineScope by lazy {
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, e ->
                        log.error("[Req-Shield] background task failed", e)
                    },
            )
        }
    }
}

enum class ReqShieldWorkMode {
    CREATE_AND_UPDATE_CACHE,
    ONLY_CREATE_CACHE,
    ONLY_UPDATE_CACHE,
}
