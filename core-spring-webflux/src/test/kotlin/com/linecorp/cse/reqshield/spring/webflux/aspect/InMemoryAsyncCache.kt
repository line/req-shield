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

package com.linecorp.cse.reqshield.spring.webflux.aspect

import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

class InMemoryAsyncCache<T> : AsyncCache<T> {
    private data class Entry<T>(val data: ReqShieldData<T>, val expiresAt: Long)

    private val store = ConcurrentHashMap<String, Entry<T>>()
    private val locks = ConcurrentHashMap<String, Semaphore>()

    override fun get(key: String): Mono<ReqShieldData<T>?> =
        Mono.fromCallable {
            val now = System.currentTimeMillis()
            store[key]?.let { e -> if (now <= e.expiresAt) e.data else null }
        }

    override fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Mono<Boolean> =
        Mono.fromCallable {
            val expiresAt = System.currentTimeMillis() + timeToLiveMillis
            store[key] = Entry(value, expiresAt)
            true
        }

    override fun evict(key: String): Mono<Boolean> = Mono.fromCallable { store.remove(key) != null }

    override fun globalLock(
        key: String,
        timeToLiveMillis: Long,
    ): Mono<Boolean> =
        Mono.fromCallable {
            locks.computeIfAbsent(key) { Semaphore(1) }.tryAcquire()
        }

    override fun globalUnLock(key: String): Mono<Boolean> =
        Mono.fromCallable {
            locks[key]?.release()
            true
        }
}
