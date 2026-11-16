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

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.StoreScopeSupport
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The aspect hands the store scope of a cache bean with [StoreScopeSupport] to the ReqShield of each method. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectStoreScopeIntegrationTest.ScopedConfig::class])
class ReqShieldAspectStoreScopeIntegrationTest {
    @Autowired
    private lateinit var service: ScopedService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @Test
    fun theStoreScopeOfTheCacheBeanShouldGetTheContextOfTheCallerOnceAndNotForAFreshEntry() =
        runBlocking<Unit> {
            val cache = asyncCache as ScopedCache
            val id = "p-${System.nanoTime()}"

            assertEquals("value-of-$id", withContext(Tenant("A")) { service.get(id) })

            // One call for the request without an entry, with the context element of the caller
            assertEquals(listOf("A"), cache.scopes.toList())
            withTimeout(5_000) {
                while (cache.get("scoped::$id") == null) delay(5)
            }

            repeat(3) { assertEquals("value-of-$id", withContext(Tenant("A")) { service.get(id) }) }

            assertEquals(listOf("A"), cache.scopes.toList())
        }

    class Tenant(
        val id: String,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tenant>
    }

    @Configuration
    open class ScopedConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = ScopedCache()

        @Bean
        open fun scopedService(): ScopedService = ScopedService()
    }

    open class ScopedService {
        @ReqShieldCacheable(cacheName = "scoped", key = "#id", timeToLiveMillis = 10_000)
        open suspend fun get(id: String): String = "value-of-$id"
    }

    class ScopedCache :
        AsyncCache<String>,
        StoreScopeSupport {
        private val store = ConcurrentHashMap<String, ReqShieldData<String>>()

        /** The scope that each call of [storeScope] answered, in order. */
        val scopes = CopyOnWriteArrayList<String>()

        override suspend fun get(key: String): ReqShieldData<String>? = store[key]

        override suspend fun put(
            key: String,
            value: ReqShieldData<String>,
            timeToLiveMillis: Long,
        ): Boolean {
            store[key] = value
            return true
        }

        override suspend fun evict(key: String): Boolean = store.remove(key) != null

        override fun storeScope(context: CoroutineContext): String = context[Tenant]!!.id.also { scopes.add(it) }
    }
}
