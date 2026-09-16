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

import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import reactor.core.publisher.Mono
import java.util.UUID

class KeyGlobalLock(
    private val globalLockFunction: (String, String, Long) -> Mono<Boolean>,
    private val globalUnLockFunction: (String, String) -> Mono<Boolean>,
    private val lockTimeoutMillis: Long,
) : KeyLock {
    override fun tryLock(
        key: String,
        lockType: LockType,
    ): Mono<String> =
        Mono.defer {
            // A fresh token per attempt: only this attempt may release the lock it acquired.
            val token = UUID.randomUUID().toString()
            globalLockFunction(completeKey(key, lockType), token, lockTimeoutMillis)
                .flatMap { acquired -> if (acquired) Mono.just(token) else Mono.empty() }
        }

    override fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Mono<Boolean> = globalUnLockFunction(completeKey(key, lockType), token)

    private fun completeKey(
        key: String,
        lockType: LockType,
    ): String = "$LOCK_KEY_PREFIX${key}_${lockType.name}"
}
