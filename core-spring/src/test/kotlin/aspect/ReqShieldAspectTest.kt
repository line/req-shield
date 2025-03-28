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
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.BaseReqShieldModuleSupportTest
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.aspectj.lang.ProceedingJoinPoint
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.interceptor.SimpleKeyGenerator
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(ReqShieldAspectTest::class.java)

class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val reqShieldCache: ReqShieldCache<Product> = mockk()
    private val joinPoint = mockk<ProceedingJoinPoint>()
    private val reqShieldAspect = spyk(ReqShieldAspect(reqShieldCache))
    private val targetObject = spyk(TestBean())
    private val argument = mapOf("x" to "paramX", "y" to "paramY")

    private val cacheName = "TestCacheName"
    private val cacheKeyGenerator = "customGenerator"
    private val spelEvaluatedKey = "paramXparamY"
    private val keyGeneratorKey = "KeyGeneratedByGenerator"

    private val beanFactory = mockk<BeanFactory>()

    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { joinPoint.target } returns targetObject
        every { joinPoint.args } returns arrayOf(argument)

        reqShieldAspect.setBeanFactory(beanFactory)
    }

    @Test
    override fun verifyReqShieldCacheCreation() {
        // given
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { reqShieldCache.get(any()) } returns reqShieldData
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!

        // when
        val result = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        Awaitility.await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            // then
            assertEquals(reqShieldData.value, result)
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$spelEvaluatedKey"])
        }
    }

    @Test
    override fun reqShieldObjectShouldBeCreatedOnce() {
        // given
        every { reqShieldCache.get(any()) } returns ReqShieldData(methodReturn, 1000)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!

        // when
        val executorService = Executors.newFixedThreadPool(10)
        for (i in 1..10) {
            executorService.submit {
                reqShieldAspect.aroundReqShieldCacheable(joinPoint)
            }
        }

        Awaitility.await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            // then
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$spelEvaluatedKey"])
        }
    }

    @Test
    override fun verifyReqShieldCacheEviction() {
        // given
        val reqShieldData = ReqShieldData(methodReturn, 10000)
        every { reqShieldCache.get(any()) } returns reqShieldData
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }

        val cachedResult = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        assertEquals(reqShieldData.value, cachedResult)

        // Validate cache eviction
        every { reqShieldCache.evict(any()) } returns true
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::evict.name,
                Map::class.java,
            )!!

        // when
        reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        // then
        verify(exactly = 1) { reqShieldCache.evict(any()) }
    }

    @Test
    override fun verifyCacheKeyGenerationWithSpEL() {
        // given
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!

        // when, then
        assertEquals(
            spelEvaluatedKey,
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    override fun verifyCacheKeyGenerationWithKeyGenerator() {
        // given
        every { beanFactory.getBean(cacheKeyGenerator, KeyGenerator::class.java) } returns
            CustomGenerator()
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithKeyGenerator.name,
                Map::class.java,
            )!!

        // when, then
        assertEquals(
            keyGeneratorKey,
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    override fun verifyCacheKeyGenerationWithDefaultGenerator() {
        // given
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithDefaultKeyGenerator.name,
                Map::class.java,
            )!!

        // when, then
        assertEquals(
            SimpleKeyGenerator.generateKey(arrayOf(argument)).toString(),
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun cacheableWithCustomKey(paramMap: Map<String, String>): String {
            log.debug("cacheableWithCustomKey method invoked")
            return "ReturnValue: $paramMap"
        }

        @ReqShieldCacheable(cacheName = "TestCacheName")
        fun cacheableWithDefaultKeyGenerator(paramMap: Map<String, String>): String {
            log.debug("cacheableWithDefaultKeyGenerator method invoked")
            return "ReturnValue: $paramMap"
        }

        @ReqShieldCacheable(cacheName = "TestCacheName", keyGenerator = "customGenerator")
        fun cacheableWithKeyGenerator(paramMap: Map<String, String>): String {
            log.debug("cacheableWithCustomGenerator method invoked")
            return "ReturnValue: $paramMap"
        }

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun evict(paramMap: Map<String, String>) {
            log.debug("cache eviction")
        }
    }

    class CustomGenerator : KeyGenerator {
        override fun generate(
            target: Any,
            method: Method,
            vararg params: Any?,
        ): Any = "KeyGeneratedByGenerator"
    }
}
