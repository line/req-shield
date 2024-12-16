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
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.deleteAndAwait
import org.springframework.data.redis.core.getAndAwait
import org.springframework.data.redis.core.setAndAwait
import org.springframework.data.redis.core.setIfAbsentAndAwait
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class AsyncCacheImpl<T>(
    private val redisOperations: ReactiveRedisOperations<String, ReqShieldData<T>>,
    private val redisOperationsForGlobalLock: ReactiveRedisOperations<String, String>,
) : AsyncCache<T> {
    override suspend fun get(key: String): ReqShieldData<T>? = redisOperations.opsForValue().getAndAwait(key)

    override suspend fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Boolean = redisOperations.opsForValue().setAndAwait(key, value, Duration.ofMillis(timeToLiveMillis))

    override suspend fun evict(key: String): Boolean = redisOperations.opsForValue().deleteAndAwait(key)

    override suspend fun globalLock(
        key: String,
        timeToLiveMillis: Long,
    ): Boolean = redisOperationsForGlobalLock.opsForValue().setIfAbsentAndAwait(key, key, Duration.ofMillis(timeToLiveMillis))

    override suspend fun globalUnLock(key: String): Boolean = redisOperationsForGlobalLock.deleteAndAwait(key) > 0
}
