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

package com.linecorp.cse.reqshield.spring.webflux.example.service

import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(SpringExtension::class)
class CacheAnnotationTest : AbstractRedisTest() {
    @Autowired
    private lateinit var sampleService: SampleService

    @Autowired
    lateinit var asyncCache: AsyncCache<Product>

    @BeforeEach
    fun `reset request count`() {
        sampleService.resetRequestCount()
    }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be only one times`() {
        val testProductId: String = UUID.randomUUID().toString()

        val flux =
            Flux
                .range(1, 20)
                .flatMap {
                    sampleService
                        .getProduct(testProductId)
                        .subscribeOn(Schedulers.boundedElastic())
                }.collectList()

        StepVerifier
            .create(flux)
            .assertNext { productList ->
                assertEquals(1, sampleService.getRequestCount(), "Request count should be 1")
            }.verifyComplete()
    }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be request count times(only update cache mode)`() {
        val testProductId: String = UUID.randomUUID().toString()

        val flux =
            Flux
                .range(1, 20)
                .flatMap {
                    sampleService
                        .getProductOnlyUpdateCache(testProductId)
                        .subscribeOn(Schedulers.boundedElastic())
                }.collectList()

        StepVerifier
            .create(flux)
            .assertNext { productList ->
                assertEquals(19, sampleService.getRequestCount(), "Request count should be 19")
            }.verifyComplete()
    }

    @Test
    fun `ReqShieldCacheable test - request to 'sampleService' should be only one times For Global Lock`() {
        val testProductId: String = UUID.randomUUID().toString()

        val flux =
            Flux
                .range(1, 20)
                .flatMap {
                    sampleService
                        .getProductForGlobalLock(testProductId)
                        .subscribeOn(Schedulers.boundedElastic())
                }.collectList()

        StepVerifier
            .create(flux)
            .assertNext { productList ->
                assertEquals(1, sampleService.getRequestCount(), "Request count should be 1")
            }.verifyComplete()
    }

    @Test
    fun `ReqShieldCacheEvict test - after eviction, cache should be removed`() {
        // given
        val testProductId: String = UUID.randomUUID().toString()
        val productMono = sampleService.getProduct(testProductId).subscribeOn(Schedulers.boundedElastic())

        StepVerifier
            .create(productMono)
            .expectNextMatches { product ->
                assertNotNull(product)
                true
            }.expectComplete()
            .verify()

        // then
        await().atMost(5, TimeUnit.SECONDS).until {
            asyncCache.get("product-$testProductId").block() != null
        }
        val cacheMono = asyncCache.get("product-$testProductId").block()
        assertNotNull(cacheMono)

        // when
        val removeProductMono = sampleService.removeProduct(testProductId).subscribeOn(Schedulers.boundedElastic())

        StepVerifier
            .create(removeProductMono)
            .expectNextMatches { boolean ->
                assertTrue(boolean)
                true
            }.expectComplete()
            .verify()

        // then
        await().atMost(5, TimeUnit.SECONDS).until {
            asyncCache.get("product-$testProductId").block() == null
        }
        val cacheMonoNull = asyncCache.get("product-$testProductId").block()
        assertNull(cacheMonoNull)
    }
}
