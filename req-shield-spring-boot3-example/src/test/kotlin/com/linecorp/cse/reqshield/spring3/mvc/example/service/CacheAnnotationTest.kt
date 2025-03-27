package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ExtendWith(SpringExtension::class)
class CacheAnnotationTest : AbstractRedisTest() {
    @Autowired
    private lateinit var sampleService: SampleService

    @Autowired
    private lateinit var reqShieldCache: ReqShieldCache<Product>

    @BeforeEach
    fun `reset request count`() {
        sampleService.resetRequestCount()
    }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be only one times`() {
        val executorService = Executors.newFixedThreadPool(100)

        val testProductId: String = UUID.randomUUID().toString()

        for (i in 1..100) {
            executorService.submit {
                sampleService.getProduct(testProductId)
            }
        }

        executorService.shutdown()
        executorService.awaitTermination(3000, TimeUnit.SECONDS)

        await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(1, sampleService.getRequestCount())
        }
    }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be only one times for global lock`() {
        val executorService = Executors.newFixedThreadPool(100)

        val testProductId: String = UUID.randomUUID().toString()

        for (i in 1..100) {
            executorService.submit {
                sampleService.getProductForGlobalLock(testProductId)
            }
        }

        executorService.shutdown()
        executorService.awaitTermination(3000, TimeUnit.SECONDS)

        Thread.sleep(1000)

        await().atMost(Duration.ofMillis(BaseReqShieldTest.AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(1, sampleService.getRequestCount())
        }
    }

    @Test
    fun `ReqShieldCacheEvict test - after eviction, cache should be removed`() {
        // given
        val testProductId: String = UUID.randomUUID().toString()
        sampleService.getProduct(testProductId)

        await().atMost(5, TimeUnit.SECONDS).until {
            reqShieldCache.get("product-$testProductId") != null
        }

        assertNotNull(reqShieldCache.get("product-$testProductId"))

        // when
        sampleService.removeProduct(testProductId)

        // then
        await().atMost(5, TimeUnit.SECONDS).until {
            reqShieldCache.get("product-$testProductId") == null
        }

        assertNull(reqShieldCache.get("product-$testProductId"))
    }
}
