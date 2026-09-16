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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.example.cache

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.deleteAndAwait
import org.springframework.data.redis.core.getAndAwait
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.core.setAndAwait
import org.springframework.data.redis.core.setIfAbsentAndAwait
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class AsyncCacheImpl<T>(
    private val redisOperations: ReactiveRedisOperations<String, ReqShieldData<T>>,
    private val redisOperationsForGlobalLock: ReactiveRedisOperations<String, String>,
) : AsyncCache<T>,
    GlobalLockSupport {
    override suspend fun get(key: String): ReqShieldData<T>? = redisOperations.opsForValue().getAndAwait(key)

    override suspend fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Boolean = redisOperations.opsForValue().setAndAwait(key, value, Duration.ofMillis(timeToLiveMillis))

    override suspend fun evict(key: String): Boolean = redisOperations.opsForValue().deleteAndAwait(key)

    /** `SET lockKey token NX PX ttl`: the stored token identifies the caller that owns the lock. */
    override suspend fun globalLock(
        lockKey: String,
        token: String,
        timeToLiveMillis: Long,
    ): Boolean =
        redisOperationsForGlobalLock
            .opsForValue()
            .setIfAbsentAndAwait(lockKey, token, Duration.ofMillis(timeToLiveMillis))

    /**
     * Compare-and-delete in a single atomic step, so an owner whose lock already expired can never
     * release the lock that the next owner has taken in the meantime.
     */
    override suspend fun globalUnLock(
        lockKey: String,
        token: String,
    ): Boolean =
        redisOperationsForGlobalLock
            .execute(UNLOCK_SCRIPT, listOf(lockKey), listOf(token))
            .awaitFirstOrNull() == 1L

    companion object {
        /** Returns the number of keys deleted: 1 when this caller still owned the lock, 0 otherwise. */
        private val UNLOCK_SCRIPT: RedisScript<Long> =
            RedisScript.of(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                Long::class.java,
            )
    }
}
