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
import kotlin.coroutines.CoroutineContext

private val log = LoggerFactory.getLogger(KeyLocalLock::class.java)

class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock, CoroutineScope {
    private data class LockInfo(val semaphore: Semaphore, val createdAt: Long)

    private val lockMap = ConcurrentHashMap<String, LockInfo>()

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    init {
        launch {
            while (isActive) {
                runCatching {
                    val now = System.currentTimeMillis()
                    lockMap.entries.removeIf { now - it.value.createdAt > lockTimeoutMillis }
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                }.onFailure { e ->
                    log.error("Error in lock lifecycle monitoring : {}", e.message)
                }
            }
        }
    }

    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): Boolean {
        val completeKey = "${key}_${lockType.name}"
        val lockInfo = lockMap.computeIfAbsent(completeKey) { LockInfo(Semaphore(1), nowToEpochTime()) }

        return lockInfo.semaphore.tryAcquire()
    }

    override suspend fun unLock(
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

    fun cancel() {
        job.cancel()
    }
}
