package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.dto.Product
import com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.service.SampleService
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.getAndAwait
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith

@SpringBootTest(
    classes = [SpringWebfluxCoroutineApplication::class, EvictionTestConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ExtendWith(SpringExtension::class)
class CacheAnnotationTest : AbstractRedisTest() {
    @Autowired
    private lateinit var sampleService: SampleService

    @Autowired
    private lateinit var evictionTestService: EvictionTestService

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
    fun `requestToBackEndShouldBeOnlyOneTime_localLock`() =
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

            Assertions.assertEquals(20, sampleService.getRequestCount())
        }

    @Test
    fun `requestToBackEndShouldBeOnlyOneTime_globalLock`() =
        runBlocking {
            val testProductId: String = UUID.randomUUID().toString()

            List(20) {
                async {
                    sampleService.getProductForGlobalLock(testProductId)
                }
            }.awaitAll()

            delay(500)

            assertEquals(1, sampleService.getRequestCount())
            assertNotNull(asyncCache.get(cacheKeyOf(testProductId)))
        }

    @Test
    fun `cacheShouldBeRemovedAfterEviction`() =
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
    fun cacheEvictionShouldWaitForSuccessfulSuspendMethod() =
        runBlocking {
            val key = UUID.randomUUID().toString()
            putEvictionTestEntry(key)

            val cacheWasPresentDuringMethod = evictionTestService.evictReturningValue(key)

            assertTrue(cacheWasPresentDuringMethod)
            assertNull(asyncCache.get(evictionTestCacheKeyOf(key)))
        }

    @Test
    fun cacheEvictionShouldPreserveTheCacheWhenSuspendMethodFails() =
        runBlocking {
            val key = UUID.randomUUID().toString()
            putEvictionTestEntry(key)

            val exception = assertFailsWith<IllegalStateException> { evictionTestService.evictFailing(key) }

            assertEquals("cacheWasPresent=true", exception.message)
            assertNotNull(asyncCache.get(evictionTestCacheKeyOf(key)))
        }

    @Test
    fun cacheEvictionShouldWaitForSuspendMethodReturningEmptyMono() =
        runBlocking {
            val key = UUID.randomUUID().toString()
            val cacheWasPresentDuringMethod = AtomicBoolean()
            putEvictionTestEntry(key)

            assertNull(evictionTestService.evictReturningNull(key, cacheWasPresentDuringMethod))

            assertTrue(cacheWasPresentDuringMethod.get())
            assertNull(asyncCache.get(evictionTestCacheKeyOf(key)))
        }

    @Test
    fun globalLockShouldBeReleasedAfterTheOwningRequestCompletes() =
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
    fun onlyTheOwningTokenShouldReleaseTheGlobalLock() =
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

    private suspend fun putEvictionTestEntry(key: String) {
        val product = Product(key, "product_$key")
        assertTrue(asyncCache.put(evictionTestCacheKeyOf(key), ReqShieldData(product, 10_000), 10_000))
    }

    private fun evictionTestCacheKeyOf(key: String) = "$EVICTION_TEST_CACHE_NAME::$key"
}

@TestConfiguration(proxyBeanMethods = false)
class EvictionTestConfiguration {
    @Bean
    fun evictionTestService(asyncCache: AsyncCache<Product>): EvictionTestService = EvictionTestService(asyncCache)
}

open class EvictionTestService(
    private val asyncCache: AsyncCache<Product>,
) {
    @ReqShieldCacheEvict(cacheName = EVICTION_TEST_CACHE_NAME, key = "#key")
    open suspend fun evictReturningValue(key: String): Boolean = asyncCache.get(cacheKeyOf(key)) != null

    @ReqShieldCacheEvict(cacheName = EVICTION_TEST_CACHE_NAME, key = "#key")
    open suspend fun evictFailing(key: String): Boolean {
        val cacheWasPresent = asyncCache.get(cacheKeyOf(key)) != null
        throw IllegalStateException("cacheWasPresent=$cacheWasPresent")
    }

    @ReqShieldCacheEvict(cacheName = EVICTION_TEST_CACHE_NAME, key = "#key")
    open suspend fun evictReturningNull(
        key: String,
        cacheWasPresent: AtomicBoolean,
    ): String? {
        cacheWasPresent.set(asyncCache.get(cacheKeyOf(key)) != null)
        return null
    }

    private fun cacheKeyOf(key: String) = "$EVICTION_TEST_CACHE_NAME::$key"
}

private const val EVICTION_TEST_CACHE_NAME = "eviction-order"
