package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.cache

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.data.redis.core.*
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
    ): Boolean =
        redisOperationsForGlobalLock
            .opsForValue()
            .setIfAbsentAndAwait(key, key, Duration.ofMillis(timeToLiveMillis))

    override suspend fun globalUnLock(key: String): Boolean = redisOperationsForGlobalLock.deleteAndAwait(key) > 0
}
