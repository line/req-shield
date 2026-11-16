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

import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.spring.webflux.cache.StoreScopeSupport
import com.linecorp.cse.reqshield.spring.webflux.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A cache bean that chooses its store from the Reactor Context, one store for each tenant, and names that store
 * with [StoreScopeSupport]. A request of one tenant then does not wait for a request of another tenant.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectStoreScopeIntegrationTest.TenantConfig::class])
class ReqShieldAspectStoreScopeIntegrationTest {
    @Autowired
    private lateinit var service: TenantService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @Test
    fun aRequestOfOneTenantShouldNotWaitForTheCreationOfAnotherTenant() {
        val cache = asyncCache as TenantAsyncCache
        val id = "p-${System.nanoTime()}"
        val finishA = CompletableFuture<String>()
        slowResults[id] = finishA
        val a = service.profile(id).contextWrite { it.put(TENANT, "A") }.toFuture()
        try {
            // Tenant A holds the creation lock of the key in its own lock store
            await().atMost(Duration.ofSeconds(2)).until { cache.lockCalls("A") == 1 }
            val startedAtNanos = System.nanoTime()

            val b = service.profile(id).contextWrite { it.put(TENANT, "B") }.block(Duration.ofSeconds(5))

            val millis = (System.nanoTime() - startedAtNanos) / 1_000_000
            assertEquals("profile-of-B", b)
            assertTrue(millis < 1_000, "the request of tenant B took $millis ms")
            // Tenant B asked its own lock store and wrote its own entry
            assertEquals(1, cache.lockCalls("B"))
            await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(listOf("profile-of-B"), cache.valuesOf("B")) }
        } finally {
            finishA.complete("profile-of-A")
        }
        assertEquals("profile-of-A", a.get(2, TimeUnit.SECONDS))
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(listOf("profile-of-A"), cache.valuesOf("A")) }
    }

    /** One in-memory cache and lock store for each tenant. The tenant of a call is read from the Reactor Context. */
    class TenantAsyncCache :
        AsyncCache<String>,
        GlobalLockSupport,
        StoreScopeSupport {
        private val stores = mapOf("A" to InMemoryAsyncCache<String>(), "B" to InMemoryAsyncCache<String>())
        private val lockCalls = mapOf("A" to AtomicInteger(), "B" to AtomicInteger())
        private val values = mapOf("A" to mutableListOf<String?>(), "B" to mutableListOf())

        fun lockCalls(tenant: String): Int = lockCalls.getValue(tenant).get()

        /** The values that were written to the cache of [tenant], in order. */
        fun valuesOf(tenant: String): List<String?> = synchronized(values) { values.getValue(tenant).toList() }

        private fun <R> inStore(action: (String, InMemoryAsyncCache<String>) -> Mono<R>): Mono<R> =
            Mono.deferContextual { context ->
                val tenant = context.get<String>(TENANT)
                action(tenant, stores.getValue(tenant))
            }

        override fun get(key: String): Mono<ReqShieldData<String>?> = inStore { _, store -> store.get(key) }

        override fun put(
            key: String,
            value: ReqShieldData<String>,
            timeToLiveMillis: Long,
        ): Mono<Boolean> =
            inStore { tenant, store ->
                synchronized(values) { values.getValue(tenant).add(value.value) }
                store.put(key, value, timeToLiveMillis)
            }

        override fun evict(key: String): Mono<Boolean> = inStore { _, store -> store.evict(key) }

        override fun globalLock(
            lockKey: String,
            token: String,
            timeToLiveMillis: Long,
        ): Mono<Boolean> =
            inStore { tenant, store ->
                lockCalls.getValue(tenant).incrementAndGet()
                store.globalLock(lockKey, token, timeToLiveMillis)
            }

        override fun globalUnLock(
            lockKey: String,
            token: String,
        ): Mono<Boolean> = inStore { _, store -> store.globalUnLock(lockKey, token) }

        override fun storeScope(context: ContextView): String = context.get(TENANT)
    }

    @Configuration
    open class TenantConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = TenantAsyncCache()

        @Bean
        open fun tenantService(): TenantService = TenantService()
    }

    open class TenantService {
        /** The result of tenant A waits for the test. The result of any other tenant is ready at once. */
        @ReqShieldCacheable(cacheName = "tenant-profile", key = "#id", isLocalLock = false, timeToLiveMillis = 10_000)
        open fun profile(id: String): Mono<String> =
            Mono.deferContextual { context ->
                val tenant = context.get<String>(TENANT)
                if (tenant == "A") Mono.fromFuture(slowResults.getValue(id)) else Mono.just("profile-of-$tenant")
            }
    }

    companion object {
        private const val TENANT = "tenant"

        /** The results that tenant A waits for, by id. Kept here: a field of the proxied service is not shared with its proxy. */
        private val slowResults = java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<String>>()
    }
}
