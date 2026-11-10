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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Keys of methods that name neither a key nor a key generator, through the real proxy. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectCacheKeyIntegrationTest.KeyConfig::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ReqShieldAspectCacheKeyIntegrationTest {
    @Autowired
    private lateinit var service: KeyService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    private fun cached(key: String) = asyncCache.get(key).block()

    private fun awaitEntry(key: String) = await().atMost(Duration.ofSeconds(5)).until { cached(key) != null }

    @Test
    fun separatorInsideAnArgumentShouldNotShareAnEntry() {
        val first = service.document("a, b", "c").block()
        awaitEntry("doc::SimpleKey [a\\, b, c]")
        val second = service.document("a", "b, c").block()

        assertEquals("doc(a, b|c)", first)
        assertEquals("doc(a|b, c)", second)
    }

    @Test
    fun nullArgumentShouldNotShareAnEntryWithTheTextNull() {
        service.document(null, "x").block()
        awaitEntry("doc::SimpleKey [\\0, x]")
        service.document("null", "x").block()

        assertEquals(2, service.callCount())
    }

    @Test
    fun ordinaryArgumentsShouldKeepTheSimpleKeyLayoutAndHitTheEntry() {
        service.document("tenant", "7").block()
        awaitEntry("doc::SimpleKey [tenant, 7]")
        service.document("tenant", "7").block()

        assertEquals(1, service.callCount())
    }

    @Test
    fun argumentWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() {
        val error = assertThrows<IllegalArgumentException> { service.search(Query("Aa")).block() }

        assertTrue(error.message!!.contains("toString()"), error.message)
        assertEquals(0, service.callCount())
    }

    @Test
    fun spelKeyWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() {
        assertThrows<IllegalArgumentException> { service.searchBySpel(Query("Aa")).block() }

        assertEquals(0, service.callCount())
    }

    @Test
    fun evictShouldTargetTheEntryOfTheSameArguments() {
        service.document("a, b", "c").block()
        service.document("a", "b, c").block()
        awaitEntry("doc::SimpleKey [a\\, b, c]")
        awaitEntry("doc::SimpleKey [a, b\\, c]")

        service.evictDocument("a, b", "c").block()

        assertNull(cached("doc::SimpleKey [a\\, b, c]"))
        assertNotNull(cached("doc::SimpleKey [a, b\\, c]"))
    }

    /** equals/hashCode are value based, but toString is the one of Object: "Aa" and "BB" give the same text. */
    class Query(
        val text: String,
    ) {
        override fun equals(other: Any?) = other is Query && other.text == text

        override fun hashCode() = text.hashCode()
    }

    @Configuration
    open class KeyConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun keyService(): KeyService = KeyService()
    }

    open class KeyService {
        private val counter = AtomicInteger(0)

        // read through a method: the bean is a CGLIB proxy whose own fields are never initialized
        open fun callCount(): Int = counter.get()

        @ReqShieldCacheable(cacheName = "doc")
        open fun document(
            tenant: String?,
            id: String,
        ): Mono<String> = Mono.fromCallable { counter.incrementAndGet().let { "doc($tenant|$id)" } }

        @ReqShieldCacheEvict(cacheName = "doc")
        open fun evictDocument(
            tenant: String?,
            id: String,
        ): Mono<Boolean> = Mono.just(true)

        @ReqShieldCacheable(cacheName = "search")
        open fun search(query: Query): Mono<String> = Mono.fromCallable { counter.incrementAndGet().let { "result-of-" + query.text } }

        @ReqShieldCacheable(cacheName = "search", key = "#query")
        open fun searchBySpel(query: Query): Mono<String> =
            Mono.fromCallable { counter.incrementAndGet().let { "result-of-" + query.text } }
    }
}
