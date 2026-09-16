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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.example.service

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.example.dto.Product
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.getAndAwait
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(SpringExtension::class)
class CacheAnnotationTest : AbstractRedisTest() {
    @Autowired
    private lateinit var sampleService: SampleService

    @Autowired
    lateinit var asyncCache: AsyncCache<Product>

    @Autowired
    @Qualifier("redisOperationsForGlobalLock")
    lateinit var globalLockOperations: ReactiveRedisOperations<String, String>

    private val lockSupport: GlobalLockSupport get() = asyncCache as GlobalLockSupport

    /** The aspect namespaces every key with the cache name of the annotation. */
    private fun cacheKeyOf(productId: String) = "product::product-$productId"

    @BeforeEach
    fun `reset request count`() =
        runTest {
            sampleService.resetRequestCount()
        }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be only one times`() =
        runBlocking {
            val testProductId: String = UUID.randomUUID().toString()

            List(20) {
                async {
                    sampleService.getProduct(testProductId)
                }
            }.awaitAll()

            delay(500)

            assertEquals(1, sampleService.getRequestCount())
            assertNotNull(asyncCache.get(cacheKeyOf(testProductId)))
        }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be request count times(only update cache mode)`() =
        runBlocking {
            val testProductId: String = UUID.randomUUID().toString()

            List(20) {
                async {
                    sampleService.getProductOnlyUpdateCache(testProductId)
                }
            }.awaitAll()

            delay(500)

            assertEquals(20, sampleService.getRequestCount())
        }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be only one times For Global Lock`() =
        runBlocking {
            val testProductId: String = UUID.randomUUID().toString()

            List(20) {
                async {
                    sampleService.getProductForGlobalLock(testProductId)
                }
            }.awaitAll()

            delay(500)

            assertEquals(1, sampleService.getRequestCount())
        }

    @Test
    fun `ReqShieldCacheEvict test - after eviction, cache should be removed`() =
        runBlocking {
            // given
            val testProductId: String = UUID.randomUUID().toString()
            sampleService.getProduct(testProductId)

            val maxAttempts = 30

            var attempts = 0
            while (asyncCache.get(cacheKeyOf(testProductId)) == null) {
                if (attempts >= maxAttempts) {
                    break
                }
                attempts++
                delay(100)
            }

            assertNotNull(asyncCache.get(cacheKeyOf(testProductId)))

            // when
            sampleService.removeProduct(testProductId)

            var attemptsSecond = 0
            while (asyncCache.get(cacheKeyOf(testProductId)) != null) {
                if (attemptsSecond >= maxAttempts) {
                    break
                }
                attemptsSecond++
                delay(100)
            }

            assertNull(asyncCache.get(cacheKeyOf(testProductId)))
        }

    @Test
    fun `global lock test - the lock is released after the owning request completes`() =
        runBlocking {
            // given
            val testProductId: String = UUID.randomUUID().toString()
            val lockKey = "$LOCK_KEY_PREFIX${cacheKeyOf(testProductId)}_CREATE"

            // when
            sampleService.getProductForGlobalLock(testProductId)

            // then the background cache write releases the lock it owns
            var attempts = 0
            while (globalLockOperations.opsForValue().getAndAwait(lockKey) != null && attempts < 30) {
                attempts++
                delay(100)
            }
            assertNull(globalLockOperations.opsForValue().getAndAwait(lockKey))
        }

    @Test
    fun `global lock test - only the owning token can release the lock`() =
        runBlocking {
            val lockKey = "lock-token-${UUID.randomUUID()}"

            assertTrue(lockSupport.globalLock(lockKey, "owner", 10_000))
            // The token is stored as a plain string, which is what the compare-and-delete script compares.
            assertEquals("owner", globalLockOperations.opsForValue().getAndAwait(lockKey))
            // SET NX: a second caller cannot take a held lock.
            assertFalse(lockSupport.globalLock(lockKey, "intruder", 10_000))
            // Compare-and-delete: a non-owner cannot release it either.
            assertFalse(lockSupport.globalUnLock(lockKey, "intruder"))
            assertTrue(lockSupport.globalUnLock(lockKey, "owner"))
            assertNull(globalLockOperations.opsForValue().getAndAwait(lockKey))
        }
}
