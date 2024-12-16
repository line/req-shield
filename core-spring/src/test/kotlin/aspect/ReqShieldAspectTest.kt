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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.util.ReflectionUtils
import java.time.Duration
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(ReqShieldAspectTest::class.java)

class ReqShieldAspectTest : BaseReqShieldModuleSupportTest {
    private val reqShieldCache: ReqShieldCache<Product> = mockk()
    private val targetObject = spyk(TestBean())
    private val joinPoint = mockk<ProceedingJoinPoint>()
    private val reqShieldAspect = spyk(ReqShieldAspect(reqShieldCache))

    private val cacheKey = "testCacheKey"
    private val cacheName = "testCacheName"
    private val argument = "testArgument"
    private val methodReturn = Product("testProduct", "testCategory")

    @BeforeEach
    fun setUp() {
        every { joinPoint.target } returns targetObject
        every { joinPoint.args } returns arrayOf(argument)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithSingleArgument.name,
                String::class.java,
            )

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
                key = cacheKey,
            )
    }

    @Test
    override fun `test aspect operation - verify reqShield and cache creation`() {
        // given
        every { reqShieldCache.get(any()) } returns ReqShieldData(methodReturn, 1000)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

        // when
        reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        Awaitility.await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            // then
            assertTrue(reqShieldAspect.reqShieldMap.size == 1)
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$cacheKey"])
        }
    }

    @Test
    override fun `test aspect operation - reqShieldObject should be created once`() {
        // given
        every { reqShieldCache.get(any()) } returns ReqShieldData(methodReturn, 1000)
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

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
            assertNotNull(reqShieldAspect.reqShieldMap["$cacheName-$cacheKey"])
        }
    }

    @Test
    override fun `test aspect operation - cache eviction`() {
        // given
        val reqShieldData = ReqShieldData(methodReturn, 10000)
        every { reqShieldCache.get(any()) } returns reqShieldData
        every { reqShieldCache.evict(any()) } returns true
        every { joinPoint.proceed() } answers { targetObject.cacheableWithSingleArgument(argument) }

        val cachedResult = reqShieldAspect.aroundReqShieldCacheable(joinPoint)

        assertEquals(reqShieldData.value, cachedResult)

        // when
        reqShieldAspect.aroundReqShieldCacheEvict(joinPoint)

        // then
        verify(exactly = 1) { reqShieldCache.evict(any()) }
    }

    @Test
    override fun `test cache key generation - use generated key`() {
        every { joinPoint.target } returns targetObject
        every { joinPoint.args } returns arrayOf(argument)
        every { reqShieldAspect.getTargetMethod(joinPoint) } returns
            ReflectionUtils.findMethod(
                TestBean::class.java,
                TestBean::cacheableWithSingleArgument.name,
                String::class.java,
            )

        every { reqShieldAspect.getCacheableAnnotation(joinPoint) } returns
            ReqShieldCacheable(
                cacheName = cacheName,
            )

        assertEquals(
            "$cacheName-[testArgument]",
            reqShieldAspect.getCacheableCacheKey(joinPoint),
        )
    }

    @Test
    override fun `test cache key generation - cacheKey should be supplied key`() {
        every { reqShieldAspect.getCacheableAnnotation(joinPoint) } returns
            ReqShieldCacheable(
                cacheName = cacheName,
                key = cacheKey,
            )

        assertEquals(cacheKey, reqShieldAspect.getCacheableCacheKey(joinPoint))
    }

    class TestBean {
        @ReqShieldCacheable(cacheName = "TestCacheName")
        fun cacheableWithSingleArgument(testArgument: String): String {
            log.debug("method invoked")
            return "ReturnValue: $testArgument"
        }

        @ReqShieldCacheEvict(cacheName = "TestCacheName")
        fun evictWithSingleArgument(testArgument: String) {
            log.debug("cache eviction")
        }
    }
}
