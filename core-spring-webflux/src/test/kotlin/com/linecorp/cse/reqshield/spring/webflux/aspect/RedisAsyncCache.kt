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

import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import reactor.core.publisher.Mono

private const val UNLOCK_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"

/**
 * Reference Redis implementation of the documented locking recipe: `SET NX PX` to acquire and a
 * compare-and-delete Lua script to release, so only the token holder can release the lock.
 */
class RedisAsyncCache(
    private val sync: RedisCommands<String, String>,
    private val reactive: RedisReactiveCommands<String, String>,
) : AsyncCache<String>,
    GlobalLockSupport {
    override fun get(key: String): Mono<ReqShieldData<String>?> =
        Mono.fromCallable {
            sync.get(key)?.let { ReqShieldData(value = it, timeToLiveMillis = 10_000) }
        }

    override fun put(
        key: String,
        value: ReqShieldData<String>,
        timeToLiveMillis: Long,
    ): Mono<Boolean> =
        Mono.fromCallable {
            sync.psetex(key, timeToLiveMillis, value.value ?: "")
            true
        }

    override fun evict(key: String): Mono<Boolean> = Mono.fromCallable { sync.del(key) > 0 }

    override fun globalLock(
        lockKey: String,
        token: String,
        timeToLiveMillis: Long,
    ): Mono<Boolean> =
        reactive
            .set(lockKey, token, SetArgs.Builder.nx().px(timeToLiveMillis))
            .map { it == "OK" }
            .defaultIfEmpty(false)

    override fun globalUnLock(
        lockKey: String,
        token: String,
    ): Mono<Boolean> =
        reactive
            .eval<Long>(UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token)
            .next()
            .map { it == 1L }
            .defaultIfEmpty(false)
}
