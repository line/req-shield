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

package com.linecorp.cse.reqshield.spring.webflux.aspect

import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.config.LibAutoConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.concurrent.atomic.AtomicInteger

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectIntegrationTest.TestConfig::class])
class ReqShieldAspectIntegrationTest {
    @Autowired
    private lateinit var service: TestService

    @Test
    fun shouldCollapseDuplicateRequests() {
        val key = "dup"
        val attempts = 20

        val result =
            Flux
                .range(1, attempts)
                .flatMap { service.get(key).subscribeOn(Schedulers.boundedElastic()) }
                .collectList()
                .block()

        assertEquals(attempts, result?.size)
        val first = result?.firstOrNull()
        assertTrue(result?.all { it == first } == true)
    }

    @Test
    fun shouldEvictAndRecompute() {
        val key = "evict"
        val v1 = service.get(key).block()
        val evicted = service.evict(key).block()
        val v2 = service.get(key).block()

        assertTrue(evicted == true)
        assertTrue(v1 != null)
        assertTrue(v2 != null)
        assertTrue(v1 != v2)
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun service(): TestService = TestService()
    }

    open class TestService {
        val counter = AtomicInteger(0)

        @ReqShieldCacheable(cacheName = "it", key = "#key", timeToLiveMillis = 10_000)
        open fun get(key: String): Mono<String> = Mono.fromCallable { "value-" + counter.incrementAndGet() }

        @ReqShieldCacheEvict(cacheName = "it", key = "#key")
        open fun evict(key: String): Mono<Boolean> = Mono.just(true)
    }
}
