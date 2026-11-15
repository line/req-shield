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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A caller can cancel after the store took the lock but before the token reached it. [KeyGlobalLock] owns the token
 * at that moment, so it releases the lock. The lock functions here work on a map, like `SET NX PX` and a
 * compare-and-delete by token.
 */
class KeyGlobalLockCancellationTest {
    private val key = "cancelled-lock-${UUID.randomUUID()}"
    private val lockKey = "$LOCK_KEY_PREFIX${key}_${LockType.CREATE.name}"
    private val tenant = "alice"

    // lock key -> token of the holder
    private val store = ConcurrentHashMap<String, String>()

    // The tokens that the lock function got
    private val lockTokens = CopyOnWriteArrayList<String>()
    private val unlockCalls = CopyOnWriteArrayList<UnlockCall>()

    private data class UnlockCall(
        val lockKey: String,
        val token: String,
        // The tenant in the Reactor Context of the unlock call
        val tenant: String?,
    )

    private val unlockFunction: (String, String) -> Mono<Boolean> = { lockKey, token ->
        Mono.deferContextual { context ->
            Mono.fromSupplier {
                unlockCalls.add(UnlockCall(lockKey, token, context.getOrDefault<String>("tenant", null)))
                store.remove(lockKey, token)
            }
        }
    }

    /** Takes the lock in the store and then gives no answer, like a store call whose answer is still on its way. */
    private val lockThatTakesTheLockAndNeverAnswers: (String, String, Long) -> Mono<Boolean> = { lockKey, token, _ ->
        Mono
            .fromRunnable<Boolean> {
                lockTokens.add(token)
                store[lockKey] = token
            }.then(Mono.never())
    }

    /** Takes the lock in the store and answers [acquired] at once. */
    private fun lockThatAnswers(acquired: Boolean): (String, String, Long) -> Mono<Boolean> =
        { lockKey, token, _ ->
            Mono.fromSupplier {
                lockTokens.add(token)
                if (acquired) store[lockKey] = token
                acquired
            }
        }

    private fun keyLock(lockFunction: (String, String, Long) -> Mono<Boolean>) = KeyGlobalLock(lockFunction, unlockFunction, 3_000)

    private fun Mono<*>.asTenant() = contextWrite { it.put("tenant", tenant) }

    @Test
    fun aCancelAfterTheStoreTookTheLockShouldReleaseItWithTheSameKeyAndTokenInTheCallerContext() {
        val caller = keyLock(lockThatTakesTheLockAndNeverAnswers).tryLock(key, LockType.CREATE).asTenant().subscribe()
        await().atMost(Duration.ofSeconds(2)).until { store.containsKey(lockKey) }

        caller.dispose()

        await().atMost(Duration.ofSeconds(2)).until { unlockCalls.isNotEmpty() }
        assertEquals(listOf(UnlockCall(lockKey, lockTokens.single(), tenant)), unlockCalls)
        assertTrue(store.isEmpty(), "the lock stays in the store")
    }

    @Test
    fun aCancelShouldStillGoOnWhenTheReleaseFails() {
        val failingUnlockCalls = CopyOnWriteArrayList<String>()
        val keyLock =
            KeyGlobalLock(
                lockThatTakesTheLockAndNeverAnswers,
                { _, token ->
                    failingUnlockCalls.add(token)
                    throw IllegalStateException("the lock store is down")
                },
                3_000,
            )
        val caller = keyLock.tryLock(key, LockType.CREATE).subscribe()

        caller.dispose()

        assertTrue(caller.isDisposed)
        assertEquals(lockTokens, failingUnlockCalls)
    }

    @Test
    fun aLockThatAnsweredFalseShouldNotBeReleased() {
        StepVerifier
            .create(keyLock(lockThatAnswers(false)).tryLock(key, LockType.CREATE))
            .verifyComplete()

        assertEquals(1, lockTokens.size)
        assertEquals(emptyList<UnlockCall>(), unlockCalls)
    }

    @Test
    fun aCancelAfterTheLockAnsweredFalseShouldNotReleaseTheLock() {
        // materialize lets the downstream go on after the answer, so its cancel reaches tryLock after it ended
        val caller =
            keyLock(lockThatAnswers(false))
                .tryLock(key, LockType.CREATE)
                .materialize()
                .flatMap { Mono.never<Unit>() }
                .subscribe()

        caller.dispose()

        assertEquals(1, lockTokens.size)
        assertEquals(emptyList<UnlockCall>(), unlockCalls)
    }

    @Test
    fun aLockThatFailedShouldNotBeReleased() {
        val keyLock = keyLock { _, _, _ -> Mono.error(IllegalStateException("the lock store is down")) }

        StepVerifier
            .create(keyLock.tryLock(key, LockType.CREATE))
            .expectErrorMessage("the lock store is down")
            .verify()

        assertEquals(emptyList<UnlockCall>(), unlockCalls)
    }

    @Test
    fun aCancelAfterTheTokenWasEmittedShouldNotReleaseTheLock() {
        val emitted = CopyOnWriteArrayList<String>()
        // The caller owns the token once it was emitted: a cancel during its work must not release the lock
        val caller =
            keyLock(lockThatAnswers(true))
                .tryLock(key, LockType.CREATE)
                .doOnNext { emitted.add(it) }
                .flatMap { Mono.never<String>() }
                .subscribe()

        caller.dispose()

        assertEquals(lockTokens, emitted)
        assertEquals(emptyList<UnlockCall>(), unlockCalls)
        assertEquals(mapOf(lockKey to lockTokens.single()), store)
    }
}
