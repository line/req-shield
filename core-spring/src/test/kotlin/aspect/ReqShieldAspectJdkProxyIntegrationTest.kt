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
import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * A bean that implements an interface is proxied by a JDK dynamic proxy under the default
 * [LibAutoConfiguration] settings. The join point then reports the interface method, which carries
 * none of the annotations, so the aspect has to resolve the implementation method itself.
 */
class ReqShieldAspectJdkProxyIntegrationTest {
    private lateinit var context: AnnotationConfigApplicationContext

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, TestConfig::class.java)
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    @Test
    fun annotatedMethodShouldBeResolvedBehindAJdkDynamicProxy() {
        val service = context.getBean(ProductNameService::class.java)

        assertTrue(Proxy.isProxyClass(service.javaClass), "expected a JDK dynamic proxy but was ${service.javaClass}")
        assertEquals("product-1", service.findName("1"))
    }

    /**
     * Both beans report the same interface method to the aspect. Only the target class tells their annotations
     * apart, so each must get its own cache name, its own entries and its own ReqShield.
     */
    @Test
    fun implementationsOfOneInterfaceShouldEachUseTheirOwnAnnotation() {
        AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, TwoLabelServicesConfig::class.java).use { twoBeans ->
            val alpha = twoBeans.getBean("alphaLabelService", LabelService::class.java)
            val beta = twoBeans.getBean("betaLabelService", LabelService::class.java)
            val cache = twoBeans.getBean(ReqShieldAspectIntegrationTest.InMemoryReqShieldCache::class.java)
            assertTrue(Proxy.isProxyClass(alpha.javaClass) && Proxy.isProxyClass(beta.javaClass))

            assertEquals("alpha-1", alpha.label("1"))
            await().atMost(Duration.ofSeconds(5)).until { cache.get("alpha::1") != null }
            assertEquals("beta-1", beta.label("1"))
            await().atMost(Duration.ofSeconds(5)).until { cache.get("beta::1") != null }

            // Both entries are now hits, and each bean still reads its own
            assertEquals("alpha-1", alpha.label("1"))
            assertEquals("beta-1", beta.label("1"))

            val reqShieldMap = twoBeans.getBean(ReqShieldAspect::class.java).reqShieldMap
            val alphaMethod = AlphaLabelService::class.java.getMethod("label", String::class.java)
            val betaMethod = BetaLabelService::class.java.getMethod("label", String::class.java)
            assertEquals(setOf(alphaMethod, betaMethod), reqShieldMap.keys)
            assertNotSame(reqShieldMap[alphaMethod], reqShieldMap[betaMethod])
        }
    }

    /** Like the cacheable advice, each eviction must use the cache name of its own implementation. */
    @Test
    fun implementationsOfOneInterfaceShouldEachEvictTheirOwnEntry() {
        AnnotationConfigApplicationContext(LibAutoConfiguration::class.java, TwoLabelServicesConfig::class.java).use { twoBeans ->
            val alpha = twoBeans.getBean("alphaLabelService", LabelService::class.java)
            val beta = twoBeans.getBean("betaLabelService", LabelService::class.java)
            val cache = twoBeans.getBean(ReqShieldAspectIntegrationTest.InMemoryReqShieldCache::class.java)
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
        open fun reqShieldCache(): ReqShieldAspectIntegrationTest.InMemoryReqShieldCache =
            ReqShieldAspectIntegrationTest.InMemoryReqShieldCache()

        @Bean
        open fun alphaLabelService(): LabelService = AlphaLabelService()

        @Bean
        open fun betaLabelService(): LabelService = BetaLabelService()
    }

    interface LabelService {
        fun label(id: String): String

        fun forget(id: String)
    }

    class AlphaLabelService : LabelService {
        @ReqShieldCacheable(cacheName = "alpha", key = "#id", timeToLiveMillis = 10_000)
        override fun label(id: String): String = "alpha-$id"

        @ReqShieldCacheEvict(cacheName = "alpha", key = "#id")
        override fun forget(id: String) = Unit
    }

    class BetaLabelService : LabelService {
        @ReqShieldCacheable(cacheName = "beta", key = "#id", timeToLiveMillis = 10_000)
        override fun label(id: String): String = "beta-$id"

        @ReqShieldCacheEvict(cacheName = "beta", key = "#id")
        override fun forget(id: String) = Unit
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun reqShieldCache(): ReqShieldAspectIntegrationTest.InMemoryReqShieldCache =
            ReqShieldAspectIntegrationTest.InMemoryReqShieldCache()

        @Bean
        open fun productNameService(): ProductNameService = ProductNameServiceImpl()
    }

    interface ProductNameService {
        fun findName(id: String): String
    }

    class ProductNameServiceImpl : ProductNameService {
        @ReqShieldCacheable(cacheName = "jdkProxy", key = "#id", timeToLiveMillis = 10_000)
        override fun findName(id: String): String = "product-$id"
    }
}
