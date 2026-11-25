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

import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Mono
import java.time.Duration

/** The background refresh of an annotated method runs with the Reactor Context of the request that started it. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectRefreshContextIntegrationTest.ContextConfig::class])
class ReqShieldAspectRefreshContextIntegrationTest {
    @Autowired
    private lateinit var service: ContextService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @Test
    fun refreshOfAnAnnotatedMethodShouldSeeTheReactorContextOfTheCaller() {
        val key = "profile::p-${System.nanoTime()}"
        val ttl = 10_000L
        // An entry that has used 90% of its TTL, so the next read starts a background refresh
        val stale = ReqShieldData("old", ReqShieldData.Status.NEW, System.currentTimeMillis() - (ttl * 0.9).toLong(), ttl)
        asyncCache.put(key, stale, ttl).block()

        val returned = service.profile(key.substringAfter("::")).contextWrite { it.put(USER, "alice") }.block()

        assertEquals("old", returned)
        // Without the context the method completes empty, and the refresh would store a null value instead
        await().atMost(Duration.ofSeconds(5)).until { asyncCache.get(key).block()?.value != "old" }
        assertEquals("profile-of-alice", asyncCache.get(key).block()?.value)
    }

    @Configuration
    open class ContextConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun contextService(): ContextService = ContextService()
    }

    open class ContextService {
        /** Reads the caller from the Reactor Context, the way ReactiveSecurityContextHolder does. */
        @ReqShieldCacheable(cacheName = "profile", key = "#id", timeToLiveMillis = 10_000)
        open fun profile(id: String): Mono<String> =
            Mono.deferContextual { context -> Mono.justOrEmpty(context.getOrEmpty<String>(USER)).map { "profile-of-$it" } }
    }

    companion object {
        private const val USER = "user"
    }
}
