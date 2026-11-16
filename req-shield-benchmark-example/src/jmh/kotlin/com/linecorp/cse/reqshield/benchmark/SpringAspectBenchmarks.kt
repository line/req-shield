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
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
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
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import reactor.core.publisher.Mono
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable as BlockingCacheable
import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration as BlockingAutoConfiguration
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable as ReactiveCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache as ReactiveAsyncCache
import com.linecorp.cse.reqshield.spring.webflux.config.LibAutoConfiguration as ReactiveAutoConfiguration
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable as SuspendCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache as SuspendAsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.config.LibAutoConfiguration as SuspendAutoConfiguration

private const val ID_COUNT = 1024
private const val DIRECT_PREFIX = "direct::"

/** Waits for the background cache writes of the warm-up calls. */
private fun awaitEntries(
    map: Map<String, *>,
    expected: Int,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (map.size < expected && System.nanoTime() < deadline) Thread.sleep(5)
    check(map.size >= expected) { "cache has ${map.size} entries, expected $expected" }
}

// ---------------------------------------------------------------------------------------------------------------
// core-spring: blocking aspect
// ---------------------------------------------------------------------------------------------------------------

class MapReqShieldCache : ReqShieldCache<String> {
    val map = ConcurrentHashMap<String, ReqShieldData<String>>()

    override fun get(key: String): ReqShieldData<String>? = map[key]

    override fun put(
        key: String,
        value: ReqShieldData<String>,
        timeToLiveMillis: Long,
    ) {
        map[key] = value
    }

    override fun evict(key: String): Boolean = map.remove(key) != null
}

open class BlockingProductService {
    /** No advice: the cost of the proxy call alone. */
    open fun noAdvice(id: String): String = "value"

    @BlockingCacheable(cacheName = "byId")
    open fun defaultKey(id: String): String = "value"

    @BlockingCacheable(cacheName = "bySpel", key = "#id")
    open fun spelKey(id: String): String = "value"

    @BlockingCacheable(cacheName = "byTwo")
    open fun twoArguments(
        id: String,
        locale: String,
    ): String = "value"

    /** Spring's own cache abstraction: what a Spring application uses without req-shield. */
    @Cacheable("springById")
    open fun springCacheable(id: String): String = "value"
}

/**
 * Call count and delay of [SlowProductService]. They live outside the bean: a CGLIB proxy is a subclass
 * instance with its own, uninitialized fields, so state read through the proxy would be null.
 */
object SlowOrigin {
    val calls = AtomicInteger()

    @Volatile
    var delayMillis = 0L
}

/** A method that takes time, as an origin call does. It counts its calls. */
open class SlowProductService {
    private fun load(): String {
        SlowOrigin.calls.incrementAndGet()
        Thread.sleep(SlowOrigin.delayMillis)
        return "value"
    }

    @BlockingCacheable(cacheName = "slow")
    open fun reqShieldAspect(id: String): String = load()

    @Cacheable("slowSpring")
    open fun springCacheable(id: String): String = load()

    /** Spring's own request collapsing: one thread loads, the others wait on the cache's lock. */
    @Cacheable("slowSpringSync", sync = true)
    open fun springCacheableSync(id: String): String = load()
}

@Configuration
@EnableCaching
@Import(BlockingAutoConfiguration::class)
open class BlockingBenchConfig {
    @Bean
    open fun slowProductService() = SlowProductService()

    @Bean
    open fun reqShieldCache(): ReqShieldCache<String> = MapReqShieldCache()

    /** The aspect uses a bean of this name for its background cache writes. */
    @Bean
    open fun reqShieldExecutor(): Executor = libraryLikeExecutor()

    @Bean
    open fun cacheManager(): CacheManager = ConcurrentMapCacheManager()

    @Bean
    open fun productService() = BlockingProductService()
}

/** Cache hit through the core-spring aspect, against a direct ReqShield call on the same cache. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class SpringAspectHitBenchmark {
    private val ids = Array(ID_COUNT) { "$it" }
    private val directKeys = Array(ID_COUNT) { "$DIRECT_PREFIX$it" }
    private val supplier = Callable<String?> { "value" }
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: BlockingProductService
    private lateinit var direct: ReqShield<String>

    @Setup(Level.Trial)
    fun setup() {
        context = AnnotationConfigApplicationContext(BlockingBenchConfig::class.java)
        service = context.getBean(BlockingProductService::class.java)
        val cache = context.getBean(MapReqShieldCache::class.java)
        direct =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { key, data, ttl ->
                        cache.put(key, data, ttl)
                        true
                    },
                    getCacheFunction = { cache.get(it) },
                    executor = context.getBean("reqShieldExecutor", Executor::class.java),
                ),
            )
        // The first call of each key is a miss that fills the cache in the background.
        for (i in 0 until ID_COUNT) {
            service.defaultKey(ids[i])
            service.spelKey(ids[i])
            service.twoArguments(ids[i], "ko")
            service.springCacheable(ids[i])
            direct.getAndSetReqShieldData(directKeys[i], supplier, TTL_MILLIS)
        }
        awaitEntries(cache.map, ID_COUNT * 4)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        context.close()
    }

    @Benchmark
    fun directReqShield(cursor: KeyCursor): String? = direct.getAndSetReqShieldData(directKeys[cursor.next()], supplier, TTL_MILLIS).value

    @Benchmark
    fun proxyNoAdvice(cursor: KeyCursor): String = service.noAdvice(ids[cursor.next()])

    @Benchmark
    fun springCacheable(cursor: KeyCursor): String = service.springCacheable(ids[cursor.next()])

    @Benchmark
    fun aspectDefaultKey(cursor: KeyCursor): String = service.defaultKey(ids[cursor.next()])

    @Benchmark
    fun aspectSpelKey(cursor: KeyCursor): String = service.spelKey(ids[cursor.next()])

    @Benchmark
    fun aspectTwoArguments(cursor: KeyCursor): String = service.twoArguments(ids[cursor.next()], "ko")
}

/**
 * One invocation is one stampede through a Spring bean: `callers` threads call the same method with the same
 * new argument at the same moment. The arms are the req-shield aspect, Spring `@Cacheable`, and
 * `@Cacheable(sync = true)`. The events file has the method call count and the latency of each caller.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class SpringAspectStampedeBenchmark {
    @Param("reqShieldAspect", "springCacheable", "springCacheableSync")
    @JvmField
    final var arm: String = ""

    @Param("100")
    @JvmField
    final var callers: Int = 0

    @Param("10", "100")
    @JvmField
    final var supplierMillis: Long = 0

    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: SlowProductService
    private lateinit var call: (String) -> String
    private lateinit var workers: ExecutorService
    private lateinit var aspectExecutor: ThreadPoolExecutor
    private val latencies = ArrayList<Long>()
    private val callsPerRun = ArrayList<Int>()
    private var measuring = false
    private var sequence = 0

    @Setup(Level.Trial)
    fun setup() {
        context = AnnotationConfigApplicationContext(BlockingBenchConfig::class.java)
        service = context.getBean(SlowProductService::class.java)
        SlowOrigin.delayMillis = supplierMillis
        aspectExecutor = context.getBean("reqShieldExecutor", ThreadPoolExecutor::class.java)
        call =
            when (arm) {
                "reqShieldAspect" -> service::reqShieldAspect
                "springCacheable" -> service::springCacheable
                "springCacheableSync" -> service::springCacheableSync
                else -> error("unknown arm $arm")
            }
        workers = Executors.newFixedThreadPool(callers)
    }

    @Setup(Level.Iteration)
    fun beforeRun(iteration: IterationParams) {
        measuring = iteration.type == IterationType.MEASUREMENT
        awaitQuiescence(aspectExecutor)
        Thread.sleep(5)
        SlowOrigin.calls.set(0)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        workers.shutdownNow()
        context.close()
        val sorted = latencies.toLongArray().also { it.sort() }

        fun millis(p: Double) = "%.2f".format(percentile(sorted, p) / 1e6)
        Events.write(
            """{"event":"aspectStampede","arm":"$arm","callers":$callers,"supplierMillis":$supplierMillis,""" +
                """"runs":${callsPerRun.size},"methodCallsMin":${callsPerRun.minOrNull()},"methodCallsMax":${callsPerRun.maxOrNull()},""" +
                """"latencyMsP50":${millis(0.50)},"latencyMsP90":${millis(0.90)},"latencyMsP99":${millis(0.99)},""" +
                """"latencyMsMax":${millis(1.0)}}""",
        )
    }

    @Benchmark
    fun stampede() {
        val id = "$arm-$supplierMillis-${sequence++}"
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)
        val nanos = LongArray(callers)
        repeat(callers) { i ->
            workers.execute {
                ready.countDown()
                start.await()
                val begin = System.nanoTime()
                call(id)
                nanos[i] = System.nanoTime() - begin
                done.countDown()
            }
        }
        ready.await()
        start.countDown()
        done.await()
        if (measuring) {
            nanos.forEach { latencies.add(it) }
            callsPerRun.add(SlowOrigin.calls.get())
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// core-spring-webflux: Mono aspect. Every arm ends in block().
// ---------------------------------------------------------------------------------------------------------------

class MapReactiveCache : ReactiveAsyncCache<String> {
    val map = ConcurrentHashMap<String, ReqShieldData<String>>()

    override fun get(key: String): Mono<ReqShieldData<String>?> = Mono.justOrEmpty(map[key])

    override fun put(
        key: String,
        value: ReqShieldData<String>,
        timeToLiveMillis: Long,
    ): Mono<Boolean> =
        Mono.fromCallable {
            map[key] = value
            true
        }

    override fun evict(key: String): Mono<Boolean> = Mono.fromCallable { map.remove(key) != null }
}

open class ReactiveProductService {
    @ReactiveCacheable(cacheName = "byId")
    open fun defaultKey(id: String): Mono<String> = Mono.just("value")

    @ReactiveCacheable(cacheName = "bySpel", key = "#id")
    open fun spelKey(id: String): Mono<String> = Mono.just("value")
}

@Configuration
@Import(ReactiveAutoConfiguration::class)
open class ReactiveBenchConfig {
    @Bean
    open fun asyncCache(): ReactiveAsyncCache<String> = MapReactiveCache()

    @Bean
    open fun productService() = ReactiveProductService()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class WebfluxAspectHitBenchmark {
    private val ids = Array(ID_COUNT) { "$it" }
    private val directKeys = Array(ID_COUNT) { "$DIRECT_PREFIX$it" }
    private val supplier = Callable<Mono<String?>> { Mono.just("value") }
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: ReactiveProductService
    private lateinit var direct: ReactorReqShield<String>

    @Setup(Level.Trial)
    fun setup() {
        context = AnnotationConfigApplicationContext(ReactiveBenchConfig::class.java)
        service = context.getBean(ReactiveProductService::class.java)
        val cache = context.getBean(MapReactiveCache::class.java)
        direct =
            ReactorReqShield(
                ReactorConfig(
                    setCacheFunction = { key: String, data: ReqShieldData<String>, ttl: Long -> cache.put(key, data, ttl) },
                    getCacheFunction = { key: String -> cache.get(key) },
                ),
            )
        for (i in 0 until ID_COUNT) {
            service.defaultKey(ids[i]).block()
            service.spelKey(ids[i]).block()
            direct.getAndSetReqShieldData(directKeys[i], supplier, TTL_MILLIS).block()
        }
        awaitEntries(cache.map, ID_COUNT * 3)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        context.close()
    }

    @Benchmark
    fun directReqShield(cursor: KeyCursor): String? =
        direct.getAndSetReqShieldData(directKeys[cursor.next()], supplier, TTL_MILLIS).block()?.value

    @Benchmark
    fun aspectDefaultKey(cursor: KeyCursor): String? = service.defaultKey(ids[cursor.next()]).block()

    @Benchmark
    fun aspectSpelKey(cursor: KeyCursor): String? = service.spelKey(ids[cursor.next()]).block()
}

// ---------------------------------------------------------------------------------------------------------------
// core-spring-webflux-kotlin-coroutine: suspend aspect. Every arm runs in runBlocking.
// ---------------------------------------------------------------------------------------------------------------

class MapSuspendCache : SuspendAsyncCache<String> {
    val map = ConcurrentHashMap<String, ReqShieldData<String>>()

    override suspend fun get(key: String): ReqShieldData<String>? = map[key]

    override suspend fun put(
        key: String,
        value: ReqShieldData<String>,
        timeToLiveMillis: Long,
    ): Boolean {
        map[key] = value
        return true
    }

    override suspend fun evict(key: String): Boolean = map.remove(key) != null
}

open class SuspendProductService {
    @SuspendCacheable(cacheName = "byId")
    open suspend fun defaultKey(id: String): String = "value"

    @SuspendCacheable(cacheName = "bySpel", key = "#id")
    open suspend fun spelKey(id: String): String = "value"
}

@Configuration
@Import(SuspendAutoConfiguration::class)
open class SuspendBenchConfig {
    @Bean
    open fun asyncCache(): SuspendAsyncCache<String> = MapSuspendCache()

    @Bean
    open fun productService() = SuspendProductService()
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class CoroutineAspectHitBenchmark {
    private val ids = Array(ID_COUNT) { "$it" }
    private val directKeys = Array(ID_COUNT) { "$DIRECT_PREFIX$it" }
    private val supplier: suspend () -> String? = { "value" }
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: SuspendProductService
    private lateinit var direct: CoroutineReqShield<String>

    @Setup(Level.Trial)
    fun setup() {
        context = AnnotationConfigApplicationContext(SuspendBenchConfig::class.java)
        service = context.getBean(SuspendProductService::class.java)
        val cache = context.getBean(MapSuspendCache::class.java)
        direct =
            CoroutineReqShield(
                CoroutineConfig(
                    setCacheFunction = { key: String, data: ReqShieldData<String>, ttl: Long -> cache.put(key, data, ttl) },
                    getCacheFunction = { key: String -> cache.get(key) },
                ),
            )
        runBlocking {
            for (i in 0 until ID_COUNT) {
                service.defaultKey(ids[i])
                service.spelKey(ids[i])
                direct.getAndSetReqShieldData(directKeys[i], supplier, TTL_MILLIS)
            }
        }
        awaitEntries(cache.map, ID_COUNT * 3)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        context.close()
    }

    @Benchmark
    fun directReqShield(cursor: KeyCursor): String? =
        runBlocking { direct.getAndSetReqShieldData(directKeys[cursor.next()], supplier, TTL_MILLIS).value }

    @Benchmark
    fun aspectDefaultKey(cursor: KeyCursor): String = runBlocking { service.defaultKey(ids[cursor.next()]) }

    @Benchmark
    fun aspectSpelKey(cursor: KeyCursor): String = runBlocking { service.spelKey(ids[cursor.next()]) }
}
