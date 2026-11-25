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

package com.linecorp.cse.reqshield

import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import java.util.UUID

/**
 * Lock backed by a shared store (for example Redis).
 *
 * @param globalLockFunction (lockKey, token, ttlMillis) -> acquired. Must store the token only when
 *   the key is absent and must expire on its own, e.g. `SET key token NX PX ttl`.
 * @param globalUnLockFunction (lockKey, token) -> released. Must delete the key only when its value
 *   still equals the token (compare-and-delete).
 */
class KeyGlobalLock(
    private val globalLockFunction: (String, String, Long) -> Boolean,
    private val globalUnLockFunction: (String, String) -> Boolean,
    private val lockTimeoutMillis: Long,
) : KeyLock {
    override fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        // Tokens are compared across processes, so they must be globally unique
        val token = UUID.randomUUID().toString()
        return if (globalLockFunction(buildLockKey(key, lockType), token, lockTimeoutMillis)) token else null
    }

    override fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean = globalUnLockFunction(buildLockKey(key, lockType), token)

    private fun buildLockKey(
        key: String,
        lockType: LockType,
    ): String = "$LOCK_KEY_PREFIX${key}_${lockType.name}"
}
