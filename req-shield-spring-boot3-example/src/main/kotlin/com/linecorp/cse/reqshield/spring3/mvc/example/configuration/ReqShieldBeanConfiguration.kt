package com.linecorp.cse.reqshield.spring3.mvc.example.configuration

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

@Configuration
class ReqShieldBeanConfiguration<T>(
    private val redisTemplate: RedisTemplate<String, ReqShieldData<T>>,
) {
    @Bean
    fun reqShield(): ReqShield<T> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = {
                    key,
                    value,
                    timeToLiveMillis,
                    ->
                    redisTemplate.opsForValue().setIfAbsent(key, value, Duration.ofMillis(timeToLiveMillis)) ?: false
                },
                getCacheFunction = { key -> redisTemplate.opsForValue()[key] },
            ),
        )
}
