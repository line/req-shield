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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

typealias ReactorReqShield<T> = com.linecorp.cse.reqshield.reactor.ReqShield<T>
typealias ReactorConfig<T> = com.linecorp.cse.reqshield.reactor.config.ReqShieldConfiguration<T>
typealias CoroutineReqShield<T> = com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield<T>
typealias CoroutineConfig<T> = com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration<T>

private const val KEY_COUNT = 1024

/** The reactor arm without req-shield: read the cache, and on a miss call the supplier and write the cache. */
class ReactorCacheAside<T : Any>(
    private val getCache: (String) -> Mono<ReqShieldData<T>?>,
    private val setCache: (String, ReqShieldData<T>, Long) -> Mono<Boolean>,
) {
    fun get(
        key: String,
        supplier: Callable<Mono<T?>>,
        timeToLiveMillis: Long,
    ): Mono<ReqShieldData<T>> =
        getCache(key)
            .map { it!! }
            .switchIfEmpty(
                Mono.defer {
                    supplier.call().flatMap { value ->
                        val data = ReqShieldData<T>(value, timeToLiveMillis)
                        setCache(key, data, timeToLiveMillis).thenReturn(data)
                    }
                },
            )
}

/** The coroutine arm without req-shield. */
class CoroutineCacheAside<T>(
    private val getCache: suspend (String) -> ReqShieldData<T>?,
    private val setCache: suspend (String, ReqShieldData<T>, Long) -> Boolean,
) {
    suspend fun get(
        key: String,
        supplier: suspend () -> T?,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        getCache(key)?.let { return it }
        val data = ReqShieldData(supplier(), timeToLiveMillis)
        setCache(key, data, timeToLiveMillis)
        return data
    }
}

/**
 * Counts the calls and the cache writes of a miss benchmark. Each call ends in one cache write, but req-shield
 * runs it in the background, so the difference at the end of an iteration is the work the callers left behind.
 */
class WriteBacklog {
    val calls = LongAdder()
    val writes = LongAdder()

    fun record(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) {
        val pending = calls.sum() - writes.sum()
        val start = System.nanoTime()
        val deadline = start + TimeUnit.SECONDS.toNanos(30)
        while (writes.sum() < calls.sum() && System.nanoTime() < deadline) Thread.sleep(1)
        val lost = calls.sum() - writes.sum()
        Events.write(
            """{"event":"backlog","benchmark":"${benchmark.benchmark}","threads":${benchmark.threads},""" +
                """"iteration":"${iteration.type}","pendingWritesAtEnd":$pending,""" +
                """"drainMillis":${(System.nanoTime() - start) / 1_000_000},"writesNeverDone":$lost}""",
        )
        // The unlock runs just after the write, on the same background thread.
        Thread.sleep(5)
        calls.reset()
        writes.reset()
    }
}

private fun freshEntry() = ReqShieldData("value", TTL_MILLIS)

private fun staleEntry() = ReqShieldData("value", ReqShieldData.Status.NEW, System.currentTimeMillis() - TTL_MILLIS * 9 / 10, TTL_MILLIS)

// ---------------------------------------------------------------------------------------------------------------
// core-reactor. Every arm ends in block(), so the bridge to the blocking benchmark thread costs the same in each.
// ---------------------------------------------------------------------------------------------------------------

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class ReactorHitBenchmark {
    private val keys = Array(KEY_COUNT) { "product:$it" }
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val supplier = Callable<Mono<String?>> { Mono.just("value") }
    private val get: (String) -> Mono<ReqShieldData<String>?> = { key -> Mono.justOrEmpty(cache[key]) }
    private val set: (String, ReqShieldData<String>, Long) -> Mono<Boolean> = { _, _, _ -> Mono.just(true) }
    private val shield = ReactorReqShield(ReactorConfig(setCacheFunction = set, getCacheFunction = get))
    private val aside = ReactorCacheAside(get, set)

    @Setup(Level.Trial)
    fun setup() {
        keys.forEach { cache[it] = freshEntry() }
    }

    @Benchmark
    fun cacheAside(cursor: KeyCursor): ReqShieldData<String>? = aside.get(keys[cursor.next()], supplier, TTL_MILLIS).block()

    @Benchmark
    fun reqShield(cursor: KeyCursor): ReqShieldData<String>? =
        shield.getAndSetReqShieldData(keys[cursor.next()], supplier, TTL_MILLIS).block()
}

/** One hot key past the refresh point while a 10 ms refresh is running. The write is dropped, so it stays stale. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class ReactorStaleHitBenchmark {
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val refreshes = AtomicInteger()
    private val supplier =
        Callable<Mono<String?>> {
            Mono.defer {
                refreshes.incrementAndGet()
                Mono.delay(Duration.ofMillis(10)).map { "value" }
            }
        }
    private val shield =
        ReactorReqShield(
            ReactorConfig(
                setCacheFunction = { _: String, _: ReqShieldData<String>, _: Long -> Mono.just(true) },
                getCacheFunction = { key: String -> Mono.justOrEmpty(cache[key]) },
            ),
        )

    private val immediateShield =
        ReactorReqShield(
            ReactorConfig(
                setCacheFunction = { _: String, _: ReqShieldData<String>, _: Long -> Mono.just(true) },
                getCacheFunction = { key: String -> Mono.justOrEmpty(cache[key]) },
                scheduler = Schedulers.immediate(),
            ),
        )

    @Setup(Level.Trial)
    fun setup() {
        cache["reactor-stale-hot-immediate"] = staleEntry()
        cache["reactor-stale-hot"] = staleEntry()
        cache["reactor-fresh-hot"] = freshEntry()
    }

    /** Records how many refreshes ran, to show that a fast stale hit is not a refresh that stopped. */
    @TearDown(Level.Iteration)
    fun settle(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) {
        // Lets the refresh tasks that the iteration queued finish before the next iteration starts.
        Thread.sleep(200)
        Events.write(
            """{"event":"refreshes","benchmark":"${benchmark.benchmark}","threads":${benchmark.threads},""" +
                """"iteration":"${iteration.type}","supplierCalls":${refreshes.getAndSet(0)}}""",
        )
    }

    @Benchmark
    fun freshHitOneKey(): ReqShieldData<String>? = shield.getAndSetReqShieldData("reactor-fresh-hot", supplier, TTL_MILLIS).block()

    @Benchmark
    fun staleHitOneKey(): ReqShieldData<String>? = shield.getAndSetReqShieldData("reactor-stale-hot", supplier, TTL_MILLIS).block()

    /** Same call with no thread hand-off: the refresh chain runs on the calling thread. */
    @Benchmark
    fun staleHitOneKeyImmediateScheduler(): ReqShieldData<String>? =
        immediateShield.getAndSetReqShieldData("reactor-stale-hot-immediate", supplier, TTL_MILLIS).block()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class ReactorMissBenchmark {
    private val backlog = WriteBacklog()
    private val supplier = Callable<Mono<String?>> { Mono.just("value") }
    private val get: (String) -> Mono<ReqShieldData<String>?> = { Mono.empty() }
    private val set: (String, ReqShieldData<String>, Long) -> Mono<Boolean> = { _, _, _ ->
        Mono.fromCallable {
            backlog.writes.increment()
            true
        }
    }
    private val shield = ReactorReqShield(ReactorConfig(setCacheFunction = set, getCacheFunction = get))
    private val immediateShield =
        ReactorReqShield(ReactorConfig(setCacheFunction = set, getCacheFunction = get, scheduler = Schedulers.immediate()))
    private val aside = ReactorCacheAside(get, set)

    @TearDown(Level.Iteration)
    fun recordBacklog(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) = backlog.record(benchmark, iteration)

    @Benchmark
    fun cacheAside(keys: FreshKeys): ReqShieldData<String>? {
        backlog.calls.increment()
        return aside.get(keys.next(), supplier, TTL_MILLIS).block()
    }

    @Benchmark
    fun reqShield(keys: FreshKeys): ReqShieldData<String>? {
        backlog.calls.increment()
        return shield.getAndSetReqShieldData(keys.next(), supplier, TTL_MILLIS).block()
    }

    /** Same call with no thread hand-off: the re-check read and the cache write run on the calling thread. */
    @Benchmark
    fun reqShieldImmediateScheduler(keys: FreshKeys): ReqShieldData<String>? {
        backlog.calls.increment()
        return immediateShield.getAndSetReqShieldData(keys.next(), supplier, TTL_MILLIS).block()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// core-kotlin-coroutine. Every arm runs in runBlocking, so the bridge costs the same in each.
// ---------------------------------------------------------------------------------------------------------------

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class CoroutineHitBenchmark {
    private val keys = Array(KEY_COUNT) { "product:$it" }
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val supplier: suspend () -> String? = { "value" }
    private val get: suspend (String) -> ReqShieldData<String>? = { cache[it] }
    private val set: suspend (String, ReqShieldData<String>, Long) -> Boolean = { _, _, _ -> true }
    private val shield = CoroutineReqShield(CoroutineConfig(setCacheFunction = set, getCacheFunction = get))
    private val aside = CoroutineCacheAside(get, set)

    @Setup(Level.Trial)
    fun setup() {
        keys.forEach { cache[it] = freshEntry() }
    }

    @Benchmark
    fun cacheAside(cursor: KeyCursor): ReqShieldData<String> = runBlocking { aside.get(keys[cursor.next()], supplier, TTL_MILLIS) }

    @Benchmark
    fun reqShield(cursor: KeyCursor): ReqShieldData<String> =
        runBlocking { shield.getAndSetReqShieldData(keys[cursor.next()], supplier, TTL_MILLIS) }
}

/** One hot key past the refresh point while a 10 ms refresh is running. The write is dropped, so it stays stale. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class CoroutineStaleHitBenchmark {
    private val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val refreshes = AtomicInteger()
    private val supplier: suspend () -> String? = {
        refreshes.incrementAndGet()
        delay(10)
        "value"
    }
    private val shield =
        CoroutineReqShield(
            CoroutineConfig(
                setCacheFunction = { _: String, _: ReqShieldData<String>, _: Long -> true },
                getCacheFunction = { key: String -> cache[key] },
            ),
        )

    @Setup(Level.Trial)
    fun setup() {
        cache["coroutine-stale-hot"] = staleEntry()
        cache["coroutine-fresh-hot"] = freshEntry()
    }

    /** Records how many refreshes ran, to show that a fast stale hit is not a refresh that stopped. */
    @TearDown(Level.Iteration)
    fun settle(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) {
        // Lets the refresh that the iteration started finish before the next iteration starts.
        Thread.sleep(200)
        Events.write(
            """{"event":"refreshes","benchmark":"${benchmark.benchmark}","threads":${benchmark.threads},""" +
                """"iteration":"${iteration.type}","supplierCalls":${refreshes.getAndSet(0)}}""",
        )
    }

    @Benchmark
    fun freshHitOneKey(): ReqShieldData<String> = runBlocking { shield.getAndSetReqShieldData("coroutine-fresh-hot", supplier, TTL_MILLIS) }

    @Benchmark
    fun staleHitOneKey(): ReqShieldData<String> = runBlocking { shield.getAndSetReqShieldData("coroutine-stale-hot", supplier, TTL_MILLIS) }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class CoroutineMissBenchmark {
    private val backlog = WriteBacklog()
    private val supplier: suspend () -> String? = { "value" }
    private val get: suspend (String) -> ReqShieldData<String>? = { null }
    private val set: suspend (String, ReqShieldData<String>, Long) -> Boolean = { _, _, _ ->
        backlog.writes.increment()
        true
    }
    private val shield = CoroutineReqShield(CoroutineConfig(setCacheFunction = set, getCacheFunction = get))
    private val unconfinedShield =
        CoroutineReqShield(
            CoroutineConfig(
                setCacheFunction = set,
                getCacheFunction = get,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ),
        )
    private val aside = CoroutineCacheAside(get, set)

    @TearDown(Level.Iteration)
    fun recordBacklog(
        benchmark: BenchmarkParams,
        iteration: IterationParams,
    ) = backlog.record(benchmark, iteration)

    @Benchmark
    fun cacheAside(keys: FreshKeys): ReqShieldData<String> {
        backlog.calls.increment()
        return runBlocking { aside.get(keys.next(), supplier, TTL_MILLIS) }
    }

    @Benchmark
    fun reqShield(keys: FreshKeys): ReqShieldData<String> {
        backlog.calls.increment()
        return runBlocking { shield.getAndSetReqShieldData(keys.next(), supplier, TTL_MILLIS) }
    }

    /** Same call with no thread hand-off: the cache write runs on the calling thread. */
    @Benchmark
    fun reqShieldUnconfinedScope(keys: FreshKeys): ReqShieldData<String> {
        backlog.calls.increment()
        return runBlocking { unconfinedShield.getAndSetReqShieldData(keys.next(), supplier, TTL_MILLIS) }
    }
}
