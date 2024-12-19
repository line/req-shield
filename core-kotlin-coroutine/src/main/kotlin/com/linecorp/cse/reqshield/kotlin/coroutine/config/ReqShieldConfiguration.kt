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
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData

data class ReqShieldConfiguration<T>(
    val setCacheFunction: suspend (String, ReqShieldData<T>, Long) -> Boolean,
    val getCacheFunction: suspend (String) -> ReqShieldData<T>?,
    val globalLockFunction: (suspend (String, Long) -> Boolean)? = null,
    val globalUnLockFunction: (suspend (String) -> Boolean)? = null,
    val isLocalLock: Boolean = true,
    val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    val decisionForUpdate: Int = DEFAULT_DECISION_FOR_UPDATE, // %
    val keyLock: KeyLock =
        if (isLocalLock) {
            KeyLocalLock(lockTimeoutMillis)
        } else {
            KeyGlobalLock(globalLockFunction!!, globalUnLockFunction!!, lockTimeoutMillis)
        },
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
