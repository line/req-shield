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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.aspect

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

class InMemoryAsyncCache<T> : AsyncCache<T> {
    private data class Entry<T>(val data: ReqShieldData<T>, val expiresAt: Long)

    private val store = ConcurrentHashMap<String, Entry<T>>()
    private val locks = ConcurrentHashMap<String, Semaphore>()

    override suspend fun get(key: String): ReqShieldData<T>? {
        val now = System.currentTimeMillis()
        return store[key]?.let { e -> if (now <= e.expiresAt) e.data else null }
    }

    override suspend fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Boolean {
        val expiresAt = System.currentTimeMillis() + timeToLiveMillis
        store[key] = Entry(value, expiresAt)
        return true
    }

    override suspend fun evict(key: String): Boolean = store.remove(key) != null

    override suspend fun globalLock(
        key: String,
        timeToLiveMillis: Long,
    ): Boolean = locks.computeIfAbsent(key) { Semaphore(1) }.tryAcquire()

    override suspend fun globalUnLock(key: String): Boolean {
        locks[key]?.release()
        return true
    }
}
