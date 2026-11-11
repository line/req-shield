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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/** How the aspect turns arguments, a SpEL key and a key generator result into a cache key, through the real proxy. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectCacheKeyIntegrationTest.KeyConfig::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ReqShieldAspectCacheKeyIntegrationTest {
    @Autowired
    private lateinit var service: KeyService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    private suspend fun awaitEntry(key: String) =
        withTimeout(5_000) {
            while (asyncCache.get(key) == null) delay(5)
        }

    @Test
    fun separatorInsideAnArgumentShouldNotShareAnEntry() =
        runBlocking {
            val first = service.document("a, b", "c")
            awaitEntry("doc::SimpleKey [a\\, b, c]")
            val second = service.document("a", "b, c")

            assertEquals("doc(a, b|c)", first)
            assertEquals("doc(a|b, c)", second)
        }

    @Test
    fun nullArgumentShouldNotShareAnEntryWithTheTextNull() =
        runBlocking {
            service.document(null, "x")
            awaitEntry("doc::SimpleKey [\\0, x]")
            service.document("null", "x")

            assertEquals(2, service.callCount())
        }

    @Test
    fun singleArgumentShouldBeTheKeyWithoutTheContinuation() =
        runBlocking {
            service.single("product-1")
            awaitEntry("single::product-1")
            service.single("product-1")

            assertEquals(1, service.callCount())
        }

    @Test
    fun ordinaryArgumentsShouldKeepTheSimpleKeyLayoutAndHitTheEntry() =
        runBlocking {
            service.document("tenant", "7")
            awaitEntry("doc::SimpleKey [tenant, 7]")
            service.document("tenant", "7")

            assertEquals(1, service.callCount())
        }

    @Test
    fun argumentWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() =
        runBlocking {
            val error = assertFailsWith<IllegalArgumentException> { service.search(Query("Aa")) }

            assertTrue(error.message!!.contains("toString()"), error.message)
            assertEquals(0, service.callCount())
        }

    @Test
    fun spelKeyWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { service.searchBySpel(Query("Aa")) }

            assertEquals(0, service.callCount())
        }

    @Test
    fun spelListKeyShouldNotShareAnEntryWhenAnElementHoldsTheSeparator() =
        runBlocking {
            val first = service.documentBySpelList("a,b", "c")
            awaitEntry("doc::[a\\,b, c]")
            val second = service.documentBySpelList("a", "b,c")

            assertEquals("doc(a,b|c)", first)
            assertEquals("doc(a|b,c)", second)
        }

    @Test
    fun evictShouldTargetTheEntryOfTheSameSpelListKey() =
        runBlocking {
            service.documentBySpelList("a,b", "c")
            service.documentBySpelList("a", "b,c")
            awaitEntry("doc::[a\\,b, c]")
            awaitEntry("doc::[a, b\\,c]")

            service.evictDocumentBySpelList("a,b", "c")

            assertNull(asyncCache.get("doc::[a\\,b, c]"))
            assertNotNull(asyncCache.get("doc::[a, b\\,c]"))
        }

    @Test
    fun spelKeyThatSpringCannotConvertShouldUseItsOwnToString() =
        runBlocking {
            service.byFilter(Filter(1, 2))
            awaitEntry("filter::Filter(tenant=1, id=2)")
            service.byFilter(Filter(1, 2))

            assertEquals(1, service.callCount())
        }

    @Test
    fun keyGeneratorResultWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() =
        runBlocking {
            val error = assertFailsWith<IllegalArgumentException> { service.searchByGenerator("Aa") }

            assertTrue(error.message!!.contains("Query") && error.message!!.contains("toString()"), error.message)
            assertEquals(0, service.callCount())
        }

    @Test
    fun evictMethodShouldNotRunWhenItsKeyIsRejected() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { service.evictSearch(Query("Aa")) }

            // The key is built before the method is called, so a rejected key stops the method body too
            assertEquals(0, service.callCount())
        }

    @Test
    fun evictShouldTargetTheEntryOfTheSameArguments() =
        runBlocking {
            service.document("a, b", "c")
            service.document("a", "b, c")
            awaitEntry("doc::SimpleKey [a\\, b, c]")
            awaitEntry("doc::SimpleKey [a, b\\, c]")

            service.evictDocument("a, b", "c")

            assertNull(asyncCache.get("doc::SimpleKey [a\\, b, c]"))
            assertNotNull(asyncCache.get("doc::SimpleKey [a, b\\, c]"))
        }

    /** equals/hashCode are value based, but toString is the one of Object: "Aa" and "BB" give the same text. */
    class Query(
        val text: String,
    ) {
        override fun equals(other: Any?) = other is Query && other.text == text

        override fun hashCode() = text.hashCode()
    }

    data class Filter(
        val tenant: Int,
        val id: Int,
    )

    @Configuration
    open class KeyConfig {
        @Bean
        open fun queryKeyGenerator(): KeyGenerator = KeyGenerator { _, _, params -> Query(params[0] as String) }

        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun keyService(): KeyService = KeyService()
    }

    open class KeyService {
        private val counter = AtomicInteger(0)

        // read through a method: the bean is a CGLIB proxy whose own fields are never initialized
        open fun callCount(): Int = counter.get()

        @ReqShieldCacheable(cacheName = "single")
        open suspend fun single(id: String): String = counter.incrementAndGet().let { "single($id)" }

        @ReqShieldCacheable(cacheName = "doc")
        open suspend fun document(
            tenant: String?,
            id: String,
        ): String = counter.incrementAndGet().let { "doc($tenant|$id)" }

        @ReqShieldCacheable(cacheName = "doc", key = "{#tenant, #id}")
        open suspend fun documentBySpelList(
            tenant: String,
            id: String,
        ): String = counter.incrementAndGet().let { "doc($tenant|$id)" }

        @ReqShieldCacheEvict(cacheName = "doc", key = "{#tenant, #id}")
        open suspend fun evictDocumentBySpelList(
            tenant: String,
            id: String,
        ): Boolean = true

        @ReqShieldCacheable(cacheName = "filter", key = "#filter")
        open suspend fun byFilter(filter: Filter): String = counter.incrementAndGet().let { "result-of-$filter" }

        @ReqShieldCacheable(cacheName = "search", keyGenerator = "queryKeyGenerator")
        open suspend fun searchByGenerator(text: String): String = counter.incrementAndGet().let { "result-of-$text" }

        @ReqShieldCacheEvict(cacheName = "search")
        open suspend fun evictSearch(query: Query): Boolean = counter.incrementAndGet() > 0

        @ReqShieldCacheEvict(cacheName = "doc")
        open suspend fun evictDocument(
            tenant: String?,
            id: String,
        ): Boolean = true

        @ReqShieldCacheable(cacheName = "search")
        open suspend fun search(query: Query): String = counter.incrementAndGet().let { "result-of-" + query.text }

        @ReqShieldCacheable(cacheName = "search", key = "#query")
        open suspend fun searchBySpel(query: Query): String = counter.incrementAndGet().let { "result-of-" + query.text }
    }
}
