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

import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX

interface KeyLock {
    /** Returns an opaque ownership token when acquired, or null when another holder owns the lock. */
    suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String?

    /** Releases only if [token] matches the current owner; false when not held or token mismatch. */
    suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean
}

enum class LockType {
    CREATE,
    UPDATE,
}

/** Lock keys are prefixed so that a lock entry can never collide with a cache entry. */
internal fun lockKeyOf(
    key: String,
    lockType: LockType,
): String = "$LOCK_KEY_PREFIX${key}_${lockType.name}"
