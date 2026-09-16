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

import reactor.core.publisher.Mono

interface KeyLock {
    /**
     * Tries to acquire the lock for [key] and [lockType].
     *
     * Emits an opaque ownership token when the lock was acquired, and completes EMPTY
     * when another holder currently owns the lock.
     */
    fun tryLock(
        key: String,
        lockType: LockType,
    ): Mono<String>

    /**
     * Releases the lock for [key] and [lockType] only if [token] matches the current owner.
     *
     * Emits false when the lock is not held or the token does not match, so an expired lock
     * that was already handed to another holder can never be released by a stale owner.
     */
    fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Mono<Boolean>
}

enum class LockType {
    CREATE,
    UPDATE,
}
