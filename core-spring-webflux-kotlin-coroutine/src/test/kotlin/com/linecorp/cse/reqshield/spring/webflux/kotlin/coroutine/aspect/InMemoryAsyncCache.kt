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
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap

class InMemoryAsyncCache<T> :
    AsyncCache<T>,
    GlobalLockSupport {
    private data class Entry<T>(val data: ReqShieldData<T>, val expiresAt: Long)

    private data class Lock(val token: String, val expiresAt: Long)

    private val store = ConcurrentHashMap<String, Entry<T>>()
    private val locks = ConcurrentHashMap<String, Lock>()

    /** Name of the thread that ran the most recent write, to tell which scope performed it. */
    @Volatile
    var lastWriterThread: String? = null

    /** [CoroutineName] of the most recent write, for scopes that share a dispatcher. */
    @Volatile
    var lastWriterCoroutineName: String? = null

    override suspend fun get(key: String): ReqShieldData<T>? {
        val now = System.currentTimeMillis()
        return store[key]?.let { e -> if (now <= e.expiresAt) e.data else null }
    }

    override suspend fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Boolean {
        lastWriterThread = Thread.currentThread().name
        lastWriterCoroutineName = currentCoroutineContext()[CoroutineName]?.name
        val expiresAt = System.currentTimeMillis() + timeToLiveMillis
        store[key] = Entry(value, expiresAt)
        return true
    }

    override suspend fun evict(key: String): Boolean = store.remove(key) != null

    /** In-memory equivalent of `SET lockKey token NX PX ttl`: the stored token identifies the owner. */
    override suspend fun globalLock(
        lockKey: String,
        token: String,
        timeToLiveMillis: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        val owner =
            locks.compute(lockKey) { _, current ->
                if (current == null || now > current.expiresAt) {
                    Lock(token, now + timeToLiveMillis)
                } else {
                    current
                }
            }

        return owner?.token == token
    }

    /** In-memory equivalent of the compare-and-delete script: a stale owner cannot release the lock. */
    override suspend fun globalUnLock(
        lockKey: String,
        token: String,
    ): Boolean {
        var released = false
        locks.compute(lockKey) { _, current ->
            if (current?.token == token) {
                released = true
                null
            } else {
                current
            }
        }

        return released
    }
}
