package com.linecorp.cse.reqshield.spring3.webflux.example.cache

import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Compare-and-delete: the lock is only released when it still holds the caller's token, so an owner
 * whose lock already expired cannot release the lock of the next owner.
 */
private const val UNLOCK_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"

@Service
class AsyncCacheImpl<T>(
    private val redisOperations: ReactiveRedisOperations<String, ReqShieldData<T>>,
    private val redisOperationsForGlobalLock: ReactiveRedisOperations<String, String>,
) : AsyncCache<T>,
    GlobalLockSupport {
    override fun get(key: String): Mono<ReqShieldData<T>?> = redisOperations.opsForValue()[key]

    override fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Mono<Boolean> = redisOperations.opsForValue().set(key, value, Duration.ofMillis(timeToLiveMillis))

    override fun evict(key: String): Mono<Boolean> = redisOperations.opsForValue().delete(key)

    override fun globalLock(
        lockKey: String,
        token: String,
        timeToLiveMillis: Long,
    ): Mono<Boolean> = redisOperationsForGlobalLock.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(timeToLiveMillis))

    override fun globalUnLock(
        lockKey: String,
        token: String,
    ): Mono<Boolean> =
        redisOperationsForGlobalLock
            .execute(RedisScript.of(UNLOCK_SCRIPT, Long::class.java), listOf(lockKey), listOf(token))
            .next()
            .map { it == 1L }
            .defaultIfEmpty(false)
}
