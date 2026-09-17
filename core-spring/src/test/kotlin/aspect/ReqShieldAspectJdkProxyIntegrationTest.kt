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
import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.reflect.Proxy

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
