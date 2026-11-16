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

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The parts that the two scenario benchmarks share: the cache, the arms and the supplier call count. */
@State(Scope.Benchmark)
abstract class ScenarioState {
    /** The req-shield module under test: core (blocking), reactor or coroutine. */
    @Param("core")
    @JvmField
    final var module: String = ""

    @Param("cacheAside", "reqShield")
    @JvmField
    final var arm: String = ""

    @Param("1", "10", "100")
    @JvmField
    final var supplierMillis: Long = 0

    /** Time of one cache write. 0 models an in-process cache, 1 models a cache behind a network call. */
    @Param("0")
    @JvmField
    final var setMillis: Long = 0

    protected val cache = ConcurrentHashMap<String, ReqShieldData<String>>()
    protected val supplierCalls = AtomicInteger()

    /** Time of one supplier call. A subclass can make it random. */
    protected fun supplierDelayMillis(): Long = if (supplierMaxMillis > 0) randomSupplierMillis() else supplierMillis

    /** Time to live of the entries that the arms write. */
    protected val timeToLive: Long
        get() = if (entryTtlMillis > 0) entryTtlMillis else TTL_MILLIS

    /** Set by the expiry benchmark: supplier time is uniform in [supplierMinMillis, supplierMaxMillis]. */
    protected var supplierMinMillis = 0L
    protected var supplierMaxMillis = 0L
    protected var entryTtlMillis = 0L
    protected val supplierMillisTotal = AtomicLong()

    private fun randomSupplierMillis(): Long =
        ThreadLocalRandom.current().nextLong(supplierMinMillis, supplierMaxMillis + 1).also { supplierMillisTotal.addAndGet(it) }

    /** Reads like a cache with expiry (Redis, for example): an entry older than its time to live is gone. */
    private fun read(key: String): ReqShieldData<String>? =
        cache[key]?.takeIf { System.currentTimeMillis() < it.createdAt + it.timeToLiveMillis }

    private val cacheWrites = AtomicInteger()
    protected lateinit var executor: ScheduledThreadPoolExecutor
    protected lateinit var call: (String) -> ReqShieldData<String>
    protected var measuring = false
    private var keySequence = 0

    private fun store(
        key: String,
        data: ReqShieldData<String>,
    ): Boolean {
        cache[key] = data
        cacheWrites.incrementAndGet()
        return true
    }

    protected fun setupArms() {
        executor = libraryLikeExecutor()
        val useReqShield = arm == "reqShield"
        call =
            when (module) {
                "core" -> coreCall(useReqShield)
                "reactor" -> reactorCall(useReqShield)
                "coroutine" -> coroutineCall(useReqShield)
                else -> error("unknown module $module")
            }
    }

    private fun coreCall(useReqShield: Boolean): (String) -> ReqShieldData<String> {
        val supplier =
            Callable<String?> {
                supplierCalls.incrementAndGet()
                Thread.sleep(supplierDelayMillis())
                "value"
            }
        val get: (String) -> ReqShieldData<String>? = { read(it) }
        val set: (String, ReqShieldData<String>, Long) -> Boolean = { key, data, _ ->
            if (setMillis > 0) Thread.sleep(setMillis)
            store(key, data)
        }
        if (!useReqShield) {
            val aside = CacheAside(get, set)
            return { key -> aside.get(key, supplier, timeToLive) }
        }
        val shield = ReqShield(ReqShieldConfiguration(setCacheFunction = set, getCacheFunction = get, executor = executor))
        return { key -> shield.getAndSetReqShieldData(key, supplier, timeToLive) }
    }

    /** Both reactor arms end in block(), so the bridge to the blocking caller costs the same in each. */
    private fun reactorCall(useReqShield: Boolean): (String) -> ReqShieldData<String> {
        val supplier =
            Callable<Mono<String?>> {
                Mono.defer {
                    supplierCalls.incrementAndGet()
                    Mono.delay(Duration.ofMillis(supplierDelayMillis())).map { "value" }
                }
            }
        val get: (String) -> Mono<ReqShieldData<String>?> = { key -> Mono.fromCallable { read(key) } }
        val set: (String, ReqShieldData<String>, Long) -> Mono<Boolean> = { key, data, _ ->
            Mono.delay(Duration.ofMillis(setMillis)).map { store(key, data) }
        }
        if (!useReqShield) {
            val aside = ReactorCacheAside(get, set)
            return { key -> aside.get(key, supplier, timeToLive).block()!! }
        }
        val shield = ReactorReqShield(ReactorConfig(setCacheFunction = set, getCacheFunction = get))
        return { key -> shield.getAndSetReqShieldData(key, supplier, timeToLive).block()!! }
    }

    /** Both coroutine arms run in runBlocking, so the bridge to the blocking caller costs the same in each. */
    private fun coroutineCall(useReqShield: Boolean): (String) -> ReqShieldData<String> {
        val supplier: suspend () -> String? = {
            supplierCalls.incrementAndGet()
            delay(supplierDelayMillis())
            "value"
        }
        val get: suspend (String) -> ReqShieldData<String>? = { read(it) }
        val set: suspend (String, ReqShieldData<String>, Long) -> Boolean = { key, data, _ ->
            if (setMillis > 0) delay(setMillis)
            store(key, data)
        }
        if (!useReqShield) {
            val aside = CoroutineCacheAside(get, set)
            return { key -> runBlocking { aside.get(key, supplier, timeToLive) } }
        }
        val shield = CoroutineReqShield(CoroutineConfig(setCacheFunction = set, getCacheFunction = get))
        return { key -> runBlocking { shield.getAndSetReqShieldData(key, supplier, timeToLive) } }
    }

    protected fun freshKey(): String = "$module-$arm-$supplierMillis-$setMillis-${keySequence++}"

    /** Each single-shot iteration is one scenario run. It starts with no background work left from the last run. */
    @Setup(Level.Iteration)
    fun beforeRun(iteration: IterationParams) {
        measuring = iteration.type == IterationType.MEASUREMENT
        // In these scenarios each supplier call is followed by one cache write, on some background thread.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cacheWrites.get() < supplierCalls.get() && System.nanoTime() < deadline) Thread.sleep(1)
        check(cacheWrites.get() >= supplierCalls.get()) { "background cache writes of the last run did not finish" }
        awaitQuiescence(executor)
        // The unlock runs just after the write, on the same background thread.
        Thread.sleep(5)
        cache.clear()
        supplierCalls.set(0)
        cacheWrites.set(0)
    }
}

/**
 * One invocation is one stampede: `callers` threads ask for the same uncached key at the same moment.
 * The JMH score is the time until the last caller has its data. The events file has the supplier call count
 * and the latency of each caller.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class StampedeBenchmark : ScenarioState() {
    @Param("8", "100")
    @JvmField
    final var callers: Int = 0

    private lateinit var workers: ExecutorService
    private val latencies = ArrayList<Long>()
    private val supplierCallsPerRun = ArrayList<Int>()

    @Setup(Level.Trial)
    fun setup() {
        setupArms()
        workers = Executors.newFixedThreadPool(callers)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        workers.shutdownNow()
        executor.shutdownNow()
        val sorted = latencies.toLongArray().also { it.sort() }

        fun millis(p: Double) = "%.2f".format(percentile(sorted, p) / 1e6)
        Events.write(
            """{"event":"stampede","module":"$module","arm":"$arm","callers":$callers,"supplierMillis":$supplierMillis,""" +
                """"runs":${supplierCallsPerRun.size},"supplierCallsMin":${supplierCallsPerRun.minOrNull()},""" +
                """"supplierCallsMax":${supplierCallsPerRun.maxOrNull()},""" +
                """"supplierCallsMean":${"%.2f".format(supplierCallsPerRun.average())},""" +
                """"latencyMsP50":${millis(0.50)},"latencyMsP90":${millis(0.90)},"latencyMsP99":${millis(0.99)},""" +
                """"latencyMsMax":${millis(1.0)}}""",
        )
    }

    @Benchmark
    fun stampede() {
        val key = freshKey()
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)
        val nanos = LongArray(callers)
        repeat(callers) { i ->
            workers.execute {
                ready.countDown()
                start.await()
                val begin = System.nanoTime()
                call(key)
                nanos[i] = System.nanoTime() - begin
                done.countDown()
            }
        }
        ready.await()
        start.countDown()
        done.await()
        if (measuring) {
            nanos.forEach { latencies.add(it) }
            supplierCallsPerRun.add(supplierCalls.get())
        }
    }
}

/**
 * One thread reads a new key, then reads it again at once. req-shield returns the first read before its background
 * cache write, so the second read can find the cache empty and the lock taken.
 * The JMH score is the time of both reads. The events file has the latency of the second read.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ReadAfterMissBenchmark : ScenarioState() {
    private val secondReadNanos = ArrayList<Long>()
    private val supplierCallsPerRun = ArrayList<Int>()

    @Setup(Level.Trial)
    fun setup() {
        setupArms()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        executor.shutdownNow()
        val sorted = secondReadNanos.toLongArray().also { it.sort() }

        fun millis(p: Double) = "%.3f".format(percentile(sorted, p) / 1e6)
        val slow = sorted.count { it >= 40_000_000 }
        Events.write(
            """{"event":"readAfterMiss","module":"$module","arm":"$arm","supplierMillis":$supplierMillis,""" +
                """"setMillis":$setMillis,"runs":${sorted.size},""" +
                """"supplierCallsMean":${"%.2f".format(supplierCallsPerRun.average())},""" +
                """"secondReadMsP50":${millis(0.50)},"secondReadMsP90":${millis(0.90)},"secondReadMsMax":${millis(1.0)},""" +
                """"secondReadsOver40ms":$slow}""",
        )
    }

    @Benchmark
    fun readTwice(): ReqShieldData<String> {
        val key = freshKey()
        call(key)
        val begin = System.nanoTime()
        val second = call(key)
        if (measuring) {
            secondReadNanos.add(System.nanoTime() - begin)
            supplierCallsPerRun.add(supplierCalls.get())
        }
        return second
    }
}

/**
 * Steady load on hot keys whose entries expire during the run, with a supplier that takes a random time.
 * `callers` threads read the hot keys in turn with a short pause between reads. The cache drops an entry at its
 * time to live. cache-aside then calls the supplier from every caller that finds the entry gone. req-shield starts
 * a background refresh at 80 % of the time to live and collapses the callers that still find the entry gone.
 * One invocation is one run of `runMillis`. The events file has the supplier call count and the latency of each read.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ExpiryLoadBenchmark : ScenarioState() {
    @Param("50")
    @JvmField
    final var callers: Int = 0

    @Param("10")
    @JvmField
    final var hotKeys: Int = 0

    @Param("1000")
    @JvmField
    final var ttlMillis: Long = 0

    /** Supplier time is uniform in [randomMinMillis, randomMaxMillis]. 20 % of the time to live is the refresh window. */
    @Param("10")
    @JvmField
    final var randomMinMillis: Long = 0

    @Param("300")
    @JvmField
    final var randomMaxMillis: Long = 0

    @Param("10000")
    @JvmField
    final var runMillis: Long = 0

    @Param("2")
    @JvmField
    final var thinkMillis: Long = 0

    private lateinit var workers: ExecutorService
    private val latencies = ArrayList<Long>()
    private var supplierCallsTotal = 0L
    private var runs = 0

    @Setup(Level.Trial)
    fun setup() {
        supplierMinMillis = randomMinMillis
        supplierMaxMillis = randomMaxMillis
        entryTtlMillis = ttlMillis
        setupArms()
        workers = Executors.newFixedThreadPool(callers)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        workers.shutdownNow()
        executor.shutdownNow()
        val sorted = latencies.toLongArray().also { it.sort() }

        fun millis(p: Double) = "%.3f".format(percentile(sorted, p) / 1e6)
        val expiries = runs * hotKeys * (runMillis.toDouble() / ttlMillis)
        Events.write(
            """{"event":"expiryLoad","module":"$module","arm":"$arm","callers":$callers,"hotKeys":$hotKeys,""" +
                """"ttlMillis":$ttlMillis,"supplierMinMillis":$randomMinMillis,"supplierMaxMillis":$randomMaxMillis,""" +
                """"runs":$runs,"requests":${sorted.size},"supplierCalls":$supplierCallsTotal,""" +
                """"supplierCallsPerExpiry":${"%.2f".format(supplierCallsTotal / expiries)},""" +
                """"supplierMillisMean":${"%.1f".format(supplierMillisTotal.get().toDouble() / maxOf(1, supplierCallsAll))},""" +
                """"latencyMsP50":${millis(0.50)},"latencyMsP90":${millis(0.90)},"latencyMsP99":${millis(0.99)},""" +
                """"latencyMsP999":${millis(0.999)},"latencyMsMax":${millis(1.0)},""" +
                """"readsOver5ms":${sorted.count { it >= 5_000_000 }},"readsOver40ms":${sorted.count { it >= 40_000_000 }}}""",
        )
    }

    /** Supplier calls of all runs, warm-up included: the divisor of the mean supplier time. */
    private var supplierCallsAll = 0L

    @Benchmark
    fun load() {
        val now = System.currentTimeMillis()
        // The entries start at different ages, so the keys do not all expire at the same moment.
        val keys =
            Array(hotKeys) { i ->
                freshKey().also { cache[it] = ReqShieldData("value", ReqShieldData.Status.NEW, now - i * ttlMillis / hotKeys, ttlMillis) }
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(runMillis)
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)
        val perCaller = Array(callers) { ArrayList<Long>() }
        repeat(callers) { caller ->
            workers.execute {
                start.await()
                var next = caller
                while (System.nanoTime() < deadline) {
                    val key = keys[next++ % hotKeys]
                    val begin = System.nanoTime()
                    call(key)
                    perCaller[caller].add(System.nanoTime() - begin)
                    Thread.sleep(thinkMillis)
                }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        supplierCallsAll += supplierCalls.get()
        if (measuring) {
            perCaller.forEach { latencies.addAll(it) }
            supplierCallsTotal += supplierCalls.get()
            runs++
        }
    }
}
