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

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.config.LibAutoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectIntegrationTest.TestConfig::class])
class ReqShieldAspectIntegrationTest {
    @Autowired
    private lateinit var service: TestService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @Autowired
    private lateinit var reqShieldCoroutineScope: CoroutineScope

    /** The aspect namespaces every key with the cache name of the annotation. */
    private fun cacheKeyOf(key: String) = "$CACHE_NAME::$key"

    private suspend fun awaitCachePut(
        key: String,
        timeoutMillis: Long = 1_000,
    ): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (asyncCache.get(cacheKeyOf(key)) == null) {
                delay(5)
            }
            true
        } ?: false

    @Test
    fun shouldCollapseDuplicateRequests() =
        runBlocking {
            val key = "dup"
            val attempts = 20
            val results = (1..attempts).map { async(Dispatchers.IO) { service.get(key) } }.awaitAll()
            assertEquals(attempts, results.size)
            val first = results.firstOrNull()
            assertTrue(results.all { it == first })
        }

    @Test
    fun shouldEvictAndRecompute() =
        runBlocking {
            val key = "evict-${System.nanoTime()}" // Use unique key for test isolation
            val v1 = service.get(key)
            // ReqShield stores cache asynchronously; wait until the cache write is observed.
            assertTrue(awaitCachePut(key), "Timed out waiting for cache put for key=$key")
            val evicted = service.evict(key)
            val v2 = service.get(key)

            assertTrue(evicted)
            assertTrue(v1.isNotEmpty())
            assertTrue(v2.isNotEmpty())
            assertTrue(v1 != v2)
        }

    @Test
    fun shouldEvictOnlyAfterTheAnnotatedMethodSucceeds() =
        runBlocking {
            val key = "evict-success-${System.nanoTime()}"
            service.get(key)
            assertTrue(awaitCachePut(key), "Timed out waiting for cache put for key=$key")

            assertTrue(service.evict(key))

            assertNull(asyncCache.get(cacheKeyOf(key)))
        }

    @Test
    fun shouldKeepTheCacheWhenTheAnnotatedMethodThrows() =
        runBlocking {
            val key = "evict-failure-${System.nanoTime()}"
            service.get(key)
            assertTrue(awaitCachePut(key), "Timed out waiting for cache put for key=$key")

            assertFailsWith<IllegalStateException> { service.evictFailing(key) }

            assertNotNull(asyncCache.get(cacheKeyOf(key)))
        }

    @Test
    fun shouldExposeTheCacheKeyUnderTheCacheNameNamespace() =
        runBlocking {
            val key = "namespace-${System.nanoTime()}"
            service.get(key)
            assertTrue(awaitCachePut(key), "Timed out waiting for cache put for key=$key")

            // The bare key must never be used: only the namespaced one carries the entry.
            assertNull(asyncCache.get(key))
            assertNotNull(asyncCache.get(cacheKeyOf(key)))
        }

    @Test
    fun shouldWireTheCoroutineScopeBeanAndCancelItOnShutdown() {
        // The autowired bean proves LibAutoConfiguration alone provides the scope (no component scan).
        assertFalse(reqShieldCoroutineScope.coroutineContext[Job]!!.isCancelled)

        val context = AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, TestConfig::class.java)
        val scope = context.getBean("reqShieldCoroutineScope", CoroutineScope::class.java)
        assertFalse(scope.coroutineContext[Job]!!.isCancelled)

        context.close()

        assertTrue(scope.coroutineContext[Job]?.isCancelled == true)
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun service(): TestService = TestService()
    }

    open class TestService {
        val counter = AtomicInteger(0)

        @ReqShieldCacheable(cacheName = CACHE_NAME, key = "#key", timeToLiveMillis = 10_000)
        open suspend fun get(key: String): String = "value-" + counter.incrementAndGet()

        @ReqShieldCacheEvict(cacheName = CACHE_NAME, key = "#key")
        open suspend fun evict(key: String): Boolean = true

        @ReqShieldCacheEvict(cacheName = CACHE_NAME, key = "#key")
        open suspend fun evictFailing(key: String): Boolean = throw IllegalStateException("eviction target failed: $key")
    }

    companion object {
        const val CACHE_NAME = "it"
    }
}
