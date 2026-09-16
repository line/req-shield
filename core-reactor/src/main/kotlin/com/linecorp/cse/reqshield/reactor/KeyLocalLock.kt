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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(KeyLocalLock::class.java)

class KeyLocalLock(
    private val lockTimeoutMillis: Long,
) : KeyLock {
    /**
     * Internal lock state holder.
     * Using class instead of data class to allow mutable expiresAt for atomic updates.
     */
    private class LockInfo(
        val semaphore: Semaphore,
        /**
         * Expiration timestamp in milliseconds.
         * @Volatile ensures visibility across threads when updated inside compute() and read by monitor.
         */
        @Volatile var expiresAt: Long,
        /**
         * Tracks whether the lock is currently held.
         * Uses AtomicBoolean with CAS operations to prevent over-release
         * when multiple threads race to release the same lock (e.g., tryLock expiration
         * check vs unLock, or monitor cleanup vs unLock).
         */
        val isHeld: AtomicBoolean = AtomicBoolean(false),
        /**
         * Ownership token of the current holder, null when the lock is not held.
         * Only the holder that owns this token may release the lock, so a holder whose
         * lock already expired cannot release the lock of the next holder.
         */
        @Volatile var token: String? = null,
    )

    companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()

        // Monotonic counter backing the local ownership tokens. A counter is enough because
        // the tokens never leave this JVM, and it is far cheaper than UUID generation.
        private val tokenSequence = AtomicLong(0)

        @Volatile
        private var monitoringStarted: Boolean = false

        @Volatile
        private var monitorDisposable: Disposable? = null

        // Track consecutive failures for rate-limited logging
        private val consecutiveFailures = AtomicInteger(0)

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        private fun startMonitoringOnce() {
            if (monitoringStarted) return
            synchronized(this) {
                if (monitoringStarted) return
                monitorDisposable =
                    Flux
                        .interval(Duration.ofMillis(LOCK_MONITOR_INTERVAL_MILLIS), Schedulers.single())
                        // Concurrency of 1: a slow cleanup must not fan out into overlapping runs.
                        .flatMap({
                            Mono
                                .fromRunnable<Unit> {
                                    val now = System.currentTimeMillis()
                                    // Remove expired locks using compute() for atomic check-and-remove.
                                    // This prevents TOCTOU race condition where removeIf's lambda returns true
                                    // but the actual removal happens after a new lock is acquired.
                                    // compute() guarantees atomic execution per key, so cleanup and tryLock
                                    // are mutually exclusive for the same key.
                                    lockMap.keys.forEach { key ->
                                        lockMap.compute(key) { _, lockInfo ->
                                            if (lockInfo == null) return@compute null

                                            if (now > lockInfo.expiresAt) {
                                                // Expired lock: force release regardless of isHeld state.
                                                // This handles the case where unlock() was missed due to exception.
                                                // CAS ensures safe release (no-op if already released).
                                                if (lockInfo.isHeld.compareAndSet(true, false)) {
                                                    lockInfo.semaphore.release()
                                                }
                                                lockInfo.token = null
                                                null // Atomic removal
                                            } else {
                                                lockInfo // Keep the entry
                                            }
                                        }
                                    }
                                    consecutiveFailures.set(0) // Reset on success
                                }.onErrorResume { e ->
                                    // Log only on first failure or every 10th consecutive failure
                                    val failures = consecutiveFailures.incrementAndGet()
                                    if (failures == 1 || failures % 10 == 0) {
                                        log.warn(
                                            "Error in lock lifecycle monitoring (consecutive failures: {}): {}",
                                            failures,
                                            e.message,
                                        )
                                    }
                                    Mono.empty()
                                }
                        }, 1).subscribe(
                            { /* success - no action needed */ },
                            { e -> log.error("Fatal error in lock lifecycle monitoring: {}", e.message, e) },
                        )
                monitoringStarted = true
            }
        }

        // For testing and resource cleanup
        internal fun stopMonitoring() {
            synchronized(this) {
                monitorDisposable?.dispose()
                monitorDisposable = null
                consecutiveFailures.set(0)
                monitoringStarted = false
            }
        }
    }

    init {
        startMonitoringOnce()
    }

    override fun tryLock(
        key: String,
        lockType: LockType,
    ): Mono<String> =
        Mono.fromCallable {
            val completeKey = completeKey(key, lockType)
            val now = nowToEpochTime()
            // Holds the token handed out by this attempt, or null when the lock could not be acquired.
            val acquiredToken = AtomicReference<String?>(null)

            // Use compute() for atomic lock acquisition.
            // This ensures mutual exclusion with cleanup - they cannot race on the same key.
            lockMap.compute(completeKey) { _, existing ->
                if (existing != null) {
                    // Force-release expired locks to allow reacquisition.
                    // Use CAS to prevent race condition with concurrent unLock().
                    // Without CAS, if unLock() executes between isHeld.get() and release(),
                    // both threads would call release(), causing over-release (permits > 1).
                    if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {
                        existing.semaphore.release()
                        // The previous holder lost ownership: its token must no longer release the lock.
                        existing.token = null
                    }

                    // Existing entry: try to acquire semaphore
                    if (existing.semaphore.tryAcquire()) {
                        val token = nextToken()
                        existing.isHeld.set(true)
                        existing.expiresAt = now + lockTimeoutMillis
                        existing.token = token
                        acquiredToken.set(token)
                    }
                    existing
                } else {
                    // New entry: create and acquire
                    val token = nextToken()
                    val newLock = LockInfo(Semaphore(1), now + lockTimeoutMillis)
                    newLock.semaphore.tryAcquire() // Always succeeds for new semaphore
                    newLock.isHeld.set(true)
                    newLock.token = token
                    acquiredToken.set(token)
                    newLock
                }
            }
            // Mono.fromCallable completes empty on a null result, which signals "not acquired".
            acquiredToken.get()
        }

    override fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Mono<Boolean> =
        Mono.fromCallable {
            val completeKey = completeKey(key, lockType)
            val released = AtomicBoolean(false)

            // Release inside compute() so that it is atomic with acquisition and cleanup:
            // no other thread can reacquire this key while the ownership check runs.
            lockMap.compute(completeKey) { _, existing ->
                if (existing == null) return@compute null

                if (existing.token == token && existing.isHeld.compareAndSet(true, false)) {
                    existing.semaphore.release()
                    existing.token = null
                    released.set(true)
                } else {
                    log.debug("Attempted to unlock key '{}' without holding its current token", completeKey)
                }
                existing // Keep the entry
            }
            released.get()
        }

    private fun completeKey(
        key: String,
        lockType: LockType,
    ): String = "$LOCK_KEY_PREFIX${key}_${lockType.name}"
}
