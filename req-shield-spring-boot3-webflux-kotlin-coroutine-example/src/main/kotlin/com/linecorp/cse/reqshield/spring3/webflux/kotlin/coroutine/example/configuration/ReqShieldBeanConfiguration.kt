package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.configuration

import com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield
import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.setAndAwait
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
                    reactiveRedisOperations.opsForValue().setAndAwait(
                        key,
                        value,
                        Duration.ofMillis(timeToLiveMillis),
                    )
                },
                getCacheFunction = { key -> reactiveRedisOperations.opsForValue()[key].awaitFirstOrNull() },
                isLocalLock = true,
                decisionForUpdate = 70,
            ),
        )
}
