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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

data class ReqShieldConfiguration<T>(
    val setCacheFunction: (String, ReqShieldData<T>, Long) -> Boolean,
    val getCacheFunction: (String) -> ReqShieldData<T>?,
    val globalLockFunction: ((String, Long) -> Boolean)? = null,
    val globalUnLockFunction: ((String) -> Boolean)? = null,
    val isLocalLock: Boolean = true,
    val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS, // for local and global lock
    val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * 10,
        ),
    val decisionForUpdate: Int = DEFAULT_DECISION_FOR_UPDATE, // %
    val keyLock: KeyLock =
        if (isLocalLock) {
            KeyLocalLock(lockTimeoutMillis)
        } else {
            KeyGlobalLock(globalLockFunction!!, globalUnLockFunction!!, lockTimeoutMillis)
        },
    val maxAttemptGetCache: Int = MAX_ATTEMPT_GET_CACHE,
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
}
