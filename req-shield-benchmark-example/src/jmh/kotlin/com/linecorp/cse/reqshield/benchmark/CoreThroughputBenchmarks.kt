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

import com.linecorp.cse.reqshield.KeyLocalLock
import com.linecorp.cse.reqshield.LockType
import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val KEY_COUNT = 1024

/** Walks over the shared, pre-built keys. Each thread has its own position. */
@State(Scope.Thread)
class KeyCursor {
    private var index = nextThreadId() * 31

    fun next(): Int {
        index = (index + 1) and (KEY_COUNT - 1)
        return index
    }
}

/** Pre-built keys that only this thread uses. */
@State(Scope.Thread)
class OwnKeys {
    private val keys = nextThreadId().let { id -> Array(KEY_COUNT) { "t$id-k$it" } }
    private var index = 0

    fun next(): String {
        index = (index + 1) and (KEY_COUNT - 1)
        return keys[index]
    }
}

/** A key that no call has used before. Building the string is part of every arm that takes this state. */
@State(Scope.Thread)
class FreshKeys {
    private val id = nextThreadId()
    private var sequence = 0L

    fun next(): String = "t$id-f${sequence++}"
}

/** Cache hit on fresh data: no lock, no supplier, no background task. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class HitBenchmark {
    private val keys = Array(KEY_COUNT) { "product:$it" }
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val supplier = Callable<String?> { "value" }
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var shield: ReqShield<String>
    private lateinit var aside: CacheAside<String>

    @Setup(Level.Trial)
    fun setup() {
        keys.forEach { cache[it] = ReqShieldData("value", TTL_MILLIS) }
        executor = libraryLikeExecutor()
        val get: (String) -> ReqShieldData<String>? = { cache[it] }
        val set: (String, ReqShieldData<String>, Long) -> Boolean = { key, data, _ ->
            cache[key] = data
            true
        }
        shield = ReqShield(ReqShieldConfiguration(setCacheFunction = set, getCacheFunction = get, executor = executor))
        aside = CacheAside(get, set)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        executor.shutdownNow()
    }

    @Benchmark
    fun rawMapGet(cursor: KeyCursor): ReqShieldData<String>? = cache[keys[cursor.next()]]

    @Benchmark
    fun cacheAside(cursor: KeyCursor): ReqShieldData<String> = aside.get(keys[cursor.next()], supplier, TTL_MILLIS)

    @Benchmark
    fun reqShield(cursor: KeyCursor): ReqShieldData<String> = shield.getAndSetReqShieldData(keys[cursor.next()], supplier, TTL_MILLIS)
}

/**
 * Cache hit on one hot key that is past the refresh point (80 % of the TTL) while a refresh is running.
 * The cache write is dropped, so the entry stays stale and every call takes the refresh branch.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class StaleHitBenchmark {
    private val staleKey = "stale-hot"
    private val freshKey = "fresh-hot"
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()

    // The refresh supplier takes 10 ms, so one refresh is in flight for most calls.
    private val refreshes = AtomicInteger()
    private val supplier =
        Callable<String?> {
            refreshes.incrementAndGet()
            Thread.sleep(10)
            "value"
        }
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var shield: ReqShield<String>

    @Setup(Level.Trial)
    fun setup() {
        val now = System.currentTimeMillis()
        cache[staleKey] = ReqShieldData("value", ReqShieldData.Status.NEW, now - TTL_MILLIS * 9 / 10, TTL_MILLIS)
        cache[freshKey] = ReqShieldData("value", ReqShieldData.Status.NEW, now, TTL_MILLIS)
        executor = libraryLikeExecutor()
        shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { _, _, _ -> true },
                    getCacheFunction = { cache[it] },
                    executor = executor,
                ),
            )
    }

    /** Records how many refreshes ran, to show that a fast stale hit is not a refresh that stopped. */
    @TearDown(Level.Iteration)
    fun drain(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) {
        awaitQuiescence(executor)
        Events.write(
            """{"event":"refreshes","benchmark":"${benchmark.benchmark}","threads":${benchmark.threads},""" +
                """"iteration":"${iteration.type}","supplierCalls":${refreshes.getAndSet(0)}}""",
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        executor.shutdownNow()
    }

    @Benchmark
    fun freshHitOneKey(): ReqShieldData<String> = shield.getAndSetReqShieldData(freshKey, supplier, TTL_MILLIS)

    @Benchmark
    fun staleHitOneKey(): ReqShieldData<String> = shield.getAndSetReqShieldData(staleKey, supplier, TTL_MILLIS)
}

/**
 * Cache miss on a key that no other call uses: request collapsing cannot help, so this is the cost that
 * req-shield adds to a miss. The cache is empty and drops writes, so memory does not grow with the key count.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class MissBenchmark {
    private val supplier = Callable<String?> { "value" }
    private val get: (String) -> ReqShieldData<String>? = { null }
    private val set: (String, ReqShieldData<String>, Long) -> Boolean = { _, _, _ -> true }
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var pooledShield: ReqShield<String>
    private lateinit var directShield: ReqShield<String>
    private val aside = CacheAside(get, set)

    @Setup(Level.Trial)
    fun setup() {
        executor = libraryLikeExecutor()
        pooledShield = ReqShield(ReqShieldConfiguration(setCacheFunction = set, getCacheFunction = get, executor = executor))
        // Runs the cache write and the unlock on the calling thread: the whole work of one miss, with no hand-off.
        directShield =
            ReqShield(
                ReqShieldConfiguration(setCacheFunction = set, getCacheFunction = get, executor = Executor { it.run() }),
            )
    }

    /** The caller returns before the background write runs, so record how much work the iteration left behind. */
    @TearDown(Level.Iteration)
    fun recordBacklog(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) {
        val queued = executor.queue.size
        val drainMillis = awaitQuiescence(executor)
        Events.write(
            """{"event":"backlog","benchmark":"${benchmark.benchmark}","threads":${benchmark.threads},""" +
                """"iteration":"${iteration.type}","queuedAtEnd":$queued,"drainMillis":$drainMillis}""",
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        executor.shutdownNow()
    }

    @Benchmark
    fun keyOnly(keys: FreshKeys): String = keys.next()

    @Benchmark
    fun cacheAside(keys: FreshKeys): ReqShieldData<String> = aside.get(keys.next(), supplier, TTL_MILLIS)

    @Benchmark
    fun reqShieldPooledWrite(keys: FreshKeys): ReqShieldData<String> =
        pooledShield.getAndSetReqShieldData(keys.next(), supplier, TTL_MILLIS)

    @Benchmark
    fun reqShieldDirectWrite(keys: FreshKeys): ReqShieldData<String> =
        directShield.getAndSetReqShieldData(keys.next(), supplier, TTL_MILLIS)
}

/** The local lock alone. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class LockBenchmark {
    // One hour, so the lock taken in setup does not expire during the run.
    private val lock = KeyLocalLock(60 * 60 * 1000L)
    private val heldKey = "held-by-setup"
    private val plainMap = ConcurrentHashMap<String, String>()

    /**
     * Entries that stay in the maps for the whole run. With 0 the maps are almost empty, as in production,
     * where an entry lives only while a lock is held. A large count makes the hash table large, which shows
     * how much of the cost comes from many threads sharing a small table.
     */
    @Param("0")
    @JvmField
    final var residentEntries: Int = 0

    @Setup(Level.Trial)
    fun setup() {
        checkNotNull(lock.tryLock(heldKey, LockType.CREATE))
        repeat(residentEntries) {
            checkNotNull(lock.tryLock("resident-$it", LockType.UPDATE))
            plainMap["resident-$it"] = "token"
        }
    }

    /** Reference: the cheapest per-key mutual exclusion on the same map type. */
    @Benchmark
    fun plainMapPutIfAbsentRemove(keys: OwnKeys): Boolean {
        val key = keys.next()
        plainMap.putIfAbsent(key, "token")
        return plainMap.remove(key, "token")
    }

    @Benchmark
    fun lockThenUnlockOwnKeys(keys: OwnKeys): Boolean {
        val key = keys.next()
        val token = lock.tryLock(key, LockType.CREATE)!!
        return lock.unLock(key, LockType.CREATE, token)
    }

    /** What every losing request pays: all threads try the one key that another holder owns. */
    @Benchmark
    fun tryLockOnHeldKey(): String? = lock.tryLock(heldKey, LockType.CREATE)
}
