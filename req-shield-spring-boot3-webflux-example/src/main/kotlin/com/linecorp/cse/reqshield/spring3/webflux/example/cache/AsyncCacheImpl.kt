package com.linecorp.cse.reqshield.spring3.webflux.example.cache

import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Duration

@Service
class AsyncCacheImpl<T>(
    private val redisOperations: ReactiveRedisOperations<String, ReqShieldData<T>>,
    private val redisOperationsForGlobalLock: ReactiveRedisOperations<String, String>,
) : AsyncCache<T> {
    override fun get(key: String): Mono<ReqShieldData<T>?> = redisOperations.opsForValue()[key]

    override fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Mono<Boolean> = redisOperations.opsForValue().set(key, value, Duration.ofMillis(timeToLiveMillis))

    override fun evict(key: String): Mono<Boolean> = redisOperations.opsForValue().delete(key)

    override fun globalLock(
        key: String,
        timeToLiveMillis: Long,
    ): Mono<Boolean> =
        redisOperationsForGlobalLock.opsForValue().setIfAbsent(key, key, Duration.ofMillis(timeToLiveMillis)) ?: Mono.just(false)

    override fun globalUnLock(key: String): Mono<Boolean> =
        redisOperationsForGlobalLock
            .delete(key)
            .map { count -> count > 0 }
}
