package com.linecorp.cse.reqshield.spring3.mvc.example.configuration

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

@Configuration
class ReqShieldBeanConfiguration<T>(
    private val redisTemplate: RedisTemplate<String, ReqShieldData<T>>,
    private val cacheManager: CacheManager,
) {
    @Bean
    fun reqShield(): ReqShield<T> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = {
                        name,
                        key,
                        value,
                        timeToLiveMillis,
                    ->
                    redisTemplate.opsForValue().setIfAbsent(key, value, Duration.ofMillis(timeToLiveMillis)) ?: false
                },
                getCacheFunction = { name, key -> redisTemplate.opsForValue()[key] },
            ),
        )

    @Bean
    fun localReqShield(): ReqShield<T> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { name, key, value, timeToLiveMillis ->
                    val cache = cacheManager.getCache(name)
                    cache?.put(key, value)
                    true
                },
                getCacheFunction = { name, key ->
                    val cache = cacheManager.getCache(name)
                    cache?.get(key)?.get() as? ReqShieldData<T>
                },
            ),
        )
}
