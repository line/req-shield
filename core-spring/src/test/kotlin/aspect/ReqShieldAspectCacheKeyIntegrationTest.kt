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
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How the aspect turns arguments, a SpEL key and a key generator result into a cache key, through the real proxy. */
class ReqShieldAspectCacheKeyIntegrationTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: KeyService
    private lateinit var cache: KeyCache

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, KeyConfig::class.java)
        service = context.getBean(KeyService::class.java)
        cache = context.getBean(KeyCache::class.java)
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    private fun awaitEntries(count: Int) = await().atMost(Duration.ofSeconds(5)).until { cache.keys().size == count }

    @Test
    fun separatorInsideAnArgumentShouldNotShareAnEntry() {
        val first = service.document("a, b", "c")
        awaitEntries(1)
        val second = service.document("a", "b, c")

        assertEquals("doc(a, b|c)", first)
        assertEquals("doc(a|b, c)", second)
    }

    @Test
    fun nullArgumentShouldNotShareAnEntryWithTheTextNull() {
        val first = service.document(null, "x")
        awaitEntries(1)
        val second = service.document("null", "x")

        assertEquals("doc(null|x)", first)
        assertEquals("doc(null|x)", second)
        assertEquals(2, service.callCount())
    }

    @Test
    fun ordinaryArgumentsShouldKeepTheSimpleKeyLayoutAndHitTheEntry() {
        service.document("tenant", "7")
        awaitEntries(1)
        service.document("tenant", "7")

        assertEquals(setOf("doc::SimpleKey [tenant, 7]"), cache.keys())
        assertEquals(1, service.callCount())
    }

    @Test
    fun argumentWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() {
        val error = assertThrows<IllegalArgumentException> { service.search(Query("Aa")) }

        assertTrue(error.message!!.contains("toString()"), error.message)
        assertEquals(0, service.callCount())
        assertTrue(cache.keys().isEmpty())
    }

    @Test
    fun spelKeyWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() {
        assertThrows<IllegalArgumentException> { service.searchBySpel(Query("Aa")) }

        assertEquals(0, service.callCount())
    }

    @Test
    fun spelListKeyShouldNotShareAnEntryWhenAnElementHoldsTheSeparator() {
        val first = service.documentBySpelList("a,b", "c")
        awaitEntries(1)
        val second = service.documentBySpelList("a", "b,c")

        assertEquals("doc(a,b|c)", first)
        assertEquals("doc(a|b,c)", second)
    }

    @Test
    fun spelKeyThatSpringCannotConvertShouldUseItsOwnToString() {
        service.byFilter(Filter(1, 2))
        awaitEntries(1)
        service.byFilter(Filter(1, 2))
        service.byFilter(Filter(1, 3))
        awaitEntries(2)

        assertEquals(setOf("filter::Filter(tenant=1, id=2)", "filter::Filter(tenant=1, id=3)"), cache.keys())
        assertEquals(2, service.callCount())
    }

    @Test
    fun spelKeyThatSpringConvertsShouldKeepTheConvertedText() {
        service.byNumber(42L)
        service.byColor(Color.RED)
        awaitEntries(2)

        // Spring converts an enum by its name, not by its toString()
        assertEquals(setOf("number::42", "color::RED"), cache.keys())
    }

    @Test
    fun spelKeyWithoutItsOwnToStringShouldNameTheClassInTheError() {
        val error = assertThrows<IllegalArgumentException> { service.searchBySpel(Query("Aa")) }

        // The blank-key check throws the same exception type, so the message tells the two apart
        assertTrue(error.message!!.contains("Query") && error.message!!.contains("toString()"), error.message)
    }

    @Test
    fun keyGeneratorResultWithoutItsOwnToStringShouldBeRejectedBeforeTheMethodRuns() {
        val error = assertThrows<IllegalArgumentException> { service.searchByGenerator("Aa") }

        assertTrue(error.message!!.contains("Query") && error.message!!.contains("toString()"), error.message)
        assertEquals(0, service.callCount())
    }

    @Test
    fun keyGeneratorResultShouldBeTheKeyAsItIs() {
        service.byGenerator("a, b")
        awaitEntries(1)

        assertEquals(setOf("generated::generated-a, b"), cache.keys())
    }

    @Test
    fun evictShouldTargetTheEntryOfTheSameSpelListKey() {
        service.documentBySpelList("a,b", "c")
        service.documentBySpelList("a", "b,c")
        awaitEntries(2)

        service.evictDocumentBySpelList("a,b", "c")

        assertEquals(setOf("doc::[a, b\\,c]"), cache.keys())
    }

    @Test
    fun spelArrayAndMapKeysShouldBeWrittenByContent() {
        service.bySpelArray("a,b", "c")
        service.bySpelMap("a,b", "c")
        awaitEntries(2)

        assertEquals(setOf("array::[a\\,b, c]", "map::{tenant=a\\,b, id=c}"), cache.keys())
    }

    @Test
    fun evictMethodShouldNotRunWhenItsKeyIsRejected() {
        assertThrows<IllegalArgumentException> { service.evictSearch(Query("Aa")) }

        // The key is built before the method is called, so a rejected key stops the method body too
        assertEquals(0, service.callCount())
    }

    @Test
    fun evictShouldTargetTheEntryOfTheSameArguments() {
        service.document("a, b", "c")
        service.document("a", "b, c")
        awaitEntries(2)

        service.evictDocument("a, b", "c")

        assertEquals(setOf("doc::SimpleKey [a, b\\, c]"), cache.keys())
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

    enum class Color {
        RED,
        ;

        override fun toString() = "the color red"
    }

    @Configuration
    open class KeyConfig {
        @Bean
        open fun reqShieldCache(): KeyCache = KeyCache()

        @Bean
        open fun queryKeyGenerator(): KeyGenerator = KeyGenerator { _, _, params -> Query(params[0] as String) }

        @Bean
        open fun textKeyGenerator(): KeyGenerator = KeyGenerator { _, _, params -> "generated-" + params[0] }

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
        ): String {
            counter.incrementAndGet()
            return "doc($tenant|$id)"
        }

        @ReqShieldCacheable(cacheName = "doc", key = "{#tenant, #id}")
        open fun documentBySpelList(
            tenant: String,
            id: String,
        ): String {
            counter.incrementAndGet()
            return "doc($tenant|$id)"
        }

        @ReqShieldCacheEvict(cacheName = "doc", key = "{#tenant, #id}")
        open fun evictDocumentBySpelList(
            tenant: String,
            id: String,
        ) {
            // nothing to do: the aspect evicts after this method returns
        }

        @ReqShieldCacheable(cacheName = "filter", key = "#filter")
        open fun byFilter(filter: Filter): String {
            counter.incrementAndGet()
            return "result-of-$filter"
        }

        @ReqShieldCacheable(cacheName = "number", key = "#number")
        open fun byNumber(number: Long): String = "result-of-$number"

        @ReqShieldCacheable(cacheName = "color", key = "#color")
        open fun byColor(color: Color): String = "result-of-" + color.name

        @ReqShieldCacheable(cacheName = "search", keyGenerator = "queryKeyGenerator")
        open fun searchByGenerator(text: String): String {
            counter.incrementAndGet()
            return "result-of-$text"
        }

        @ReqShieldCacheable(cacheName = "generated", keyGenerator = "textKeyGenerator")
        open fun byGenerator(text: String): String = "result-of-$text"

        @ReqShieldCacheable(cacheName = "array", key = "new String[]{#tenant, #id}")
        open fun bySpelArray(
            tenant: String,
            id: String,
        ): String = "array($tenant|$id)"

        @ReqShieldCacheable(cacheName = "map", key = "{tenant: #tenant, id: #id}")
        open fun bySpelMap(
            tenant: String,
            id: String,
        ): String = "map($tenant|$id)"

        @ReqShieldCacheEvict(cacheName = "search")
        open fun evictSearch(query: Query) {
            counter.incrementAndGet()
        }

        @ReqShieldCacheEvict(cacheName = "doc")
        open fun evictDocument(
            tenant: String?,
            id: String,
        ) {
            // nothing to do: the aspect evicts after this method returns
        }

        @ReqShieldCacheable(cacheName = "search")
        open fun search(query: Query): String {
            counter.incrementAndGet()
            return "result-of-" + query.text
        }

        @ReqShieldCacheable(cacheName = "search", key = "#query")
        open fun searchBySpel(query: Query): String {
            counter.incrementAndGet()
            return "result-of-" + query.text
        }
    }

    class KeyCache : ReqShieldCache<String> {
        private val store = ConcurrentHashMap<String, ReqShieldData<String>>()

        fun keys(): Set<String> = store.keys.toSet()

        override fun get(key: String): ReqShieldData<String>? = store[key]

        override fun put(
            key: String,
            value: ReqShieldData<String>,
            timeToLiveMillis: Long,
        ) {
            store[key] = value
        }

        override fun evict(key: String): Boolean? = store.remove(key) != null
    }
}
