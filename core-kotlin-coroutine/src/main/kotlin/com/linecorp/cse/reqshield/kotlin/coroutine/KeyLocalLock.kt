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
    )

    companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()

        @Volatile
        private var monitorJob: Job? = null

        private fun ensureMonitorStarted() {
            if (monitorJob?.isActive == true) return
            synchronized(this) {
                if (monitorJob?.isActive == true) return
                monitorJob =
                    CoroutineScope(Dispatchers.IO).launch {
                        while (isActive) {
                            runCatching {
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
                                            null // Atomic removal
                                        } else {
                                            lockInfo // Keep the entry
                                        }
                                    }
                                }
                                delay(LOCK_MONITOR_INTERVAL_MILLIS)
                            }.onFailure { e ->
                                log.error("Error in lock lifecycle monitoring: {}", e.message, e)
                            }
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
    ): Boolean {
        val completeKey = "${key}_${lockType.name}"
        val now = nowToEpochTime()
        val result = AtomicBoolean(false)

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
                }

                // Existing entry: try to acquire semaphore
                if (existing.semaphore.tryAcquire()) {
                    existing.isHeld.set(true)
                    existing.expiresAt = now + lockTimeoutMillis
                    result.set(true)
                }
                existing
            } else {
                // New entry: create and acquire
                val newLock = LockInfo(Semaphore(1), now + lockTimeoutMillis)
                newLock.semaphore.tryAcquire() // Always succeeds for new semaphore
                newLock.isHeld.set(true)
                result.set(true)
                newLock
            }
        }
        return result.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
    ): Boolean {
        val completeKey = "${key}_${lockType.name}"
        val lockInfo = lockMap[completeKey] ?: return false

        // Use CAS to prevent over-release: only release if we actually hold the lock
        return if (lockInfo.isHeld.compareAndSet(true, false)) {
            lockInfo.semaphore.release()
            true
        } else {
            log.debug("Attempted to unlock key '{}' that is not held", completeKey)
            false
        }
    }

    fun cancel() {
        job.cancel()
        // Monitor cleanup is handled via stopMonitoring() in tests
    }
}
