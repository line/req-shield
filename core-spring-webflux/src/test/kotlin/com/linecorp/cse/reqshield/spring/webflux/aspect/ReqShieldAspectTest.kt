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

import com.linecorp.cse.reqshield.spring.webflux.annotation.NullHandling
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.support.BaseReqShieldModuleSupportTest
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.BeanFactory
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.util.ReflectionUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val asyncCache: AsyncCache<Product> = InMemoryAsyncCache()
    private val joinPoint = mockk<ProceedingJoinPoint>()
    private val reqShieldAspect = spyk(ReqShieldAspect(asyncCache))
    private val targetObject = spyk(TestBean())
    private val argument = mapOf("x" to "paramX", "y" to "paramY")

    private val cacheName = "TestCacheName"
    private val cacheKeyGenerator = "customGenerator"
    private val spelEvaluatedKey = "$cacheName::paramXparamY"
    private val keyGeneratorKey = "$cacheName::KeyGeneratedByGenerator"

    // A single argument is the key itself; this one is a map, which is written by content
    private val defaultGeneratedKey = "$cacheName::{x=paramX, y=paramY}"

    private val beanFactory = mockk<BeanFactory>()

    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { joinPoint.args } returns arrayOf(argument)
        every { joinPoint.target } returns targetObject
        // No application scheduler bean, so the aspect falls back to the shared boundedElastic
        every { beanFactory.containsBean("reqShieldScheduler") } returns false

        reqShieldAspect.setBeanFactory(beanFactory)
    }

    private fun stubTargetMethod(
        methodName: String,
        aspect: ReqShieldAspect<Product> = reqShieldAspect,
    ) {
        val method = findTestBeanMethod(methodName)
        every { aspect.getTargetMethod(joinPoint) } returns method
        // The aspect keeps what it resolved per signature method and target class
        val signature = mockk<MethodSignature>()
        every { signature.method } returns method
        every { joinPoint.signature } returns signature
    }

    @Test
    fun repeatedCallsShouldResolveTheMethodOnceAndReuseOneReqShield() {
        val method = findTestBeanMethod(TestBean::cacheableWithCustomKey.name)
        asyncCache.put(spelEvaluatedKey, ReqShieldData(methodReturn, 1000), 1000).block()
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        reqShieldAspect.aroundTargetCacheable(joinPoint).block()
        val first = reqShieldAspect.reqShieldMap[method]
        repeat(3) { reqShieldAspect.aroundTargetCacheable(joinPoint).block() }

        // The target method, and with it the annotation, is resolved on the first call only
        verify(exactly = 1) { reqShieldAspect.getTargetMethod(joinPoint) }
        assertEquals(1, reqShieldAspect.reqShieldMap.size)
        Assertions.assertNotNull(first)
        Assertions.assertSame(first, reqShieldAspect.reqShieldMap[method])
    }

    @Test
    fun repeatedEvictionsShouldResolveTheMethodOnce() {
        stubTargetMethod(TestBean::evict.name)
        every { joinPoint.proceed() } answers { targetObject.evict(argument) }

        repeat(3) { reqShieldAspect.aroundReqShieldCacheEvict(joinPoint).block() }

        verify(exactly = 1) { reqShieldAspect.getTargetMethod(joinPoint) }
    }

    @Test
    fun onlyASpelKeyShouldBuildAnEvaluationContext() {
        every { beanFactory.getBean(cacheKeyGenerator, KeyGenerator::class.java) } returns CustomGenerator()

        stubTargetMethod(TestBean::cacheableWithDefaultKeyGenerator.name)
        reqShieldAspect.getCacheableCacheKey(joinPoint)
        stubTargetMethod(TestBean::cacheableWithKeyGenerator.name)
        reqShieldAspect.getCacheableCacheKey(joinPoint)

        verify(exactly = 0) { reqShieldAspect.createEvaluationContext(any(), any(), any()) }

        stubTargetMethod(TestBean::cacheableWithCustomKey.name)
        reqShieldAspect.getCacheableCacheKey(joinPoint)
        reqShieldAspect.getCacheableCacheKey(joinPoint)

        verify(exactly = 2) { reqShieldAspect.createEvaluationContext(any(), any(), any()) }
    }

    @Test
    fun spelKeyShouldBeEvaluatedAgainForTheArgumentsOfEachCall() {
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        val first = reqShieldAspect.getCacheableCacheKey(joinPoint)
        every { joinPoint.args } returns arrayOf(mapOf("x" to "otherX", "y" to "otherY"))
        val second = reqShieldAspect.getCacheableCacheKey(joinPoint)

        assertEquals(spelEvaluatedKey, first)
        assertEquals("$cacheName::otherXotherY", second)
    }

    private fun findTestBeanMethod(methodName: String): Method =
        ReflectionUtils.findMethod(TestBean::class.java, methodName, Map::class.java)!!

    @Test
    override fun verifyReqShieldCacheCreation() {
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        // pre-populate cache
        asyncCache.put(spelEvaluatedKey, reqShieldData, 1000).block()
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        // Verify the behavior using StepVerifier
        StepVerifier
            .create(result)
            .assertNext { value ->
                assertEquals(reqShieldData.value, value)
                Assertions.assertTrue(reqShieldAspect.reqShieldMap.size == 1)
                Assertions.assertNotNull(reqShieldAspect.reqShieldMap[reqShieldAspect.getTargetMethod(joinPoint)])
            }.verifyComplete()
    }

    @Test
    override fun reqShieldObjectShouldBeCreatedOnce() {
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        asyncCache.put(spelEvaluatedKey, reqShieldData, 1000).block()
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)

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
                assertEquals(20, productList.size)
                Assertions.assertTrue(reqShieldAspect.reqShieldMap.size == 1)
                Assertions.assertNotNull(reqShieldAspect.reqShieldMap[reqShieldAspect.getTargetMethod(joinPoint)])
            }.verifyComplete()
    }

    @Test
    override fun verifyReqShieldCacheEviction() {
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        asyncCache.put(defaultGeneratedKey, reqShieldData, 1000).block()
        stubTargetMethod(TestBean::cacheableWithDefaultKeyGenerator.name)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }

        // Test the aroundTargetCacheable method
        val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

        StepVerifier
            .create(result)
            .assertNext { value ->
                assertEquals(reqShieldData.value, value)
            }.verifyComplete()

        // Validate cache eviction
        // real eviction call
        stubTargetMethod(TestBean::evict.name)
        every { joinPoint.proceed() } answers { targetObject.evict(argument) }

        val removeProductMono = reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        StepVerifier
            .create(removeProductMono)
            .assertNext { value ->
                assertTrue(value as Boolean)
            }.verifyComplete()

        // The evicted entry is the namespaced one written by @ReqShieldCacheable
        assertNull(asyncCache.get(defaultGeneratedKey).block())
    }

    @Test
    override fun verifyCacheKeyGenerationWithSpEL() {
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        // when, then
        Assertions.assertEquals(
            spelEvaluatedKey,
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    override fun verifyCacheKeyGenerationWithKeyGenerator() {
        // given
        every { beanFactory.getBean(cacheKeyGenerator, KeyGenerator::class.java) } returns
            CustomGenerator()
        stubTargetMethod(TestBean::cacheableWithKeyGenerator.name)

        // when, then
        Assertions.assertEquals(
            keyGeneratorKey,
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    override fun verifyCacheKeyGenerationWithDefaultGenerator() {
        // given
        stubTargetMethod(TestBean::cacheableWithDefaultKeyGenerator.name)

        // when, then
        Assertions.assertEquals(
            defaultGeneratedKey,
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    fun evictKeyIsNamespacedWithTheCacheNameToo() {
        stubTargetMethod(TestBean::evict.name)

        Assertions.assertEquals(defaultGeneratedKey, reqShieldAspect.getCacheEvictCacheKey(joinPoint))
    }

    @Test
    fun reqShieldIsCreatedPerAnnotatedMethodEvenWhenTheCacheKeyMatches() {
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)
        reqShieldAspect.aroundTargetCacheable(joinPoint).block()

        // Same cacheName and same resolved key, but another method: it must get its own ReqShield.
        stubTargetMethod(TestBean::cacheableWithSameKeyOtherMethod.name)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSameKeyOtherMethod(argument) }
        reqShieldAspect.aroundTargetCacheable(joinPoint).block()

        assertEquals(2, reqShieldAspect.reqShieldMap.size)
        Assertions.assertNotNull(reqShieldAspect.reqShieldMap[findTestBeanMethod(TestBean::cacheableWithCustomKey.name)])
        Assertions.assertNotNull(reqShieldAspect.reqShieldMap[findTestBeanMethod(TestBean::cacheableWithSameKeyOtherMethod.name)])
    }

    @Test
    fun nullValueIsEmittedAsAnEmptyMonoByDefault() {
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)
        every { joinPoint.proceed() } returns Mono.empty<Product>()

        StepVerifier
            .create(reqShieldAspect.aroundTargetCacheable(joinPoint))
            .verifyComplete()
    }

    @Test
    fun nullValueFailsWhenNullHandlingIsError() {
        stubTargetMethod(TestBean::cacheableWithNullHandlingError.name)
        every { joinPoint.proceed() } returns Mono.empty<Product>()

        StepVerifier
            .create(reqShieldAspect.aroundTargetCacheable(joinPoint))
            .verifyError(IllegalStateException::class.java)
    }

    @Test
    fun keyAndKeyGeneratorAreMutuallyExclusive() {
        stubTargetMethod(TestBean::cacheableWithKeyAndKeyGenerator.name)

        val exception = assertThrows<IllegalArgumentException> { reqShieldAspect.getCacheableCacheKey(joinPoint) }
        assertTrue(exception.message!!.contains("mutually exclusive"))
    }

    @Test
    fun aBlankResolvedKeyIsRejected() {
        stubTargetMethod(TestBean::cacheableWithUnresolvableKey.name)

        val exception = assertThrows<IllegalArgumentException> { reqShieldAspect.getCacheableCacheKey(joinPoint) }
        assertTrue(exception.message!!.contains("Null/blank key"))
    }

    @Test
    fun missingAnnotationsAreReported() {
        stubTargetMethod(TestBean::notAnnotated.name)

        assertThrows<IllegalArgumentException> { reqShieldAspect.getCacheableAnnotation(joinPoint) }
        assertThrows<IllegalArgumentException> { reqShieldAspect.getCacheEvictAnnotation(joinPoint) }
    }

    @Test
    fun evictionIsSkippedWhenTheAnnotatedMethodFails() {
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        asyncCache.put(defaultGeneratedKey, reqShieldData, 1000).block()
        stubTargetMethod(TestBean::evict.name)
        every { joinPoint.proceed() } returns Mono.error<Boolean>(IllegalStateException("boom"))

        StepVerifier
            .create(reqShieldAspect.aroundReqShieldCacheEvict(joinPoint))
            .verifyError(IllegalStateException::class.java)

        Assertions.assertNotNull(asyncCache.get(defaultGeneratedKey).block())
    }

    @Test
    fun evictionAlsoRunsWhenTheAnnotatedMethodCompletesEmpty() {
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        asyncCache.put(defaultGeneratedKey, reqShieldData, 1000).block()
        stubTargetMethod(TestBean::evict.name)
        every { joinPoint.proceed() } returns Mono.empty<Void>()

        StepVerifier
            .create(reqShieldAspect.aroundReqShieldCacheEvict(joinPoint))
            .verifyComplete()

        assertNull(asyncCache.get(defaultGeneratedKey).block())
    }

    @Test
    fun globalLockCollapsesRequestsWhenTheCacheSupportsIt() {
        stubTargetMethod(TestBean::cacheableWithGlobalLock.name)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithGlobalLock(argument) }

        StepVerifier
            .create(reqShieldAspect.aroundTargetCacheable(joinPoint))
            .assertNext { value -> assertEquals(methodReturn, value) }
            .verifyComplete()
    }

    @Test
    fun globalLockRequiresTheCacheToImplementGlobalLockSupport() {
        val localOnlyAspect = spyk(ReqShieldAspect<Product>(LocalOnlyAsyncCache()))
        localOnlyAspect.setBeanFactory(beanFactory)
        stubTargetMethod(TestBean::cacheableWithGlobalLock.name, localOnlyAspect)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithGlobalLock(argument) }

        val exception = assertThrows<IllegalArgumentException> { localOnlyAspect.aroundTargetCacheable(joinPoint) }
        assertTrue(exception.message!!.contains("GlobalLockSupport"))
        assertTrue(localOnlyAspect.reqShieldMap.isEmpty())
    }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun cacheableWithCustomKey(paramMap: Map<String, String>): Mono<Product> = Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun cacheableWithSameKeyOtherMethod(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName")
        fun cacheableWithDefaultKeyGenerator(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName", keyGenerator = "customGenerator")
        fun cacheableWithKeyGenerator(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x']", keyGenerator = "customGenerator")
        fun cacheableWithKeyAndKeyGenerator(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['absent']")
        fun cacheableWithUnresolvableKey(paramMap: Map<String, String>): Mono<Product> =
            Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheable(
            cacheName = "TestCacheName",
            key = "#paramMap['x']",
            nullHandling = NullHandling.ERROR,
        )
        fun cacheableWithNullHandlingError(paramMap: Map<String, String>): Mono<Product> = Mono.empty()

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x']", isLocalLock = false)
        fun cacheableWithGlobalLock(paramMap: Map<String, String>): Mono<Product> = Mono.justOrEmpty(Product("testProduct", "testCategory"))

        @ReqShieldCacheEvict(cacheName = "TestCacheName")
        fun evict(paramMap: Map<String, String>): Mono<Boolean> = Mono.just(true)

        fun notAnnotated(paramMap: Map<String, String>): Mono<Product> = Mono.empty()
    }

    class CustomGenerator : KeyGenerator {
        override fun generate(
            target: Any,
            method: Method,
            vararg params: Any?,
        ): Any = "KeyGeneratedByGenerator"
    }
}
