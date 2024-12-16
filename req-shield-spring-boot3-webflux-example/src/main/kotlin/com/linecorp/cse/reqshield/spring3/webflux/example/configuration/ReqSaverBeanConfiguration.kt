package com.linecorp.cse.reqshield.spring3.webflux.example.configuration

import com.linecorp.cse.reqshield.reactor.ReqShield
import com.linecorp.cse.reqshield.reactor.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveRedisOperations
import reactor.core.publisher.Mono
import java.time.Duration

@Configuration
class ReqShieldBeanConfiguration<T>(
    private val reactiveRedisOperations: ReactiveRedisOperations<String, ReqShieldData<T>>,
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
                    reactiveRedisOperations.opsForValue().setIfAbsent(key, value, Duration.ofMillis(timeToLiveMillis)) ?: Mono.just(false)
                },
                getCacheFunction = { key -> reactiveRedisOperations.opsForValue()[key] },
            ),
        )
}
