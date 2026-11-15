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

import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith

/**
 * A caller can be cancelled after the store took the lock but before the token reached it. [KeyGlobalLock] owns the
 * token at that moment, so it releases the lock. The lock functions here work on a map, like `SET NX PX` and a
 * compare-and-delete by token.
 */
class KeyGlobalLockCancellationTest {
    private val key = "cancelled-lock-${UUID.randomUUID()}"
    private val lockKey = lockKeyOf(key, LockType.CREATE)

    // lock key -> token of the holder
    private val store = ConcurrentHashMap<String, String>()

    // The tokens that the lock function got
    private val lockTokens = CopyOnWriteArrayList<String>()

    // lock key and token of each unlock call
    private val unlockCalls = CopyOnWriteArrayList<Pair<String, String>>()
    private val lockTaken = CompletableDeferred<Unit>()

    // Runs the callers, so a cancelled caller does not cancel the test
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() = callers.cancel()

    // Suspends like a store client, so a release in a cancelled coroutine would not run
    private val unlockFunction: suspend (String, String) -> Boolean = { lockKey, token ->
        withContext(Dispatchers.IO) {
            unlockCalls.add(lockKey to token)
            store.remove(lockKey, token)
        }
    }

    private fun takeTheLock(
        lockKey: String,
        token: String,
    ) {
        lockTokens.add(token)
        store[lockKey] = token
        lockTaken.complete(Unit)
    }

    /** Takes the lock in the store and then gives no answer, like a store call whose answer is still on its way. */
    private val lockThatTakesTheLockAndNeverAnswers: suspend (String, String, Long) -> Boolean = { lockKey, token, _ ->
        takeTheLock(lockKey, token)
        awaitCancellation()
    }

    private fun keyLock(
        lockFunction: suspend (String, String, Long) -> Boolean,
        unLockFunction: suspend (String, String) -> Boolean = unlockFunction,
    ) = KeyGlobalLock(lockFunction, unLockFunction, 3_000)

    private fun assertTheLockWasReleased() {
        assertEquals(listOf(lockKey to lockTokens.single()), unlockCalls)
        assertTrue(store.isEmpty(), "the lock stays in the store")
    }

    @Test
    fun aCancellationAfterTheStoreTookTheLockShouldReleaseItWithTheSameKeyAndToken() =
        runBlocking {
            val caller = callers.async { keyLock(lockThatTakesTheLockAndNeverAnswers).tryLock(key, LockType.CREATE) }
            withTimeout(2_000) { lockTaken.await() }

            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            assertTheLockWasReleased()
        }

    @Test
    fun anAnswerThatTheCancellationOvertookShouldBeReleased() =
        runBlocking {
            val answer = CompletableDeferred<Unit>()
            // The store took the lock and its answer arrives after the caller was cancelled. withContext then
            // throws the cancellation of the caller instead of returning the answer.
            val lockFunction: suspend (String, String, Long) -> Boolean = { lockKey, token, _ ->
                withContext(NonCancellable + Dispatchers.IO) {
                    takeTheLock(lockKey, token)
                    answer.await()
                    true
                }
            }
            val caller = callers.async { keyLock(lockFunction).tryLock(key, LockType.CREATE) }
            withTimeout(2_000) { lockTaken.await() }

            caller.cancel()
            answer.complete(Unit)
            withTimeout(2_000) { caller.join() }

            assertTrue(caller.isCancelled)
            assertTheLockWasReleased()
        }

    @Test
    fun aCancellationShouldGoOnWhenTheReleaseFails() =
        runBlocking {
            val failingUnlockCalls = CopyOnWriteArrayList<String>()
            val keyLock =
                keyLock(lockThatTakesTheLockAndNeverAnswers) { _, token ->
                    failingUnlockCalls.add(token)
                    throw IllegalStateException("the lock store is down")
                }
            val caller = callers.async { keyLock.tryLock(key, LockType.CREATE) }
            withTimeout(2_000) { lockTaken.await() }

            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            assertEquals(lockTokens, failingUnlockCalls)
        }

    @Test
    fun aLockThatAnsweredFalseShouldNotBeReleased() =
        runBlocking {
            val keyLock =
                keyLock({ _, token, _ ->
                    lockTokens.add(token)
                    false
                })

            assertEquals(null, keyLock.tryLock(key, LockType.CREATE))
            assertEquals(1, lockTokens.size)
            assertEquals(emptyList<Pair<String, String>>(), unlockCalls)
        }

    @Test
    fun aLockThatTimedOutInsideTheLockFunctionShouldNotBeReleasedWhileTheCallerIsActive() =
        runBlocking {
            // The timeout is a failure of the lock function, not a cancellation of the caller
            val keyLock =
                keyLock({ lockKey, token, _ ->
                    takeTheLock(lockKey, token)
                    withTimeout(10) { awaitCancellation() }
                })

            assertFailsWith<TimeoutCancellationException> { keyLock.tryLock(key, LockType.CREATE) }
            assertEquals(emptyList<Pair<String, String>>(), unlockCalls)
        }

    @Test
    fun aLockThatFailedShouldNotBeReleased() =
        runBlocking {
            val keyLock = keyLock({ _, _, _ -> throw IllegalStateException("the lock store is down") })

            assertFailsWith<IllegalStateException> { keyLock.tryLock(key, LockType.CREATE) }
            assertEquals(emptyList<Pair<String, String>>(), unlockCalls)
        }

    @Test
    fun aCancelledRequestShouldLeaveNoLockInTheStoreWhenItsLockCallWasCancelled() =
        runBlocking {
            val supplierCalls = CopyOnWriteArrayList<Unit>()
            val shield =
                ReqShield(
                    ReqShieldConfiguration<String>(
                        setCacheFunction = { _, _, _ -> true },
                        getCacheFunction = { null },
                        keyLock = keyLock(lockThatTakesTheLockAndNeverAnswers),
                    ),
                )
            val request =
                callers.async {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierCalls.add(Unit)
                            "value"
                        },
                        10_000,
                    )
                }
            withTimeout(2_000) { lockTaken.await() }

            request.cancelAndJoin()

            assertTrue(request.isCancelled)
            assertTheLockWasReleased()
            assertEquals(0, supplierCalls.size)
        }
}
