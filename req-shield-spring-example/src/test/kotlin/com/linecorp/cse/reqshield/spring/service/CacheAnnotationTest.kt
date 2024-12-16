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

package com.linecorp.cse.reqshield.spring.service

import com.linecorp.cse.reqshield.service.SampleService
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.time.Duration
import java.util.*
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
            reqShieldCache.get("product-[$testProductId]") != null
        }

        assertNotNull(reqShieldCache.get("product-[$testProductId]"))

        // when
        sampleService.removeProduct(testProductId)

        // then
        await().atMost(5, TimeUnit.SECONDS).until {
            reqShieldCache.get("product-[$testProductId]") == null
        }

        assertNull(reqShieldCache.get("product-[$testProductId]"))
    }
}
