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

package com.linecorp.cse.reqshield.spring.webflux.example.configuration

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
