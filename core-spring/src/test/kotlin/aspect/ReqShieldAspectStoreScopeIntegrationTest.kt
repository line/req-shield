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

import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.spring.cache.StoreScopeSupport
import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The aspect hands the store scope of a cache bean with [StoreScopeSupport] to the ReqShield of each method. */
class ReqShieldAspectStoreScopeIntegrationTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var service: ScopedService
    private lateinit var cache: ScopedCache

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, ScopedConfig::class.java)
        service = context.getBean(ScopedService::class.java)
        cache = context.getBean(ScopedCache::class.java)
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    @Test
    fun theStoreScopeOfTheCacheBeanShouldBeAskedOnceForARequestWithoutAnEntryAndNotForAFreshEntry() {
        assertEquals("value-of-id", service.get("id"))
        assertEquals(1, cache.scopeCalls.get())
        assertEquals("main-thread", cache.lastScopeThread)
        await().atMost(Duration.ofSeconds(5)).until { cache.get("scoped::id") != null }

        repeat(3) { assertEquals("value-of-id", service.get("id")) }

        assertEquals(1, cache.scopeCalls.get())
    }

    @Configuration
    open class ScopedConfig {
        @Bean
        open fun reqShieldCache(): ScopedCache = ScopedCache()

        @Bean
        open fun scopedService(): ScopedService = ScopedService()
    }

    open class ScopedService {
        @ReqShieldCacheable(cacheName = "scoped", key = "#id", timeToLiveMillis = 10_000)
        open fun get(id: String): String = "value-of-$id"
    }

    class ScopedCache :
        ReqShieldCache<String>,
        StoreScopeSupport {
        private val store = ConcurrentHashMap<String, ReqShieldData<String>>()
        val scopeCalls = AtomicInteger()

        /** "main-thread" when the last scope call ran on the thread of the test, which is the caller. */
        @Volatile
        var lastScopeThread: String? = null

        override fun get(key: String): ReqShieldData<String>? = store[key]

        override fun put(
            key: String,
            value: ReqShieldData<String>,
            timeToLiveMillis: Long,
        ) {
            store[key] = value
        }

        override fun evict(key: String): Boolean? = store.remove(key) != null

        override fun storeScope(): String {
            scopeCalls.incrementAndGet()
            lastScopeThread = if (Thread.currentThread() === callerThread) "main-thread" else Thread.currentThread().name
            return "tenant"
        }

        private val callerThread = Thread.currentThread()
    }
}
