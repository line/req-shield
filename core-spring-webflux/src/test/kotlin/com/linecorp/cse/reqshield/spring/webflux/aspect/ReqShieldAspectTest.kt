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
import com.linecorp.cse.reqshield.support.BaseReqShieldModuleSupportTest
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanFactory
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.interceptor.SimpleKeyGenerator
import org.springframework.util.ReflectionUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val asyncCache: AsyncCache<Product> = mockk()
    private val joinPoint = mockk<ProceedingJoinPoint>()
    private val reqShieldAspect = spyk(ReqShieldAspect(asyncCache))
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
        every { joinPoint.args } returns arrayOf(argument)
        every { joinPoint.target } returns targetObject

        reqShieldAspect.setBeanFactory(beanFactory)
    }

    @Test
    override fun verifyReqShieldCacheCreation() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        // Verify the behavior using StepVerifier
        StepVerifier
            .create(result)
            .assertNext { value ->
                assertEquals(reqShieldData.value, value)
                Assertions.assertTrue(reqShieldAspect.reqShieldMap.size == 1)
                Assertions.assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$spelEvaluatedKey"])
            }.verifyComplete()
    }

    @Test
    override fun reqShieldObjectShouldBeCreatedOnce() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!

        val flux =
            Flux
                .range(1, 20)
                .flatMap {
                    reqShieldAspect
                        .aroundTargetCacheable(joinPoint)
                        .subscribeOn(Schedulers.boundedElastic())
                }.collectList()

        StepVerifier
            .create(flux)
            .assertNext { productList ->
                Assertions.assertTrue(reqShieldAspect.reqShieldMap.size == 1)
                println(reqShieldAspect.reqShieldMap.keys().toList())
                Assertions.assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$spelEvaluatedKey"])
            }.verifyComplete()
    }

    @Test
    override fun verifyReqShieldCacheEviction() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithDefaultKeyGenerator.name,
                Map::class.java,
            )!!
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        StepVerifier
            .create(result)
            .assertNext { value ->
                assertEquals(reqShieldData.value, value)
            }.verifyComplete()

        // Validate cache eviction
        every { asyncCache.evict(any()) } returns Mono.just(true)
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::evict.name,
                Map::class.java,
            )!!
        every { joinPoint.proceed() } answers { targetObject.evict(argument) }

        val removeProductMono = reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        StepVerifier
            .create(removeProductMono)
            .assertNext { value ->
                assertTrue(value as Boolean)
            }.verifyComplete()
    }

    @Test
    override fun verifyCacheKeyGenerationWithSpEL() {
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithCustomKey.name,
                Map::class.java,
            )!!

        // when, then
        Assertions.assertEquals(
            spelEvaluatedKey,
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

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
        Assertions.assertEquals(
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
        Assertions.assertEquals(
            SimpleKeyGenerator.generateKey(arrayOf(argument)).toString(),
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun cacheableWithCustomKey(paramMap: Map<String, String>): Mono<Product> = Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName")
        fun cacheableWithDefaultKeyGenerator(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName", keyGenerator = "customGenerator")
        fun cacheableWithKeyGenerator(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheEvict(cacheName = "TestCacheName")
        fun evict(paramMap: Map<String, String>): Mono<Boolean> = Mono.just(true)
    }

    class CustomGenerator : KeyGenerator {
        override fun generate(
            target: Any,
            method: Method,
            vararg params: Any?,
        ): Any = "KeyGeneratedByGenerator"
    }
}
