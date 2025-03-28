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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.interceptor.SimpleKeyGenerator
import java.lang.reflect.Method
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
    private val reqShieldAspect: ReqShieldAspect<Product> = spyk(ReqShieldAspect(asyncCache))
    private val targetObject = spyk(TestBean())
    private val argument = mapOf("x" to "paramX", "y" to "paramY")
    private val mockContinuation = mockk<Continuation<Any?>>()
//    private val kotlinMethod =
//        TestBean::class.functions.find {
//            it.name == TestBean::cacheableWithSingleArgument.name && it.parameters.size == 2
//        }
//    private val method = kotlinMethod?.javaMethod

    private val cacheName = "TestCacheName"
    private val cacheKeyGenerator = "customGenerator"
    private val spelEvaluatedKey = "paramXparamY"
    private val keyGeneratorKey = "KeyGeneratedByGenerator"

    private val beanFactory = mockk<BeanFactory>()

    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { mockContinuation.context } returns EmptyCoroutineContext
        every { joinPoint.args } returns arrayOf(argument, mockContinuation)
        every { joinPoint.target } returns targetObject

        reqShieldAspect.setBeanFactory(beanFactory)
    }

    @Test
    override fun verifyReqShieldCacheCreation() =
        runTest {
            // Mock the cache data using mockk
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithCustomKey(argument) }
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::cacheableWithCustomKey.name && it.parameters.size == 2
                    }?.javaMethod!!

            // Test the aroundTargetCacheable method
            val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertEquals(result, reqShieldData.value)
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$spelEvaluatedKey"])
        }

    @Test
    override fun reqShieldObjectShouldBeCreatedOnce() =
        runTest {
            // Mock the cache data using mockk
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithCustomKey(argument) }
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::cacheableWithCustomKey.name && it.parameters.size == 2
                    }?.javaMethod!!

            val jobs =
                List(20) {
                    async {
                        reqShieldAspect.aroundTargetCacheable(joinPoint)
                    }
                }

            jobs.awaitAll()

            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$spelEvaluatedKey"])
        }

    @Test
    override fun verifyReqShieldCacheEviction() =
        runTest {
            // Mock the cache data using mockk
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            coEvery { asyncCache.get(any()) } returns reqShieldData
            coEvery { joinPoint.proceed() } coAnswers { targetObject.cacheableWithCustomKey(argument) }
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::cacheableWithDefaultKeyGenerator.name && it.parameters.size == 2
                    }?.javaMethod!!

            // Test the aroundTargetCacheable method
            val result = reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertEquals(reqShieldData.value, result)

            // Validate cache eviction
            coEvery { asyncCache.evict(any()) } returns true
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::evict.name && it.parameters.size == 2
                    }?.javaMethod!!
            coEvery { joinPoint.proceed() } coAnswers { targetObject.evict(argument) }

            val removeProductMono = reqShieldAspect.aroundTargetCacheable(joinPoint)

            assertTrue(removeProductMono as Boolean)
        }

    @Test
    override fun verifyCacheKeyGenerationWithSpEL() =
        runTest {
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::cacheableWithCustomKey.name && it.parameters.size == 2
                    }?.javaMethod!!

            assertEquals(spelEvaluatedKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
        }

    @Test
    override fun verifyCacheKeyGenerationWithKeyGenerator() =
        runTest {
            coEvery { beanFactory.getBean(cacheKeyGenerator, KeyGenerator::class.java) } returns
                CustomGenerator()
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::cacheableWithKeyGenerator.name && it.parameters.size == 2
                    }?.javaMethod!!

            assertEquals(keyGeneratorKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
        }

    @Test
    override fun verifyCacheKeyGenerationWithDefaultGenerator() =
        runTest {
            coEvery { reqShieldAspect.getTargetMethod(joinPoint) } returns
                TestBean::class
                    .functions
                    .find {
                        it.name == TestBean::cacheableWithDefaultKeyGenerator.name && it.parameters.size == 2
                    }?.javaMethod!!

            assertEquals(
                SimpleKeyGenerator.generateKey(arrayOf(argument)).toString(),
                reqShieldAspect.getCacheableCacheKey(joinPoint),
            )
        }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun cacheableWithCustomKey(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName")
        suspend fun cacheableWithDefaultKeyGenerator(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName", keyGenerator = "customGenerator")
        fun cacheableWithKeyGenerator(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun evict(paramMap: Map<String, String>): Boolean {
            log.debug("cache eviction")
            return true
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
