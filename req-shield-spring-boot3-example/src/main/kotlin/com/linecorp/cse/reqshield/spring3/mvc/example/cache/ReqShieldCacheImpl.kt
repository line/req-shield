package com.linecorp.cse.reqshield.spring3.mvc.example.cache

import com.linecorp.cse.reqshield.spring.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class ReqShieldCacheImpl<T>(
    private val redisTemplate: RedisTemplate<String, ReqShieldData<T>>,
    private val redisTemplateForGlobalLock: RedisTemplate<String, String>,
) : ReqShieldCache<T>,
    GlobalLockSupport {
    override fun get(key: String): ReqShieldData<T>? = redisTemplate.opsForValue()[key]

    override fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ) = redisTemplate.opsForValue().set(key, value, Duration.ofMillis(timeToLiveMillis))

    override fun evict(key: String): Boolean? = redisTemplate.delete(key)

    override fun globalLock(
        lockKey: String,
        token: String,
        timeToLiveMillis: Long,
    ): Boolean = redisTemplateForGlobalLock.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(timeToLiveMillis)) ?: false

    override fun globalUnLock(
        lockKey: String,
        token: String,
    ): Boolean = redisTemplateForGlobalLock.execute(UN_LOCK_SCRIPT, listOf(lockKey), token) == 1L

    companion object {
        /** Compare-and-delete, so an expired holder cannot release the lock of the next holder. */
        private val UN_LOCK_SCRIPT =
            DefaultRedisScript(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                Long::class.javaObjectType,
            )
    }
}
