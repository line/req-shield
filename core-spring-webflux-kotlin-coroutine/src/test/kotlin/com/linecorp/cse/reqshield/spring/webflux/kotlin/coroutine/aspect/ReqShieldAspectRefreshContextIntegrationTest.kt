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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.aspect

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The background refresh of an annotated suspend method runs with the context elements of the request that started it. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectRefreshContextIntegrationTest.ContextConfig::class])
class ReqShieldAspectRefreshContextIntegrationTest {
    @Autowired
    private lateinit var service: ContextService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @Test
    fun refreshOfAnAnnotatedMethodShouldSeeTheContextElementsOfTheCaller() =
        runBlocking {
            val key = "profile::p-${System.nanoTime()}"
            val ttl = 10_000L
            // An entry that has used 90% of its TTL, so the next read starts a background refresh
            val stale = ReqShieldData("old", ReqShieldData.Status.NEW, System.currentTimeMillis() - (ttl * 0.9).toLong(), ttl)
            asyncCache.put(key, stale, ttl)

            val returned = withContext(CallerIdentity("alice")) { service.profile(key.substringAfter("::")) }

            assertEquals("old", returned)
            // Without the caller's elements the refresh would store the profile of nobody
            withTimeout(5_000) {
                while (asyncCache.get(key)?.value == "old") delay(5)
            }
            assertEquals("profile-of-alice", asyncCache.get(key)?.value)
        }

    class CallerIdentity(
        val user: String,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<CallerIdentity>
    }

    @Configuration
    open class ContextConfig {
        @Bean
        open fun asyncCache(): AsyncCache<String> = InMemoryAsyncCache()

        @Bean
        open fun contextService(): ContextService = ContextService()
    }

    open class ContextService {
        @ReqShieldCacheable(cacheName = "profile", key = "#id", timeToLiveMillis = 10_000)
        open suspend fun profile(id: String): String = "profile-of-" + (currentCoroutineContext()[CallerIdentity]?.user ?: "nobody")
    }
}
