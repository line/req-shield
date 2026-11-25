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

package aspect

import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.aspect.ReqShieldAspect
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.spring.withNamedBean
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.BeanNotOfRequiredTypeException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wires the aspect the way a consumer does - through [LibAutoConfiguration] only - so it also proves
 * that the `@Import` of the aspect works without component scanning.
 *
 * spring-test is not a test dependency of this module, so the context is driven directly.
 */
class ReqShieldAspectIntegrationTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: TestService
    private lateinit var cache: InMemoryReqShieldCache

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigApplicationContext(*CONFIGURATIONS)
        service = context.getBean(TestService::class.java)
        cache = context.getBean(InMemoryReqShieldCache::class.java)
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    @Test
    fun libraryShouldNotRegisterAnyExecutorBean() {
        // Spring Boot can back off its applicationTaskExecutor when another Executor bean exists
        assertTrue(context.getBeansOfType(Executor::class.java).isEmpty())
    }

    @Test
    fun cacheWritesShouldRunOnThePoolOwnedByTheAspectAndStopWithTheContext() {
        service.get("owned-pool")

        await().atMost(Duration.ofSeconds(5)).until { cache.get("integration::owned-pool") != null }
        assertTrue(cache.lastWriterThread!!.startsWith(OWNED_POOL_THREAD_PREFIX), cache.lastWriterThread)

        val ownedExecutor = context.getBean(ReqShieldAspect::class.java).ownedExecutor
        assertNotNull(ownedExecutor)
        context.close()
        assertTrue(ownedExecutor!!.isShutdown)
    }

    @Test
    fun executorBeanNamedReqShieldExecutorShouldReplaceTheOwnedPool() {
        val userPool = Executors.newSingleThreadExecutor { Thread(it, "user-pool") }
        // A plain Executor view, so closing the context does not shut the pool down on its own
        val userExecutor = Executor { userPool.execute(it) }
        withNamedBean("reqShieldExecutor", userExecutor, *CONFIGURATIONS) { userContext ->
            userContext.getBean(TestService::class.java).get("user-pool")
            val userCache = userContext.getBean(InMemoryReqShieldCache::class.java)

            await().atMost(Duration.ofSeconds(5)).until { userCache.get("integration::user-pool") != null }
            assertEquals("user-pool", userCache.lastWriterThread)
            assertNull(userContext.getBean(ReqShieldAspect::class.java).ownedExecutor)
        }
        // The aspect only ever sees the wrapper here; ReqShieldAspectTest checks that destroy() leaves the pool running
        userPool.shutdown()
    }

    @Test
    fun beanNamedReqShieldExecutorOfAnotherTypeShouldFailTheRefresh() {
        val error =
            assertThrows<BeanCreationException> {
                withNamedBean("reqShieldExecutor", "not an executor", *CONFIGURATIONS) { }
            }

        assertTrue(error.mostSpecificCause is BeanNotOfRequiredTypeException, error.toString())
    }

    @Test
    fun executorBeansWithOtherNamesShouldBeIgnored() {
        val otherPool = Executors.newSingleThreadExecutor { Thread(it, "other-pool") }
        withNamedBean("applicationTaskExecutor", Executor { otherPool.execute(it) }, *CONFIGURATIONS) { otherContext ->
            otherContext.getBean(TestService::class.java).get("other-pool")
            val otherCache = otherContext.getBean(InMemoryReqShieldCache::class.java)

            await().atMost(Duration.ofSeconds(5)).until { otherCache.get("integration::other-pool") != null }
            assertTrue(otherCache.lastWriterThread!!.startsWith(OWNED_POOL_THREAD_PREFIX), otherCache.lastWriterThread)
        }
        otherPool.shutdown()
    }

    @Test
    fun shouldCollapseDuplicateRequests() {
        val attempts = 20
        val executorService = Executors.newFixedThreadPool(attempts)
        val startLatch = CountDownLatch(1)
        val results = ConcurrentHashMap.newKeySet<String>()

        repeat(attempts) {
            executorService.submit {
                startLatch.await()
                results.add(service.get("dup"))
            }
        }
        startLatch.countDown()
        executorService.shutdown()
        assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS))

        // only the request holding the lock calls the backend, the others wait for its result
        assertEquals(1, service.callCount())
        assertEquals(setOf("value-1"), results)
    }

    @Test
    fun shouldEvictAndRecompute() {
        val v1 = service.get("evict")

        // the cache is written asynchronously, so wait for it before evicting
        await().atMost(Duration.ofSeconds(5)).until { cache.get("integration::evict") != null }

        service.evict("evict")
        assertNull(cache.get("integration::evict"))

        val v2 = service.get("evict")

        assertEquals("value-1", v1)
        assertEquals("value-2", v2)
        assertEquals(2, service.callCount())
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun reqShieldCache(): InMemoryReqShieldCache = InMemoryReqShieldCache()

        @Bean
        open fun testService(): TestService = TestService()
    }

    open class TestService {
        private val counter = AtomicInteger(0)

        // read through a method: the bean is a CGLIB proxy whose own fields are never initialized
        open fun callCount(): Int = counter.get()

        @ReqShieldCacheable(cacheName = "integration", key = "#key", timeToLiveMillis = 10_000)
        open fun get(key: String): String {
            // slow enough that concurrent callers reach the lock before the first one finishes
            Thread.sleep(100)
            return "value-" + counter.incrementAndGet()
        }

        @ReqShieldCacheEvict(cacheName = "integration", key = "#key")
        open fun evict(key: String) {
            // nothing to do: the aspect evicts after this method returns
        }
    }

    companion object {
        private val CONFIGURATIONS = arrayOf(LibAutoConfiguration::class.java, TestConfig::class.java)

        /** Threads of the pool owned by the aspect, named apart from the core default pool. */
        private const val OWNED_POOL_THREAD_PREFIX = "req-shield-aspect-executor-"
    }

    class InMemoryReqShieldCache : ReqShieldCache<String> {
        private val store = ConcurrentHashMap<String, ReqShieldData<String>>()

        /** Name of the thread that ran the most recent write, to tell which executor performed it. */
        @Volatile
        var lastWriterThread: String? = null

        override fun get(key: String): ReqShieldData<String>? = store[key]

        override fun put(
            key: String,
            value: ReqShieldData<String>,
            timeToLiveMillis: Long,
        ) {
            lastWriterThread = Thread.currentThread().name
            store[key] = value
        }

        override fun evict(key: String): Boolean? = store.remove(key) != null
    }
}
