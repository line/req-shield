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

package com.linecorp.cse.reqshield.cache

import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class ReqShieldCacheImpl<T>(
    private val redisTemplate: RedisTemplate<String, ReqShieldData<T>>,
    private val redisTemplateForGlobalLock: RedisTemplate<String, String>,
) : ReqShieldCache<T> {
    override fun get(key: String): ReqShieldData<T>? = redisTemplate.opsForValue()[key]

    override fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ) = redisTemplate.opsForValue().set(key, value, Duration.ofMillis(timeToLiveMillis))

    override fun evict(key: String): Boolean? = redisTemplate.delete(key)

    override fun globalLock(
        key: String,
        timeToLiveMillis: Long,
    ): Boolean = redisTemplateForGlobalLock.opsForValue().setIfAbsent(key, key, Duration.ofMillis(timeToLiveMillis)) ?: false

    override fun globalUnLock(key: String): Boolean = redisTemplateForGlobalLock.delete(key)
}
