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
import com.linecorp.cse.reqshield.spring.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.BaseReqShieldModuleSupportTest
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.constant.ConfigValues.DEFAULT_LOCK_TIMEOUT_MILLIS
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.aspectj.lang.ProceedingJoinPoint
import org.awaitility.Awaitility
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(ReqShieldAspectTest::class.java)

class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val executor = Executors.newScheduledThreadPool(2)
    private val reqShieldCache: ReqShieldCache<Product> = mockk()
    private val joinPoint = mockk<ProceedingJoinPoint>()
    private val reqShieldAspect = spyk(ReqShieldAspect(reqShieldCache, executor))
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

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun stubTargetMethod(
        methodName: String,
        aspect: ReqShieldAspect<Product> = reqShieldAspect,
    ): Method {
        val method = ReflectionUtils.findMethod(TestBean::class.java, methodName, Map::class.java)!!
        every { aspect.getTargetMethod(joinPoint) } returns method

        return method
    }

    @Test
    override fun verifyReqShieldCacheCreation() {
        // given
        val reqShieldData = ReqShieldData(methodReturn, 1000)
        every { reqShieldCache.get(any()) } returns reqShieldData
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        val method = stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        // when
        val result = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        Awaitility.await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            // then
            assertEquals(reqShieldData.value, result)
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap[method])
        }
    }

    @Test
    override fun reqShieldObjectShouldBeCreatedOnce() {
        // given
        every { reqShieldCache.get(any()) } returns ReqShieldData(methodReturn, 1000)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }
        val method = stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        // when
        val executorService = Executors.newFixedThreadPool(10)
        for (i in 1..10) {
            executorService.submit {
                reqShieldAspect.aroundReqShieldCacheable(joinPoint)
            }
        }
        executorService.shutdown()
        assertTrue(executorService.awaitTermination(BaseReqShieldTest.AWAIT_TIMEOUT, TimeUnit.MILLISECONDS))

        Awaitility.await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            // then
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap[method])
        }
    }

    @Test
    fun eachAnnotatedMethodShouldGetItsOwnReqShield() {
        // given
        every { reqShieldCache.get(any()) } returns ReqShieldData(methodReturn, 1000)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }

        // when
        val customKeyMethod = stubTargetMethod(TestBean::cacheableWithCustomKey.name)
        reqShieldAspect.aroundReqShieldCacheable(joinPoint)
        val defaultKeyMethod = stubTargetMethod(TestBean::cacheableWithDefaultKeyGenerator.name)
        reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        // then
        assertEquals(2, reqShieldAspect.reqShieldMap.size)
        assertNotNull(reqShieldAspect.reqShieldMap[customKeyMethod])
        assertNotNull(reqShieldAspect.reqShieldMap[defaultKeyMethod])
    }

    @Test
    override fun verifyReqShieldCacheEviction() {
        // given
        val reqShieldData = ReqShieldData(methodReturn, 10000)
        every { reqShieldCache.get(any()) } returns reqShieldData
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithCustomKey(argument) }

        val cachedResult = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        assertEquals(reqShieldData.value, cachedResult)

        // Validate cache eviction
        every { reqShieldCache.evict(any()) } returns true
        stubTargetMethod(TestBean::evict.name)

        // when
        reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        // then the evicted key is the same namespaced key the cacheable advice used
        verify(exactly = 1) { reqShieldCache.evict("$cacheName::$spelEvaluatedKey") }
    }

    @Test
    fun evictionShouldHappenOnlyAfterTheMethodReturned() {
        // given
        val invocations = Collections.synchronizedList(mutableListOf<String>())
        every { joinPoint.proceed() } answers {
            invocations.add("method")
            "methodResult"
        }
        every { reqShieldCache.evict(any()) } answers {
            invocations.add("evict")
            true
        }
        stubTargetMethod(TestBean::evict.name)

        // when
        val result = reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        // then
        assertEquals("methodResult", result)
        assertEquals(listOf("method", "evict"), invocations)
    }

    @Test
    fun evictionShouldBeSkippedWhenTheMethodThrows() {
        // given
        every { joinPoint.proceed() } throws IllegalStateException("method failed")
        stubTargetMethod(TestBean::evict.name)

        // when
        val exception =
            assertThrows(IllegalStateException::class.java) {
                reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)
            }

        // then
        assertEquals("method failed", exception.message)
        verify(exactly = 0) { reqShieldCache.evict(any()) }
    }

    @Test
    fun globalLockShouldRequireACacheImplementingGlobalLockSupport() {
        // given a cache that does not implement GlobalLockSupport
        stubTargetMethod(TestBean::cacheableWithGlobalLock.name)

        // when
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                reqShieldAspect.aroundReqShieldCacheable(joinPoint)
            }

        // then
        assertTrue(exception.message!!.contains("requires the ReqShieldCache bean to implement GlobalLockSupport"))
        assertTrue(exception.message!!.contains(TestBean::cacheableWithGlobalLock.name))
    }

    @Test
    fun globalLockShouldBeAcquiredAndReleasedWithTheSameToken() {
        // given
        val globalLockCache = GlobalLockReqShieldCache()
        val aspect = spyk(ReqShieldAspect(globalLockCache, executor))
        aspect.setBeanFactory(beanFactory)
        every { joinPoint.proceed() } returns methodReturn
        stubTargetMethod(TestBean::cacheableWithGlobalLock.name, aspect)

        // when
        val result = aspect.aroundReqShieldCacheable(joinPoint)

        // then
        assertEquals(methodReturn, result)
        Awaitility.await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(1, globalLockCache.unLockCalls.size)
        }

        val lockCall = globalLockCache.lockCalls.single()
        val unLockCall = globalLockCache.unLockCalls.single()

        assertTrue(lockCall.lockKey.contains("$cacheName::$spelEvaluatedKey"))
        assertEquals(DEFAULT_LOCK_TIMEOUT_MILLIS, lockCall.timeToLiveMillis)
        assertEquals(lockCall.lockKey, unLockCall.lockKey)
        assertEquals(lockCall.token, unLockCall.token)
        assertNotNull(globalLockCache.get("$cacheName::$spelEvaluatedKey"))
    }

    @Test
    override fun verifyCacheKeyGenerationWithSpEL() {
        // given
        stubTargetMethod(TestBean::cacheableWithCustomKey.name)

        // when, then
        assertEquals(
            "$cacheName::$spelEvaluatedKey",
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
        assertEquals(
            "$cacheName::$keyGeneratorKey",
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
        // the generator bean is looked up once and then cached
        assertEquals(
            "$cacheName::$keyGeneratorKey",
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
        verify(exactly = 1) { beanFactory.getBean(cacheKeyGenerator, KeyGenerator::class.java) }
    }

    @Test
    override fun verifyCacheKeyGenerationWithDefaultGenerator() {
        // given
        stubTargetMethod(TestBean::cacheableWithDefaultKeyGenerator.name)

        // when, then
        assertEquals(
            "$cacheName::${SimpleKeyGenerator.generateKey(arrayOf(argument))}",
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    fun evictionKeyShouldUseTheSameNamespaceAsTheCacheableKey() {
        // given
        stubTargetMethod(TestBean::evict.name)

        // when, then
        assertEquals("$cacheName::$spelEvaluatedKey", reqShieldAspect.getCacheEvictCacheKey(joinPoint))
    }

    @Test
    fun keyAndKeyGeneratorShouldBeMutuallyExclusive() {
        // given
        stubTargetMethod(TestBean::cacheableWithKeyAndKeyGenerator.name)

        // when, then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                reqShieldAspect.getCacheableCacheKey(joinPoint)
            }
        assertTrue(exception.message!!.contains("mutually exclusive"))
    }

    @Test
    fun blankResolvedKeyShouldBeRejected() {
        // given
        stubTargetMethod(TestBean::cacheableWithUnresolvableKey.name)

        // when, then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                reqShieldAspect.getCacheableCacheKey(joinPoint)
            }
        assertTrue(exception.message!!.contains("Null/blank key"))
    }

    @Test
    fun missingAnnotationsShouldBeRejected() {
        // given
        stubTargetMethod(TestBean::withoutAnnotation.name)

        // when, then
        assertThrows(IllegalArgumentException::class.java) {
            reqShieldAspect.getCacheableAnnotation(joinPoint)
        }
        assertThrows(IllegalArgumentException::class.java) {
            reqShieldAspect.getCacheEvictAnnotation(joinPoint)
        }
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

        @ReqShieldCacheable(
            cacheName = "TestCacheName",
            key = "#paramMap['x'] + #paramMap['y']",
            keyGenerator = "customGenerator",
        )
        fun cacheableWithKeyAndKeyGenerator(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(cacheName = "TestCacheName", key = "#paramMap['unknown']")
        fun cacheableWithUnresolvableKey(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheable(
            cacheName = "TestCacheName",
            key = "#paramMap['x'] + #paramMap['y']",
            isLocalLock = false,
        )
        fun cacheableWithGlobalLock(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"

        @ReqShieldCacheEvict(cacheName = "TestCacheName", key = "#paramMap['x'] + #paramMap['y']")
        fun evict(paramMap: Map<String, String>) {
            log.debug("cache eviction")
        }

        fun withoutAnnotation(paramMap: Map<String, String>): String = "ReturnValue: $paramMap"
    }

    class CustomGenerator : KeyGenerator {
        override fun generate(
            target: Any,
            method: Method,
            vararg params: Any?,
        ): Any = "KeyGeneratedByGenerator"
    }

    /** Cache that opts in to global locking; it records every lock call so the token can be compared. */
    class GlobalLockReqShieldCache :
        ReqShieldCache<Product>,
        GlobalLockSupport {
        data class LockCall(
            val lockKey: String,
            val token: String,
            val timeToLiveMillis: Long,
        )

        data class UnLockCall(
            val lockKey: String,
            val token: String,
        )

        val lockCalls: MutableList<LockCall> = Collections.synchronizedList(mutableListOf())
        val unLockCalls: MutableList<UnLockCall> = Collections.synchronizedList(mutableListOf())

        private val store = ConcurrentHashMap<String, ReqShieldData<Product>>()

        override fun get(key: String): ReqShieldData<Product>? = store[key]

        override fun put(
            key: String,
            value: ReqShieldData<Product>,
            timeToLiveMillis: Long,
        ) {
            store[key] = value
        }

        override fun evict(key: String): Boolean? = store.remove(key) != null

        override fun globalLock(
            lockKey: String,
            token: String,
            timeToLiveMillis: Long,
        ): Boolean {
            lockCalls.add(LockCall(lockKey, token, timeToLiveMillis))
            return true
        }

        override fun globalUnLock(
            lockKey: String,
            token: String,
        ): Boolean {
            unLockCalls.add(UnLockCall(lockKey, token))
            return true
        }
    }
}
