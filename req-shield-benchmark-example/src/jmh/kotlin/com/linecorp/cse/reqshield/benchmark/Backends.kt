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

package com.linecorp.cse.reqshield.benchmark

import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirstOrNull
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How often the scenario asked the cache and the lock store. */
class StoreCalls {
    val cacheReads = AtomicInteger()
    val cacheWrites = AtomicInteger()
    val lockCalls = AtomicInteger()

    fun reset() {
        cacheReads.set(0)
        cacheWrites.set(0)
        lockCalls.set(0)
    }
}

/**
 * The cache and the lock store of a scenario, in the three call styles of the core modules.
 * An entry is gone after its time to live, as in a cache with expiry. The lock is the global lock contract of
 * req-shield: take it only if the key is absent, let it expire, and release it only with the token that took it.
 */
interface Backend {
    fun get(key: String): ReqShieldData<String>?

    fun set(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean

    fun lock(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean

    fun unlock(
        lockKey: String,
        token: String,
    ): Boolean

    fun getMono(key: String): Mono<ReqShieldData<String>?>

    fun setMono(
        key: String,
        data: ReqShieldData<String>,
    ): Mono<Boolean>

    fun lockMono(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Mono<Boolean>

    fun unlockMono(
        lockKey: String,
        token: String,
    ): Mono<Boolean>

    suspend fun getSuspend(key: String): ReqShieldData<String>?

    suspend fun setSuspend(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean

    suspend fun lockSuspend(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean

    suspend fun unlockSuspend(
        lockKey: String,
        token: String,
    ): Boolean

    /** Removes all entries and locks. */
    fun clear()

    fun close() {}
}

/**
 * A cache and a lock store in the JVM. `setMillis` is the time of one cache write and `lockMillis` the time of one
 * lock or unlock call: 0 models a store in the process, 1 a store behind a network call.
 */
class MemoryBackend(
    private val calls: StoreCalls,
    private val setMillis: Long,
    private val lockMillis: Long,
) : Backend {
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()

    /** Lock key to (token, expiry in epoch milliseconds). */
    private val locks = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun read(key: String): ReqShieldData<String>? {
        calls.cacheReads.incrementAndGet()
        return cache[key]?.takeIf { System.currentTimeMillis() < it.createdAt + it.timeToLiveMillis }
    }

    private fun write(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean {
        cache[key] = data
        calls.cacheWrites.incrementAndGet()
        return true
    }

    private fun take(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean {
        calls.lockCalls.incrementAndGet()
        val now = System.currentTimeMillis()
        var taken = false
        locks.compute(lockKey) { _, held ->
            if (held == null || held.second <= now) {
                taken = true
                token to now + ttlMillis
            } else {
                held
            }
        }
        return taken
    }

    private fun release(
        lockKey: String,
        token: String,
    ): Boolean {
        calls.lockCalls.incrementAndGet()
        var released = false
        locks.computeIfPresent(lockKey) { _, held ->
            if (held.first == token) {
                released = true
                null
            } else {
                held
            }
        }
        return released
    }

    override fun get(key: String) = read(key)

    override fun set(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean {
        if (setMillis > 0) Thread.sleep(setMillis)
        return write(key, data)
    }

    override fun lock(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean {
        if (lockMillis > 0) Thread.sleep(lockMillis)
        return take(lockKey, token, ttlMillis)
    }

    override fun unlock(
        lockKey: String,
        token: String,
    ): Boolean {
        if (lockMillis > 0) Thread.sleep(lockMillis)
        return release(lockKey, token)
    }

    private fun <R : Any> after(
        millis: Long,
        action: () -> R?,
    ): Mono<R> = if (millis > 0) Mono.delay(Duration.ofMillis(millis)).mapNotNull { action() } else Mono.fromCallable { action() }

    override fun getMono(key: String): Mono<ReqShieldData<String>?> = Mono.fromCallable { read(key) }

    override fun setMono(
        key: String,
        data: ReqShieldData<String>,
    ): Mono<Boolean> = after(setMillis) { write(key, data) }

    override fun lockMono(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Mono<Boolean> = after(lockMillis) { take(lockKey, token, ttlMillis) }

    override fun unlockMono(
        lockKey: String,
        token: String,
    ): Mono<Boolean> = after(lockMillis) { release(lockKey, token) }

    override suspend fun getSuspend(key: String) = read(key)

    override suspend fun setSuspend(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean {
        if (setMillis > 0) delay(setMillis)
        return write(key, data)
    }

    override suspend fun lockSuspend(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean {
        if (lockMillis > 0) delay(lockMillis)
        return take(lockKey, token, ttlMillis)
    }

    override suspend fun unlockSuspend(
        lockKey: String,
        token: String,
    ): Boolean {
        if (lockMillis > 0) delay(lockMillis)
        return release(lockKey, token)
    }

    override fun clear() {
        cache.clear()
        locks.clear()
    }
}

/**
 * A Redis server as the cache and the lock store. An entry is one string, `createdAt|timeToLive|value`, set with
 * its time to live. The lock is `SET key token NX PX ttl`, and the release is a script that deletes the key only
 * while its value is the token.
 */
class RedisBackend(
    private val calls: StoreCalls,
    url: String,
) : Backend {
    private val client: RedisClient = RedisClient.create(url)
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val sync = connection.sync()
    private val reactive = connection.reactive()

    private fun encode(data: ReqShieldData<String>) = "${data.createdAt}|${data.timeToLiveMillis}|${data.value}"

    private fun decode(text: String): ReqShieldData<String> {
        val parts = text.split('|', limit = 3)
        return ReqShieldData(parts[2], ReqShieldData.Status.NEW, parts[0].toLong(), parts[1].toLong())
    }

    private fun entryArgs(data: ReqShieldData<String>) = SetArgs.Builder.px(data.timeToLiveMillis)

    private fun lockArgs(ttlMillis: Long) = SetArgs.Builder.nx().px(ttlMillis)

    override fun get(key: String): ReqShieldData<String>? {
        calls.cacheReads.incrementAndGet()
        return sync.get(key)?.let(::decode)
    }

    override fun set(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean {
        sync.set(key, encode(data), entryArgs(data))
        calls.cacheWrites.incrementAndGet()
        return true
    }

    override fun lock(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean {
        calls.lockCalls.incrementAndGet()
        return sync.set(lockKey, token, lockArgs(ttlMillis)) == "OK"
    }

    override fun unlock(
        lockKey: String,
        token: String,
    ): Boolean {
        calls.lockCalls.incrementAndGet()
        return sync.eval<Long>(RELEASE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token) == 1L
    }

    override fun getMono(key: String): Mono<ReqShieldData<String>?> =
        Mono.defer {
            calls.cacheReads.incrementAndGet()
            reactive.get(key).map(::decode)
        }

    override fun setMono(
        key: String,
        data: ReqShieldData<String>,
    ): Mono<Boolean> =
        reactive.set(key, encode(data), entryArgs(data)).map {
            calls.cacheWrites.incrementAndGet()
            true
        }

    override fun lockMono(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Mono<Boolean> =
        Mono.defer {
            calls.lockCalls.incrementAndGet()
            // SET NX answers nothing when the key exists, so the Mono is empty then
            reactive.set(lockKey, token, lockArgs(ttlMillis)).map { it == "OK" }.defaultIfEmpty(false)
        }

    override fun unlockMono(
        lockKey: String,
        token: String,
    ): Mono<Boolean> =
        Mono.defer {
            calls.lockCalls.incrementAndGet()
            reactive
                .eval<Long>(RELEASE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token)
                .next()
                .map { it == 1L }
                .defaultIfEmpty(false)
        }

    override suspend fun getSuspend(key: String): ReqShieldData<String>? = getMono(key).awaitFirstOrNull()

    override suspend fun setSuspend(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean = setMono(key, data).awaitFirstOrNull() ?: false

    override suspend fun lockSuspend(
        lockKey: String,
        token: String,
        ttlMillis: Long,
    ): Boolean = lockMono(lockKey, token, ttlMillis).awaitFirstOrNull() ?: false

    override suspend fun unlockSuspend(
        lockKey: String,
        token: String,
    ): Boolean = unlockMono(lockKey, token).awaitFirstOrNull() ?: false

    override fun clear() {
        sync.flushdb()
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private companion object {
        const val RELEASE_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}
