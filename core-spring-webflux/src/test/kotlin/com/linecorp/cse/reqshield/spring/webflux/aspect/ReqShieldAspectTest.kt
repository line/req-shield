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
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.util.ReflectionUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val asyncCache: AsyncCache<Product> = mockk()
    private val joinPoint = mockk<ProceedingJoinPoint>()
    private val methodSignature: MethodSignature = mockk()
    private val reqShieldAspect = spyk(ReqShieldAspect(asyncCache))
    private val targetObject = spyk(TestBean())
    private val method =
        ReflectionUtils.findMethod(
            TestBean::class.java,
            TestBean::cacheableWithSingleArgument.name,
            Map::class.java,
        )

    private val cacheName = "testCacheName"
    private val cacheKey = "#paramMap['x'] + #paramMap['y']"
    private val argument = mapOf("x" to "paramX", "y" to "paramY")
    private val evaulatedKey = "paramXparamY"
    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { joinPoint.signature } returns methodSignature

        every { methodSignature.method } returns method
        every { joinPoint.args } returns arrayOf(argument)
        every { joinPoint.target } returns targetObject

        val reqShieldCacheable: ReqShieldCacheable = mockk()
        every { reqShieldCacheable.key } returns cacheKey
        every { reqShieldCacheable.timeToLiveMillis } returns 60
        every { reqShieldCacheable.isLocalLock } returns false
        every { reqShieldCacheable.lockTimeoutMillis } returns 0
        every { reqShieldCacheable.decisionForUpdate } returns 70
        every { reqShieldCacheable.cacheName } returns "product"

        every { reqShieldAspect.getCacheableAnnotation(joinPoint) } returns
            ReqShieldCacheable(
                cacheName = cacheName,
                key = cacheKey,
                lockTimeoutMillis = 1000,
                timeToLiveMillis = 1000,
            )

        every { reqShieldAspect.getCacheEvictAnnotation(joinPoint) } returns
            ReqShieldCacheEvict(
                cacheName = cacheName,
                lockTimeoutMillis = 1000,
            )
    }

    @Test
    override fun testAspectOperationVerifyReqShieldAndCacheCreation() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        // Verify the behavior using StepVerifier
        StepVerifier
            .create(result)
            .assertNext { value ->
                assertEquals(reqShieldData.value, value)
                Assertions.assertTrue(reqShieldAspect.reqShieldMap.size == 1)
                Assertions.assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$evaulatedKey"])
            }.verifyComplete()
    }

    @Test
    override fun testAspectOperationReqShieldObjectShouldBeCreatedOnce() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

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
                Assertions.assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$evaulatedKey"])
            }.verifyComplete()
    }

    @Test
    override fun testAspectOperationCacheEviction() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { asyncCache.evict(any()) } returns Mono.just(true)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        StepVerifier
            .create(result)
            .assertNext { value ->
                assertEquals(reqShieldData.value, value)
            }.verifyComplete()

        every { joinPoint.proceed() } answers { targetObject.evictWithSingleArgument(argument) }
        val removeProductMono = reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        StepVerifier
            .create(removeProductMono)
            .assertNext { value ->
                assertTrue(value as Boolean)
            }.verifyComplete()
    }

    @Test
    override fun testCacheKeyGenerationUseGeneratedKey() {
        // Mock the cache data using mockk
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { asyncCache.get(any()) } returns Mono.just(reqShieldData)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        // Verify the behavior using StepVerifier
        StepVerifier
            .create(result)
            .assertNext { value ->
                Assertions.assertEquals(
                    evaulatedKey,
                    reqShieldAspect.getCacheableCacheKey(joinPoint),
                )
            }.verifyComplete()
    }

    @Test
    override fun testCacheKeyGenerationCacheKeyShouldBeSuppliedKey() {
        every { reqShieldAspect.getCacheableAnnotation(joinPoint) } returns
            ReqShieldCacheable(
                cacheName = cacheName,
                key = cacheKey,
            )

        Assertions.assertEquals(evaulatedKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
    }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun cacheableWithSingleArgument(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheEvict(cacheName = "TestCacheName")
        fun evictWithSingleArgument(paramMap: Map<String, String>): Mono<Boolean> = Mono.just(true)
    }
}
