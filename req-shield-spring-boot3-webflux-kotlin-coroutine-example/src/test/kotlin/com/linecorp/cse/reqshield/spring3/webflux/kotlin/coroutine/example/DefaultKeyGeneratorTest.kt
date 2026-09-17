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

package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.dto.Product
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs on Spring 6.1, which strips the trailing `Continuation` of a suspend function itself before
 * generating a key. Only here can a default-generated key that ignores the method arguments be
 * observed, so this is where the collapse of unrelated arguments onto one cache key is guarded.
 */
@SpringBootTest(
    classes = [SpringWebfluxCoroutineApplication::class, DefaultKeyGeneratorTestConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ExtendWith(SpringExtension::class)
class DefaultKeyGeneratorTest : AbstractRedisTest() {
    @Autowired
    private lateinit var defaultKeyGeneratorService: DefaultKeyGeneratorService

    @Test
    fun differentArgumentsShouldNotShareTheDefaultGeneratedKey() =
        runBlocking {
            val firstId = UUID.randomUUID().toString()
            val secondId = UUID.randomUUID().toString()

            // Both requests run at the same time: sharing one key would collapse the second one onto
            // the first and hand it the product the first request cached.
            val (first, second) =
                listOf(
                    async { defaultKeyGeneratorService.getProduct(firstId) },
                    async { defaultKeyGeneratorService.getProduct(secondId) },
                ).awaitAll()

            assertEquals(firstId, first.productId)
            assertEquals(secondId, second.productId)
            assertEquals(2, defaultKeyGeneratorService.callCount())
        }
}

@TestConfiguration(proxyBeanMethods = false)
class DefaultKeyGeneratorTestConfiguration {
    @Bean
    fun defaultKeyGeneratorService(): DefaultKeyGeneratorService = DefaultKeyGeneratorService()
}

/** Neither `key` nor `keyGenerator` is set, so the key comes from Spring's `SimpleKeyGenerator`. */
open class DefaultKeyGeneratorService {
    private val counter = AtomicInteger(0)

    open fun callCount(): Int = counter.get()

    @ReqShieldCacheable(cacheName = "defaultKeyGenerator", timeToLiveMillis = 60 * 1000)
    open suspend fun getProduct(productId: String): Product {
        // slow enough that the second request reaches the lock before the first one finishes
        delay(300)
        counter.incrementAndGet()

        return Product(productId, "product_$productId")
    }
}
