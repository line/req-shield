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

package com.linecorp.cse.reqshield

import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(KeyLocalLock::class.java)

class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    private data class LockInfo(val semaphore: Semaphore, val createdAt: Long)

    companion object {
        // Global lockMap shared by all instances - CRITICAL FIX for request collapsing
        private val lockMap = ConcurrentHashMap<String, LockInfo>()

        // Single scheduler shared by all instances
        @Volatile
        private var sharedScheduler: ScheduledExecutorService? = null

        // Track active instances
        private val instances = ConcurrentHashMap.newKeySet<KeyLocalLock>()

        // Thread-safe lazy initialization
        private fun getOrCreateScheduler(): ScheduledExecutorService {
            return sharedScheduler ?: synchronized(this) {
                sharedScheduler ?: createScheduler().also {
                    sharedScheduler = it
                    startMonitoring(it)
                }
            }
        }

        private fun createScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "req-shield-lock-monitor").apply {
                    isDaemon = true // Does not prevent JVM shutdown
                    priority = Thread.MIN_PRIORITY // Low priority
                }
            }
        }

        private fun startMonitoring(scheduler: ScheduledExecutorService) {
            // Batch cleanup for all instances (10ms → 1000ms)
            scheduler.scheduleWithFixedDelay({
                try {
                    instances.forEach { instance ->
                        instance.cleanupExpiredLocks()
                    }
                } catch (e: Exception) {
                    log.error("Error in shared lock lifecycle monitoring: {}", e.message)
                }
            }, 0, LOCK_MONITOR_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }

        // Automatic cleanup on JVM shutdown
        init {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    sharedScheduler?.let { scheduler ->
                        log.debug("Shutting down shared ReqShield scheduler")
                        scheduler.shutdown()
                        try {
                            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                                scheduler.shutdownNow()
                            }
                        } catch (_: InterruptedException) {
                            scheduler.shutdownNow()
                        }
                    }
                }.apply { name = "req-shield-shutdown-hook" },
            )
        }
    }

    init {
        // Register instance and initialize scheduler
        instances.add(this)
        getOrCreateScheduler()
    }

    // Internal cleanup method (called by shared scheduler)
    internal fun cleanupExpiredLocks() {
        val now = System.currentTimeMillis()
        val expiredCount = lockMap.size
        lockMap.entries.removeIf { now - it.value.createdAt > lockTimeoutMillis }
        val remainingCount = lockMap.size

        if (log.isTraceEnabled && expiredCount > remainingCount) {
            log.trace(
                "Cleaned up {} expired locks, {} remaining",
                expiredCount - remainingCount,
                remainingCount,
            )
        }
    }

    override fun tryLock(
        key: String,
        lockType: LockType,
    ): Boolean {
        val completeKey = "${key}_${lockType.name}"
        val lockInfo = lockMap.computeIfAbsent(completeKey) { LockInfo(Semaphore(1), nowToEpochTime()) }

        return lockInfo.semaphore.tryAcquire()
    }

    override fun unLock(
        key: String,
        lockType: LockType,
    ): Boolean {
        val completeKey = "${key}_${lockType.name}"
        val lockInfo = lockMap[completeKey]
        lockInfo?.let {
            it.semaphore.release()
            lockMap.remove(completeKey)
        }
        return true
    }

    fun shutdown() {
        // Deregister instance
        instances.remove(this)

        // Shared scheduler is managed globally, no individual shutdown needed
        log.debug("KeyLocalLock instance deregistered from shared monitoring")
    }
}
