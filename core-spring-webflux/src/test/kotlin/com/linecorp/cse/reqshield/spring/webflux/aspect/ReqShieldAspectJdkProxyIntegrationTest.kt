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
import com.linecorp.cse.reqshield.spring.webflux.config.LibAutoConfiguration
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
import java.lang.reflect.Proxy

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
    fun annotatedMethodShouldBeResolvedBehindAJdkDynamicProxy() {
        assertTrue(Proxy.isProxyClass(service.javaClass), "expected a JDK dynamic proxy but was ${service.javaClass}")
        assertEquals("product-1", service.findName("1").block())
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun productNameService(): ProductNameService = ProductNameServiceImpl()
    }

    interface ProductNameService {
        fun findName(id: String): Mono<String>
    }

    class ProductNameServiceImpl : ProductNameService {
        @ReqShieldCacheable(cacheName = JDK_PROXY_CACHE_NAME, key = "#id", timeToLiveMillis = 10_000)
        override fun findName(id: String): Mono<String> = Mono.just("product-$id")
    }
}
