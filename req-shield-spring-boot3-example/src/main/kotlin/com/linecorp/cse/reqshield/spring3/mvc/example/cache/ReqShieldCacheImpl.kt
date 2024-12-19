package com.linecorp.cse.reqshield.spring3.mvc.example.cache

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
