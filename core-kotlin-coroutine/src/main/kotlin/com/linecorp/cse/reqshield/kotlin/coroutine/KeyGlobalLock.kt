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

import java.util.UUID

class KeyGlobalLock(
    private val globalLockFunction: suspend (String, String, Long) -> Boolean,
    private val globalUnLockFunction: suspend (String, String) -> Boolean,
    private val lockTimeoutMillis: Long,
) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        // The token must be unique across every process sharing the lock store.
        val token = UUID.randomUUID().toString()
        return if (globalLockFunction(lockKeyOf(key, lockType), token, lockTimeoutMillis)) token else null
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean = globalUnLockFunction(lockKeyOf(key, lockType), token)
}
