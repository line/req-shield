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

import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A cache and a lock store of one ReqShield instance. */
internal class ProbeStore {
    val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    val locks = ConcurrentHashMap<String, Pair<String, Long>>()
    val lockCalls = AtomicInteger()
    val unlockCalls = AtomicInteger()
    val cacheReads = AtomicInteger()

    fun read(k: String): ReqShieldData<String>? {
        cacheReads.incrementAndGet()
        return cache[k]
    }

    fun lock(
        k: String,
        t: String,
        ttl: Long,
    ): Boolean {
        lockCalls.incrementAndGet()
        val now = System.currentTimeMillis()
        var taken = false
        locks.compute(k) { _, held ->
            if (held == null || held.second <= now) {
                taken = true
                t to now + ttl
            } else {
                held
            }
        }
        return taken
    }

    fun unlock(
        k: String,
        t: String,
    ): Boolean {
        unlockCalls.incrementAndGet()
        var released = false
        locks.computeIfPresent(k) { _, held ->
            if (held.first == t) {
                released = true
                null
            } else {
                held
            }
        }
        return released
    }

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over. */
    fun putOldEntry(key: String) {
        cache[key] = ReqShieldData("old", ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)
    }

    /** A request of another JVM holds the lock of [lockType] for [key]. */
    fun lockByAnotherJvm(
        key: String,
        lockType: String,
    ) {
        locks["reqshield:lock:${key}_$lockType"] = "remote" to System.currentTimeMillis() + 60_000
    }
}

/** One blocking request: the key and the supplier in, the value out. */
internal typealias ProbeCall = (String, () -> String?) -> String?

internal object ProbeShields {
    fun shield(
        store: ProbeStore,
        global: Boolean,
        lockTimeoutMillis: Long = 3000,
        maxAttempt: Int = 60,
        onlyCreate: Boolean = false,
    ): ProbeCall = callOf(instance(store, global, lockTimeoutMillis, maxAttempt, onlyCreate))

    fun instance(
        store: ProbeStore,
        global: Boolean,
        lockTimeoutMillis: Long = 3000,
        maxAttempt: Int = 60,
        onlyCreate: Boolean = false,
    ): ReqShield<String> {
        val mode = if (onlyCreate) ReqShieldWorkMode.ONLY_CREATE_CACHE else ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE
        val s =
            ReqShield(
                if (global) {
                    ReqShieldConfiguration(
                        setCacheFunction = { k, d, _ ->
                            store.cache[k] = d
                            true
                        },
                        getCacheFunction = { k -> store.read(k) },
                        globalLockFunction = { k, t, ttl -> store.lock(k, t, ttl) },
                        globalUnLockFunction = { k, t -> store.unlock(k, t) },
                        isLocalLock = false,
                        lockTimeoutMillis = lockTimeoutMillis,
                        maxAttemptGetCache = maxAttempt,
                        reqShieldWorkMode = mode,
                    )
                } else {
                    ReqShieldConfiguration(
                        setCacheFunction = { k, d, _ ->
                            store.cache[k] = d
                            true
                        },
                        getCacheFunction = { k -> store.read(k) },
                        lockTimeoutMillis = lockTimeoutMillis,
                        maxAttemptGetCache = maxAttempt,
                        reqShieldWorkMode = mode,
                    )
                },
            )
        return s
    }

    fun callOf(s: ReqShield<String>): ProbeCall {
        return { key, supplier ->
            // The supplier blocks, so it runs off the caller's thread
            runBlocking { s.getAndSetReqShieldData(key, { withContext(Dispatchers.IO) { supplier() } }, 10_000) }.value
        }
    }

    /** The creation signal that requests of [s] wait for, and whether it is still open. */
    fun openCreationSignalOf(
        s: ReqShield<String>,
        key: String,
    ): Any? = s.creationSignals.find(key)?.takeIf { !it.isCompleted }

    /** Runs [n] requests at the same time. Returns the value and the time in ms of each. */
    fun burst(
        n: Int,
        call: (Int) -> String?,
    ): List<Pair<String?, Long>> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val futures =
            (0 until n).map { i ->
                pool.submit<Pair<String?, Long>> {
                    start.await()
                    val begin = System.nanoTime()
                    val v =
                        try {
                            call(i)
                        } catch (e: Exception) {
                            "ERR:" + e.javaClass.simpleName
                        }
                    v to (System.nanoTime() - begin) / 1_000_000
                }
            }
        start.countDown()
        try {
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
