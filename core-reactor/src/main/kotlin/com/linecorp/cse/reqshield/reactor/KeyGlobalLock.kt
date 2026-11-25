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
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger(KeyGlobalLock::class.java)

class KeyGlobalLock(
    private val globalLockFunction: (String, String, Long) -> Mono<Boolean>,
    private val globalUnLockFunction: (String, String) -> Mono<Boolean>,
    private val lockTimeoutMillis: Long,
) : KeyLock {
    /**
     * Calls the lock function with a new token, and emits the token when the store took the lock.
     *
     * The store can take the lock before the caller cancels, and the token then never reaches the caller. So when
     * the caller cancels before the lock function answered, this function subscribes the unlock function with that
     * token, in the caller's Reactor Context. The unlock function deletes the lock only when the token matches, so
     * the call is safe also when the store took no lock. A failed release is only logged. After the answer, the
     * token belongs to the caller, and a later cancel releases nothing here.
     *
     * Limits: a cancel before the lock command was sent to the store releases nothing, and loses nothing. A cancel
     * after the command was sent is covered only when the unlock command reaches the store after it. If the unlock
     * command arrives first, the lock stays until its timeout ends.
     */
    override fun tryLock(
        key: String,
        lockType: LockType,
    ): Mono<String> =
        Mono.deferContextual { callerContext ->
            // A fresh token per attempt: only this attempt may release the lock it acquired.
            val token = UUID.randomUUID().toString()
            val lockKey = completeKey(key, lockType)
            // Set by the answer of the lock function or by a cancel, whichever comes first
            val settled = AtomicBoolean(false)
            globalLockFunction(lockKey, token, lockTimeoutMillis)
                .flatMap { acquired -> if (acquired) Mono.just(token) else Mono.empty() }
                // Set before the token goes on: from then on the caller owns it
                .doOnEach { settled.set(true) }
                .doOnCancel { if (settled.compareAndSet(false, true)) releaseAfterCancel(lockKey, token, callerContext) }
        }

    override fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Mono<Boolean> = globalUnLockFunction(completeKey(key, lockType), token)

    private fun releaseAfterCancel(
        lockKey: String,
        token: String,
        // The release is subscribed on its own, so it sees the Context of the lock call only when it is handed over.
        callerContext: ContextView,
    ) {
        // Deferred so an unlock function that throws synchronously fails as an onError signal, which is logged.
        Mono
            .defer { globalUnLockFunction(lockKey, token) }
            .contextWrite(callerContext)
            .subscribe(
                { /* success - no action needed */ },
                { e -> log.error("Failed to unlock key '{}' after its lock call was cancelled: {}", lockKey, e.message, e) },
            )
    }

    private fun completeKey(
        key: String,
        lockType: LockType,
    ): String = "$LOCK_KEY_PREFIX${key}_${lockType.name}"
}
