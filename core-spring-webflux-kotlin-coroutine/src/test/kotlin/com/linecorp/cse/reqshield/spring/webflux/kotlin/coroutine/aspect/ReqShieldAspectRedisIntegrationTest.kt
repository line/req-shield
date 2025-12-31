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

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.concurrent.atomic.AtomicInteger

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LibAutoConfiguration::class, ReqShieldAspectRedisIntegrationTest.TestConfig::class])
class ReqShieldAspectRedisIntegrationTest : AbstractRedisTest() {
    @Autowired
    private lateinit var service: TestService

    @Autowired
    private lateinit var asyncCache: AsyncCache<String>

    @BeforeEach
    fun resetCounter() {
        service.resetCounter()
    }

    private suspend fun awaitCachePut(
        key: String,
        timeoutMillis: Long = 2_000,
    ): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (asyncCache.get(key) == null) {
                delay(10)
            }
            true
        } ?: false

    @Test
    fun shouldCollapseDuplicateRequestsWithRedis() =
        runBlocking {
            val key = "dup-redis-${System.nanoTime()}" // Use unique key for test isolation
            val attempts = 20
            val results = (1..attempts).map { async(Dispatchers.IO) { service.get(key) } }.awaitAll()

            // Request collapsing core: callable should be invoked only once
            assertTrue(
                service.getRequestCount() == 1,
                "Callable should be invoked only once. actual=${service.getRequestCount()}",
            )

            // All results should be valid (not null)
            assertTrue(
                results.size == attempts && results.all { it != null },
                "Expected all results to be valid. results=$results",
            )
        }

    @Test
    fun shouldEvictAndRecomputeWithRedis() =
        runBlocking {
            val key = "evict-redis-${System.nanoTime()}" // Use unique key for test isolation
            val v1 = service.get(key)
            // ReqShield stores cache asynchronously; wait until the cache write is observed.
            assertTrue(awaitCachePut(key), "Timed out waiting for cache put for key=$key")
            val evicted = service.evict(key)
            val v2 = service.get(key)
            assertTrue(evicted, "Eviction should return true")
            assertTrue(v1 != v2, "Values should differ after eviction: v1=$v1, v2=$v2")
        }

    @Configuration
    open class TestConfig {
        @Value("\${spring.redis.host}")
        private lateinit var host: String

        @Value("\${spring.redis.port}")
        private var port: Int = 0

        @Bean(destroyMethod = "shutdown")
        open fun redisClient(): RedisClient = RedisClient.create("redis://$host:$port")

        @Bean(destroyMethod = "close")
        open fun redisConnection(redisClient: RedisClient): StatefulRedisConnection<String, String> = redisClient.connect()

        @Bean
        open fun asyncCache(redisConnection: StatefulRedisConnection<String, String>): AsyncCache<String> {
            val sync: RedisCommands<String, String> = redisConnection.sync()
            // Ensure clean DB state for tests running in CI
            runCatching { sync.flushdb() }

            return object : AsyncCache<String> {
                override suspend fun get(key: String): ReqShieldData<String>? =
                    sync.get(key)?.let { ReqShieldData(value = it, timeToLiveMillis = 10_000) }

                override suspend fun put(
                    key: String,
                    value: ReqShieldData<String>,
                    timeToLiveMillis: Long,
                ): Boolean {
                    sync.psetex(key, timeToLiveMillis, value.value ?: "")
                    return true
                }

                override suspend fun evict(key: String): Boolean = sync.del(key) > 0

                override suspend fun globalLock(
                    key: String,
                    timeToLiveMillis: Long,
                ): Boolean = sync.setnx("lock:$key", "1").also { if (it) sync.pexpire("lock:$key", timeToLiveMillis) }

                override suspend fun globalUnLock(key: String): Boolean = sync.del("lock:$key") >= 0
            }
        }

        @Bean
        open fun service(): TestService = TestService()
    }

    open class TestService {
        val counter = AtomicInteger(0)

        open fun resetCounter() {
            counter.set(0)
        }

        open fun getRequestCount(): Int = counter.get()

        @ReqShieldCacheable(
            cacheName = "it",
            key = "#key",
            timeToLiveMillis = 10_000,
            // CI environments can be slow; give enough time for async cache put to be observed by waiters.
            maxAttemptGetCache = 200,
            lockTimeoutMillis = 10_000,
        )
        open suspend fun get(key: String): String = "value-" + counter.incrementAndGet()

        @ReqShieldCacheEvict(cacheName = "it", key = "#key")
        open suspend fun evict(key: String): Boolean = true
    }
}
