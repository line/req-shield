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
import com.linecorp.cse.reqshield.support.BaseReqShieldModuleSupportTest
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertEquals

private val log = LoggerFactory.getLogger(ReqShieldAspectTest::class.java)

@OptIn(ExperimentalCoroutinesApi::class)
class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val asyncCache: AsyncCache<Product> = mockk()
    private val joinPoint: ProceedingJoinPoint = mockk<ProceedingJoinPoint>()
    private val methodSignature: MethodSignature = mockk()
    private val reqShieldAspect: ReqShieldAspect<Product> = spyk(ReqShieldAspect(asyncCache))
    private val targetObject = spyk(TestBean())
    private val mockContinuation = mockk<Continuation<Any?>>()
    private val kotlinMethod =
        TestBean::class.functions.find {
            it.name == TestBean::cacheableWithSingleArgument.name && it.parameters.size == 2
        }
    private val method = kotlinMethod?.javaMethod

    private val cacheName = "testCacheName"
    private val cacheKey = "#paramMap['x'] + #paramMap['y']"
    private val argument = mapOf("x" to "paramX", "y" to "paramY")
    private val evaluatedKey = "paramXparamY"
    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { joinPoint.signature } returns methodSignature

        every { methodSignature.method } returns method
        every { mockContinuation.context } returns EmptyCoroutineContext
        every { joinPoint.args } returns arrayOf(argument, mockContinuation)
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
    override fun testAspectOperationVerifyReqShieldAndCacheCreation() =
        runTest {
            // Mock the cache data using mockk
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithSingleArgument(argument) }

            // Test the aroundTargetCacheable method
            val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertEquals(result, reqShieldData.value)
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$evaluatedKey"])
        }

    @Test
    override fun testAspectOperationReqShieldObjectShouldBeCreatedOnce() =
        runTest {
            // Mock the cache data using mockk
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithSingleArgument(argument) }

            val jobs =
                List(20) {
                    async {
                        reqShieldAspect.aroundTargetCacheable(joinPoint)
                    }
                }

            jobs.awaitAll()

            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$evaluatedKey"])
        }

    @Test
    override fun testAspectOperationCacheEviction() =
        runTest {
            // Mock the cache data using mockk
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { asyncCache.evict(any()) } returns true
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithSingleArgument(argument) }

            // Test the aroundTargetCacheable method
            val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertEquals(reqShieldData.value, result)

            val kotlinMethod =
                TestBean::class.functions.find {
                    it.name == TestBean::evictWithSingleArgument.name && it.parameters.size == 2
                }
            val method = kotlinMethod?.javaMethod
            every { methodSignature.method } returns method

            coEvery { joinPoint.proceed() } coAnswers { targetObject.evictWithSingleArgument(argument) }
            val removeProductMono = reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertTrue(removeProductMono as Boolean)
        }

    @Test
    override fun testCacheKeyGenerationUseGeneratedKey() =
        runTest {
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithSingleArgument(argument) }

            reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertEquals(evaluatedKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
        }

    @Test
    override fun testCacheKeyGenerationCacheKeyShouldBeSuppliedKey() {
        every { reqShieldAspect.getCacheableAnnotation(joinPoint) } returns
            ReqShieldCacheable(
                cacheName = cacheName,
                key = cacheKey,
            )

        assertEquals(evaluatedKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
    }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun cacheableWithSingleArgument(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun evictWithSingleArgument(paramMap: Map<String, String>): Boolean {
            log.debug("cache eviction")
            return true
        }
    }
}
