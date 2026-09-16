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
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.support.BaseReqShieldModuleSupportTest
import com.linecorp.cse.reqshield.support.constant.ConfigValues.DEFAULT_LOCK_TIMEOUT_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import kotlin.test.assertFailsWith

private val log = LoggerFactory.getLogger(ReqShieldAspectTest::class.java)

@OptIn(ExperimentalCoroutinesApi::class)
class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val asyncCache: AsyncCache<Product> = InMemoryAsyncCache()
    private val joinPoint: ProceedingJoinPoint = mockk<ProceedingJoinPoint>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reqShieldAspect: ReqShieldAspect<Product> = spyk(ReqShieldAspect(asyncCache, scope))
    private val targetObject = spyk(TestBean())
    private val argument = mapOf("x" to "paramX", "y" to "paramY")
    private val mockContinuation = mockk<Continuation<Any?>>()

    private val cacheName = "TestCacheName"
    private val cacheKeyGenerator = "customGenerator"
    private val spelEvaluatedKey = "paramXparamY"
    private val keyGeneratorKey = "KeyGeneratedByGenerator"
    private val namespacedSpelKey = "$cacheName::$spelEvaluatedKey"

    private val beanFactory = mockk<BeanFactory>()

    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { mockContinuation.context } returns EmptyCoroutineContext
        every { joinPoint.args } returns arrayOf(argument, mockContinuation)
        every { joinPoint.target } returns targetObject

        reqShieldAspect.setBeanFactory(beanFactory)
    }

    /** The single-parameter overload of [name] on [TestBean]; suspend and plain ones both report 2. */
    private fun methodOf(name: String): Method =
        TestBean::class
            .functions
            .find { it.name == name && it.parameters.size == 2 }
            ?.javaMethod!!

    /** Cache writes are fire-and-forget, so tests that assert on them must wait for the scope. */
    private suspend fun CoroutineScope.awaitBackgroundWrites() {
        coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    @Test
    override fun verifyReqShieldCacheCreation() =
        runTest {
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            asyncCache.put(namespacedSpelKey, reqShieldData, 1000)
            coEvery { joinPoint.proceed(any<Array<Any?>>()) } coAnswers { targetObject.cacheableWithCustomKey(argument) }
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithCustomKey.name)

            val result = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

            assertEquals(reqShieldData.value, result)
            assertEquals(1, reqShieldAspect.reqShieldMap.size)
            assertNotNull(reqShieldAspect.reqShieldMap[reqShieldAspect.getTargetMethod(joinPoint)])
        }

    @Test
    override fun reqShieldObjectShouldBeCreatedOnce() =
        runTest {
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            asyncCache.put(namespacedSpelKey, reqShieldData, 1000)
            coEvery { joinPoint.proceed(any<Array<Any?>>()) } coAnswers { targetObject.cacheableWithCustomKey(argument) }
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithCustomKey.name)

            List(20) { async { reqShieldAspect.aroundReqShieldCacheable(joinPoint) } }.awaitAll()

            assertEquals(1, reqShieldAspect.reqShieldMap.size)
            assertNotNull(reqShieldAspect.reqShieldMap[reqShieldAspect.getTargetMethod(joinPoint)])
        }

    @Test
    override fun verifyReqShieldCacheEviction() =
        runTest {
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithCustomKey.name)
            val generatedKey = reqShieldAspect.getCacheableCacheKey(joinPoint)
            asyncCache.put(generatedKey, reqShieldData, 1000)
            coEvery { joinPoint.proceed(any<Array<Any?>>()) } coAnswers { targetObject.cacheableWithCustomKey(argument) }

            val result = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

            assertEquals(reqShieldData.value, result)

            // The evict advice resolves its own key from @ReqShieldCacheEvict; it must hit the same entry.
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::evict.name)
            coEvery { joinPoint.proceed(any<Array<Any?>>()) } coAnswers { targetObject.evict(argument) }

            val evicted = reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

            assertTrue(evicted as Boolean)
            assertNull(asyncCache.get(generatedKey))
        }

    @Test
    fun cacheIsEvictedOnlyAfterTheMethodSucceeds() =
        runTest {
            val reqShieldData = ReqShieldData(methodReturn, 1000)
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::evictFailing.name)
            val evictKey = reqShieldAspect.getCacheEvictCacheKey(joinPoint)
            asyncCache.put(evictKey, reqShieldData, 1000)
            coEvery { joinPoint.proceed(any<Array<Any?>>()) } coAnswers { targetObject.evictFailing(argument) }

            assertFailsWith<IllegalStateException> { reqShieldAspect.aroundReqShieldCacheEvict(joinPoint) }

            assertNotNull(asyncCache.get(evictKey))
        }

    @Test
    override fun verifyCacheKeyGenerationWithSpEL() =
        runTest {
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithCustomKey.name)

            assertEquals(namespacedSpelKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
        }

    @Test
    override fun verifyCacheKeyGenerationWithKeyGenerator() =
        runTest {
            every { beanFactory.getBean(cacheKeyGenerator, KeyGenerator::class.java) } returns CustomGenerator()
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithKeyGenerator.name)

            assertEquals("$cacheName::$keyGeneratorKey", reqShieldAspect.getCacheableCacheKey(joinPoint))
        }

    @Test
    override fun verifyCacheKeyGenerationWithDefaultGenerator() =
        runTest {
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithDefaultKeyGenerator.name)

            assertEquals(
                "$cacheName::${SimpleKeyGenerator.generateKey(arrayOf(argument))}",
                reqShieldAspect.getCacheableCacheKey(joinPoint),
            )
        }

    @Test
    fun cacheKeysOfTwoCacheNamesNeverCollide() =
        runTest {
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithCustomKey.name)
            val first = reqShieldAspect.getCacheableCacheKey(joinPoint)

            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithOtherCacheName.name)
            val second = reqShieldAspect.getCacheableCacheKey(joinPoint)

            assertEquals(namespacedSpelKey, first)
            assertEquals("OtherCacheName::$spelEvaluatedKey", second)
        }

    @Test
    fun cacheableRejectsNonSuspendTarget() =
        runTest {
            // A non-suspend join point has no trailing Continuation argument.
            every { joinPoint.args } returns arrayOf(argument)
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithKeyGenerator.name)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    reqShieldAspect.aroundReqShieldCacheable(joinPoint)
                }

            assertTrue(exception.message!!.contains(TestBean::cacheableWithKeyGenerator.name), exception.message)
        }

    @Test
    fun cacheEvictRejectsNonSuspendTarget() =
        runTest {
            every { joinPoint.args } returns arrayOf(argument)
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::evictNonSuspend.name)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)
                }

            assertTrue(exception.message!!.contains(TestBean::evictNonSuspend.name), exception.message)
        }

    @Test
    fun keyAndKeyGeneratorAreMutuallyExclusive() =
        runTest {
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithKeyAndGenerator.name)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    reqShieldAspect.getCacheableCacheKey(joinPoint)
                }

            assertTrue(exception.message!!.contains("mutually exclusive"), exception.message)
        }

    @Test
    fun aKeyThatResolvesToNothingIsRejected() =
        runTest {
            every { reqShieldAspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithUnresolvableKey.name)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    reqShieldAspect.getCacheableCacheKey(joinPoint)
                }

            assertTrue(exception.message!!.contains(TestBean::cacheableWithUnresolvableKey.name), exception.message)
        }

    @Test
    fun globalLockRequiresTheCacheToImplementGlobalLockSupport() =
        runTest {
            val plainCache = mockk<AsyncCache<Product>>()
            val aspect = spyk(ReqShieldAspect(plainCache, scope))
            aspect.setBeanFactory(beanFactory)
            every { aspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithGlobalLock.name)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    aspect.aroundReqShieldCacheable(joinPoint)
                }

            assertTrue(exception.message!!.contains("GlobalLockSupport"), exception.message)
            assertTrue(exception.message!!.contains(TestBean::cacheableWithGlobalLock.name), exception.message)
        }

    @Test
    fun globalLockIsAcquiredAndReleasedWithTheSameToken() =
        runTest {
            val lockableCache = mockk<LockableAsyncCache<Product>>()
            val tokenSlot = slot<String>()
            coEvery { lockableCache.get(any()) } returns null
            coEvery { lockableCache.put(any(), any(), any()) } returns true
            coEvery { lockableCache.globalLock(any(), capture(tokenSlot), any()) } returns true
            coEvery { lockableCache.globalUnLock(any(), any()) } returns true

            val lockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val aspect = spyk(ReqShieldAspect(lockableCache, lockScope))
            aspect.setBeanFactory(beanFactory)
            every { aspect.getTargetMethod(joinPoint) } returns methodOf(TestBean::cacheableWithGlobalLock.name)
            coEvery { joinPoint.proceed(any<Array<Any?>>()) } coAnswers { targetObject.cacheableWithGlobalLock(argument) }

            aspect.aroundReqShieldCacheable(joinPoint)
            lockScope.awaitBackgroundWrites()

            val expectedLockKey = "$LOCK_KEY_PREFIX${namespacedSpelKey}_CREATE"
            assertTrue(tokenSlot.isCaptured, "the aspect never called globalLock")
            coVerify(exactly = 1) {
                lockableCache.globalLock(expectedLockKey, tokenSlot.captured, DEFAULT_LOCK_TIMEOUT_MILLIS)
            }
            coVerify(exactly = 1) { lockableCache.globalUnLock(expectedLockKey, tokenSlot.captured) }
        }

    /** A cache that opts in to global locking, as production code is expected to do. */
    interface LockableAsyncCache<T> :
        AsyncCache<T>,
        GlobalLockSupport

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun cacheableWithCustomKey(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "OtherCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun cacheableWithOtherCacheName(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']", isLocalLock = false)
        suspend fun cacheableWithGlobalLock(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['missing']")
        suspend fun cacheableWithUnresolvableKey(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['x']", keyGenerator = "customGenerator")
        suspend fun cacheableWithKeyAndGenerator(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName")
        suspend fun cacheableWithDefaultKeyGenerator(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName", keyGenerator = "customGenerator")
        fun cacheableWithKeyGenerator(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun evict(paramMap: Map<String, String>): Boolean {
            log.debug("cache eviction")
            return true
        }

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        suspend fun evictFailing(paramMap: Map<String, String>): Boolean = throw IllegalStateException("eviction target failed: $paramMap")

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun evictNonSuspend(paramMap: Map<String, String>): Boolean = paramMap.isNotEmpty()
    }

    class CustomGenerator : KeyGenerator {
        override fun generate(
            target: Any,
            method: Method,
            vararg params: Any?,
        ): Any = "KeyGeneratedByGenerator"
    }
}
