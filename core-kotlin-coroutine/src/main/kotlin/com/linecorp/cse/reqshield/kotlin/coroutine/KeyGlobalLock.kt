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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger(KeyGlobalLock::class.java)

class KeyGlobalLock(
    private val globalLockFunction: suspend (String, String, Long) -> Boolean,
    private val globalUnLockFunction: suspend (String, String) -> Boolean,
    private val lockTimeoutMillis: Long,
) : KeyLock {
    /**
     * Calls the lock function with a new token, and returns the token when the store took the lock.
     *
     * The store can take the lock before the caller is cancelled, and the token then never reaches the caller. So
     * when the lock function ends with the cancellation of the caller, this function calls the unlock function with
     * that token before the cancellation goes on. The unlock function deletes the lock only when the token matches,
     * so the call is safe also when the store took no lock. A failed release is only logged.
     *
     * Limits: a cancellation before the lock command was sent to the store releases nothing, and loses nothing. A
     * cancellation after the command was sent is covered only when the unlock command reaches the store after it. If
     * the unlock command arrives first, the lock stays until its timeout ends.
     *
     * A [CancellationException] while the caller is still active (a `withTimeout` inside the lock function) is a
     * failure of the lock function, like any other exception: nothing is released.
     */
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        // The token must be unique across every process sharing the lock store.
        val token = UUID.randomUUID().toString()
        val lockKey = lockKeyOf(key, lockType)
        val acquired =
            try {
                globalLockFunction(lockKey, token, lockTimeoutMillis)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) releaseAfterCancellation(lockKey, token)
                throw e
            }
        return if (acquired) token else null
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean = globalUnLockFunction(lockKeyOf(key, lockType), token)

    private suspend fun releaseAfterCancellation(
        lockKey: String,
        token: String,
    ) {
        try {
            withContext(NonCancellable) { globalUnLockFunction(lockKey, token) }
        } catch (e: Exception) {
            log.error("[Req-Shield] failed to unlock key '{}' after its lock call was cancelled", lockKey, e)
        }
    }
}
