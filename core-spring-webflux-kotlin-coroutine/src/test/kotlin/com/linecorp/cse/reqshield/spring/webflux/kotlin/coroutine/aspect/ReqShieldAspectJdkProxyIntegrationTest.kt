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
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
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
import java.lang.reflect.Proxy
import java.time.Duration

private const val JDK_PROXY_CACHE_NAME = "jdkProxy"

/**
 * A bean that implements an interface is proxied by a JDK dynamic proxy, because
 * [LibAutoConfiguration] leaves the proxying strategy to the application. The join point then
 * reports the interface method, which carries none of the annotations, so the aspect has to
 * resolve the implementation method itself.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectJdkProxyIntegrationTest.TestConfig::class])
class ReqShieldAspectJdkProxyIntegrationTest {
    @Autowired
    private lateinit var service: ProductNameService

    @Test
    fun annotatedSuspendMethodShouldBeResolvedBehindAJdkDynamicProxy() =
        runBlocking {
            assertTrue(Proxy.isProxyClass(service.javaClass), "expected a JDK dynamic proxy but was ${service.javaClass}")
            assertEquals("product-1", service.findName("1"))
        }

    /**
     * Both beans report the same interface method to the aspect. Only the target class tells their annotations
     * apart, so each must get its own cache name, its own entries and its own ReqShield. The context is built here,
     * so this test shares no aspect with the cached context of the class.
     */
    @Test
    fun implementationsOfOneInterfaceShouldEachUseTheirOwnAnnotation() =
        runBlocking {
            AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, TwoLabelServicesConfig::class.java).use { twoBeans ->
                val alpha = twoBeans.getBean("alphaLabelService", LabelService::class.java)
                val beta = twoBeans.getBean("betaLabelService", LabelService::class.java)
                val cache = twoBeans.getBean(InMemoryAsyncCache::class.java)
                assertTrue(Proxy.isProxyClass(alpha.javaClass) && Proxy.isProxyClass(beta.javaClass))

                assertEquals("alpha-1", alpha.label("1"))
                await().atMost(Duration.ofSeconds(5)).until { runBlocking { cache.get("alpha::1") } != null }
                assertEquals("beta-1", beta.label("1"))
                await().atMost(Duration.ofSeconds(5)).until { runBlocking { cache.get("beta::1") } != null }

                // Both entries are now hits, and each bean still reads its own
                assertEquals("alpha-1", alpha.label("1"))
                assertEquals("beta-1", beta.label("1"))

                val reqShieldMap = twoBeans.getBean(ReqShieldAspect::class.java).reqShieldMap
                val alphaMethod = AlphaLabelService::class.java.declaredMethods.single { it.name == "label" }
                val betaMethod = BetaLabelService::class.java.declaredMethods.single { it.name == "label" }
                assertEquals(setOf(alphaMethod, betaMethod), reqShieldMap.keys)
                assertNotSame(reqShieldMap[alphaMethod], reqShieldMap[betaMethod])
            }
        }

    /** Like the cacheable advice, each eviction must use the cache name of its own implementation. */
    @Test
    fun implementationsOfOneInterfaceShouldEachEvictTheirOwnEntry() =
        runBlocking {
            AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, TwoLabelServicesConfig::class.java).use { twoBeans ->
                val alpha = twoBeans.getBean("alphaLabelService", LabelService::class.java)
                val beta = twoBeans.getBean("betaLabelService", LabelService::class.java)
                val cache = twoBeans.getBean(InMemoryAsyncCache::class.java) as InMemoryAsyncCache<String>
                cache.put("alpha::1", ReqShieldData("alpha-1", 10_000), 10_000)
                cache.put("beta::1", ReqShieldData("beta-1", 10_000), 10_000)

                // Alpha resolves its eviction first, so beta must not reuse what alpha resolved
                alpha.forget("1")
                assertNull(cache.get("alpha::1"))
                assertNotNull(cache.get("beta::1"))

                beta.forget("1")
                assertNull(cache.get("beta::1"))
            }
        }

    @Configuration
    open class TwoLabelServicesConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun alphaLabelService(): LabelService = AlphaLabelService()

        @Bean
        open fun betaLabelService(): LabelService = BetaLabelService()
    }

    interface LabelService {
        suspend fun label(id: String): String

        suspend fun forget(id: String): Boolean
    }

    class AlphaLabelService : LabelService {
        @ReqShieldCacheable(cacheName = "alpha", key = "#id", timeToLiveMillis = 10_000)
        override suspend fun label(id: String): String = "alpha-$id"

        @ReqShieldCacheEvict(cacheName = "alpha", key = "#id")
        override suspend fun forget(id: String): Boolean = true
    }

    class BetaLabelService : LabelService {
        @ReqShieldCacheable(cacheName = "beta", key = "#id", timeToLiveMillis = 10_000)
        override suspend fun label(id: String): String = "beta-$id"

        @ReqShieldCacheEvict(cacheName = "beta", key = "#id")
        override suspend fun forget(id: String): Boolean = true
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun productNameService(): ProductNameService = ProductNameServiceImpl()
    }

    interface ProductNameService {
        suspend fun findName(id: String): String
    }

    class ProductNameServiceImpl : ProductNameService {
        @ReqShieldCacheable(cacheName = JDK_PROXY_CACHE_NAME, key = "#id", timeToLiveMillis = 10_000)
        override suspend fun findName(id: String): String = "product-$id"
    }
}
