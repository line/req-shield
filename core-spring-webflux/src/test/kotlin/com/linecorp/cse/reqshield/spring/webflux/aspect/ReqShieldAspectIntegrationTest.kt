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

import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.spring.withNamedBean
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.BeanNotOfRequiredTypeException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private const val INTEGRATION_CACHE_NAME = "it"

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectIntegrationTest.TestConfig::class])
class ReqShieldAspectIntegrationTest {
    @Autowired
    private lateinit var service: TestService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private fun cacheKey(key: String) = "$INTEGRATION_CACHE_NAME::$key"

    /** ReqShield writes the cache asynchronously, so a test that needs the entry has to wait for it. */
    private fun awaitCachePut(
        key: String,
        timeoutMillis: Long = 2_000,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (asyncCache.get(key).block() != null) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun shouldCollapseDuplicateRequests() {
        val key = "dup-${System.nanoTime()}"
        val attempts = 20

        val result =
            Flux
                .range(1, attempts)
                .flatMap { service.get(key).subscribeOn(Schedulers.boundedElastic()) }
                .collectList()
                .block()

        assertEquals(attempts, result?.size)
        val first = result?.firstOrNull()
        assertTrue(result?.all { it == first } == true)
    }

    @Test
    fun shouldEvictAndRecompute() {
        val key = "evict-${System.nanoTime()}"
        val v1 = service.get(key).block()
        assertTrue(awaitCachePut(cacheKey(key)), "Timed out waiting for cache put for key=${cacheKey(key)}")

        val evicted = service.evict(key).block()
        val v2 = service.get(key).block()

        assertTrue(evicted == true)
        assertTrue(v1 != null)
        assertTrue(v2 != null)
        assertTrue(v1 != v2)
    }

    @Test
    fun shouldEvictAfterAMethodThatCompletesEmpty() {
        val key = "evict-void-${System.nanoTime()}"
        asyncCache.put(cacheKey(key), ReqShieldData("cached", 10_000), 10_000).block()

        StepVerifier
            .create(service.evictWithoutResult(key))
            .verifyComplete()

        assertEquals(1, service.getEmptyEvictCount(), "The annotated method must still run")
        assertNull(asyncCache.get(cacheKey(key)).block(), "An empty completion is a success, so it evicts")
    }

    @Test
    fun shouldNotEvictWhenTheAnnotatedMethodFails() {
        val key = "evict-fail-${System.nanoTime()}"
        asyncCache.put(cacheKey(key), ReqShieldData("cached", 10_000), 10_000).block()

        assertThrows<IllegalStateException> { service.evictFailing(key).block() }

        assertNotNull(asyncCache.get(cacheKey(key)).block(), "A failed method must leave the cache untouched")
    }

    @Test
    fun shouldCollapseDuplicateRequestsWithGlobalLock() {
        val key = "dup-global-${System.nanoTime()}"
        val attempts = 20

        val result =
            Flux
                .range(1, attempts)
                .flatMap { service.getWithGlobalLock(key).subscribeOn(Schedulers.boundedElastic()) }
                .collectList()
                .block()!!

        assertEquals(attempts, result.size)
        assertEquals(1, service.getGlobalLockCount(), "The backend should be called once")
    }

    @Test
    fun libraryShouldNotRegisterASchedulerBean() {
        // A library bean would clash with an application bean of the same name
        assertTrue(applicationContext.getBeansOfType(Scheduler::class.java).isEmpty())
    }

    @Test
    fun cacheWritesShouldRunOnTheSharedBoundedElasticSchedulerByDefault() {
        val key = "default-scheduler-${System.nanoTime()}"
        service.get(key).block()

        assertTrue(awaitCachePut(cacheKey(key)))
        assertTrue((asyncCache as InMemoryAsyncCache).lastWriterThread!!.startsWith("boundedElastic-"))
    }

    @Test
    fun schedulerBeanNamedReqShieldSchedulerShouldReplaceTheDefault() {
        val userScheduler = Schedulers.newSingle("user-scheduler", true)
        withNamedBean("reqShieldScheduler", userScheduler, *CONFIGURATIONS) { userContext ->
            val key = "user-scheduler-${System.nanoTime()}"
            userContext.getBean(TestService::class.java).get(key).block()
            val userCache = userContext.getBean(InMemoryAsyncCache::class.java)

            await().atMost(Duration.ofSeconds(5)).until { userCache.get(cacheKey(key)).block() != null }
            assertTrue(userCache.lastWriterThread!!.startsWith("user-scheduler"))
        }
        // The scheduler belongs to the application, so the library must leave it running
        assertFalse(userScheduler.isDisposed)
        userScheduler.dispose()
    }

    @Test
    fun beanNamedReqShieldSchedulerOfAnotherTypeShouldFailTheRefresh() {
        val error =
            assertThrows<BeanCreationException> {
                withNamedBean("reqShieldScheduler", "not a scheduler", *CONFIGURATIONS) { }
            }

        assertTrue(error.mostSpecificCause is BeanNotOfRequiredTypeException, error.toString())
    }

    @Test
    fun schedulerBeansWithOtherNamesShouldBeIgnored() {
        val otherScheduler = Schedulers.newSingle("other-scheduler", true)
        withNamedBean("otherScheduler", otherScheduler, *CONFIGURATIONS) { otherContext ->
            val key = "other-scheduler-${System.nanoTime()}"
            otherContext.getBean(TestService::class.java).get(key).block()
            val otherCache = otherContext.getBean(InMemoryAsyncCache::class.java)

            await().atMost(Duration.ofSeconds(5)).until { otherCache.get(cacheKey(key)).block() != null }
            assertTrue(otherCache.lastWriterThread!!.startsWith("boundedElastic-"))
        }
        otherScheduler.dispose()
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun service(): TestService = TestService()
    }

    open class TestService {
        private val counter = AtomicInteger(0)
        private val globalLockCounter = AtomicInteger(0)
        private val emptyEvictCounter = AtomicInteger(0)

        // Read through open methods: a CGLIB proxy cannot delegate the final getter of a Kotlin property.
        open fun getGlobalLockCount(): Int = globalLockCounter.get()

        open fun getEmptyEvictCount(): Int = emptyEvictCounter.get()

        @ReqShieldCacheable(cacheName = INTEGRATION_CACHE_NAME, key = "#key", timeToLiveMillis = 10_000)
        open fun get(key: String): Mono<String> = Mono.fromCallable { "value-" + counter.incrementAndGet() }

        @ReqShieldCacheable(cacheName = INTEGRATION_CACHE_NAME, key = "#key", timeToLiveMillis = 10_000, isLocalLock = false)
        open fun getWithGlobalLock(key: String): Mono<String> = Mono.fromCallable { "value-" + globalLockCounter.incrementAndGet() }

        @ReqShieldCacheEvict(cacheName = INTEGRATION_CACHE_NAME, key = "#key")
        open fun evict(key: String): Mono<Boolean> = Mono.just(true)

        @ReqShieldCacheEvict(cacheName = INTEGRATION_CACHE_NAME, key = "#key")
        open fun evictWithoutResult(key: String): Mono<Void> = Mono.fromRunnable { emptyEvictCounter.incrementAndGet() }

        @ReqShieldCacheEvict(cacheName = INTEGRATION_CACHE_NAME, key = "#key")
        open fun evictFailing(key: String): Mono<Boolean> = Mono.error(IllegalStateException("eviction must not happen"))
    }

    companion object {
        private val CONFIGURATIONS = arrayOf(LibAutoConfiguration::class.java, TestConfig::class.java)
    }
}

/**
 * `isLocalLock = false` needs its own context: the cache bean here deliberately does not implement
 * [com.linecorp.cse.reqshield.spring.webflux.cache.GlobalLockSupport].
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectWithoutGlobalLockSupportTest.TestConfig::class])
class ReqShieldAspectWithoutGlobalLockSupportTest {
    @Autowired
    private lateinit var service: TestService

    @Test
    fun globalLockWithoutGlobalLockSupportFailsOnTheFirstCall() {
        val exception = assertThrows<IllegalArgumentException> { service.getWithGlobalLock("key").block() }

        assertTrue(
            exception.message!!.contains("requires the AsyncCache bean to implement GlobalLockSupport"),
            "Unexpected message: ${exception.message}",
        )
        assertEquals(0, service.getCallCount(), "The backend must not be called")
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = LocalOnlyAsyncCache()

        @Bean
        open fun service(): TestService = TestService()
    }

    open class TestService {
        private val counter = AtomicInteger(0)

        // Read through an open method: a CGLIB proxy cannot delegate the final getter of a Kotlin property.
        open fun getCallCount(): Int = counter.get()

        @ReqShieldCacheable(cacheName = INTEGRATION_CACHE_NAME, key = "#key", isLocalLock = false)
        open fun getWithGlobalLock(key: String): Mono<String> = Mono.fromCallable { "value-" + counter.incrementAndGet() }
    }
}
