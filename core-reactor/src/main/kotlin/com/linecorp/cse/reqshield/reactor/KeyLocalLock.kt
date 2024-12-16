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

import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private val log = LoggerFactory.getLogger(KeyLocalLock::class.java)

class KeyLocalLock(private val lockTimeoutMillis: Long): KeyLock {
    private data class LockInfo(val semaphore: Semaphore, val createdAt: Long)
    private val lockMap = ConcurrentHashMap<String, LockInfo>()

    init {
        Flux.interval(Duration.ofMillis(LOCK_MONITOR_INTERVAL_MILLIS), Schedulers.single())
            .doOnNext {
                val now = System.currentTimeMillis()
                lockMap.entries.removeIf { now - it.value.createdAt > lockTimeoutMillis }
            }.doOnError { e ->
                log.error("Error in lock lifecycle monitoring : {}", e.message)
            }
            .subscribe()
    }

    override fun tryLock(key: String, lockType: LockType): Mono<Boolean> {
        return Mono.fromCallable {
            val completeKey = "${key}_${lockType.name}"
            val lockInfo = lockMap.computeIfAbsent(completeKey) { LockInfo(Semaphore(1), nowToEpochTime()) }
            lockInfo.semaphore.tryAcquire()
        }
    }

    override fun unLock(key: String, lockType: LockType): Mono<Boolean> {
        return Mono.fromCallable {
            val completeKey = "${key}_${lockType.name}"
            val lockInfo  = lockMap[completeKey]
            lockInfo?.let {
                it.semaphore.release()
                lockMap.remove(completeKey)
            }
        }.thenReturn(true)
    }
}