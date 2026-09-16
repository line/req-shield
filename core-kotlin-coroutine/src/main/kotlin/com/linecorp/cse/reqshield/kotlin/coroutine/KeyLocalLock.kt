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

import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

private val log = LoggerFactory.getLogger(KeyLocalLock::class.java)

class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock, CoroutineScope {
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
         * Token of the current owner, or null when the lock is not held.
         * Only the owner that presents this exact token may release the lock, so a holder whose
         * lock already expired and was reacquired by somebody else cannot release the new owner.
         */
        @Volatile var token: String? = null,
    )

    companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()

        /**
         * Source of local ownership tokens. A monotonic counter is enough because tokens never
         * leave this JVM, and it is far cheaper than a UUID on the lock acquisition path.
         */
        private val tokenSequence = AtomicLong()

        @Volatile
        private var monitorJob: Job? = null

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        private fun ensureMonitorStarted() {
            if (monitorJob?.isActive == true) return
            synchronized(this) {
                if (monitorJob?.isActive == true) return
                monitorJob =
                    CoroutineScope(Dispatchers.IO).launch {
                        while (isActive) {
                            try {
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
                                                lockInfo.token = null
                                                lockInfo.semaphore.release()
                                            }
                                            null // Atomic removal
                                        } else {
                                            lockInfo // Keep the entry
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.error("Error in lock lifecycle monitoring: {}", e.message, e)
                            }

                            // Delay outside the try/catch: a failed sweep must still wait for the next
                            // interval instead of turning the monitor into a hot loop.
                            delay(LOCK_MONITOR_INTERVAL_MILLIS)
                        }
                    }
            }
        }

        // For testing and resource cleanup
        internal fun stopMonitoring() {
            synchronized(this) {
                monitorJob?.cancel()
                monitorJob = null
            }
        }
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    init {
        ensureMonitorStarted()
    }

    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val completeKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val acquiredToken = AtomicReference<String?>(null)

        // Use compute() for atomic lock acquisition.
        // This ensures mutual exclusion with cleanup and unLock - they cannot race on the same key.
        lockMap.compute(completeKey) { _, existing ->
            if (existing != null) {
                // Force-release expired locks to allow reacquisition.
                // Use CAS to prevent race condition with concurrent unLock().
                // Without CAS, if unLock() executes between isHeld.get() and release(),
                // both threads would call release(), causing over-release (permits > 1).
                if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {
                    // Drop the token as well so the timed-out owner cannot release the next one.
                    existing.token = null
                    existing.semaphore.release()
                }

                // Existing entry: try to acquire semaphore
                if (existing.semaphore.tryAcquire()) {
                    val token = nextToken()
                    existing.isHeld.set(true)
                    existing.token = token
                    existing.expiresAt = now + lockTimeoutMillis
                    acquiredToken.set(token)
                }
                existing
            } else {
                // New entry: create and acquire
                val token = nextToken()
                val newLock = LockInfo(Semaphore(1), now + lockTimeoutMillis, token = token)
                newLock.semaphore.tryAcquire() // Always succeeds for new semaphore
                newLock.isHeld.set(true)
                acquiredToken.set(token)
                newLock
            }
        }
        return acquiredToken.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val completeKey = lockKeyOf(key, lockType)
        val released = AtomicBoolean(false)

        // Release inside compute() so that it is atomic with respect to acquisition and cleanup
        // of the same key: the token check and the semaphore release cannot be interleaved with a
        // reacquisition. The entry itself is kept so a waiting caller can still acquire it.
        lockMap.compute(completeKey) { _, existing ->
            if (existing == null) return@compute null

            // Only the current owner may release: a stale token belongs to an expired holder.
            if (existing.token == token && existing.isHeld.compareAndSet(true, false)) {
                existing.token = null
                existing.semaphore.release()
                released.set(true)
            }
            existing
        }

        if (!released.get()) {
            log.debug("Attempted to unlock key '{}' that is not held by token '{}'", completeKey, token)
        }
        return released.get()
    }

    fun cancel() {
        job.cancel()
        // Monitor cleanup is handled via stopMonitoring() in tests
    }
}
