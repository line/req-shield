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

package com.linecorp.cse.reqshield.support.redis

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.test.context.ContextConfiguration
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@ContextConfiguration(initializers = [AbstractRedisTest.Companion.Initializer::class])
abstract class AbstractRedisTest {
    companion object {
        @Container
        private val redisContainer = RedisContainer.instance

        internal class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                val env = context.environment

                val properties: HashMap<String, Any> = hashMapOf()
                properties["spring.redis.host"] = redisContainer.host
                properties["spring.redis.port"] = redisContainer.getMappedPort(6379)

                val propertySource = MapPropertySource("testProperties", properties)
                env.propertySources.addFirst(propertySource)
            }
        }
    }
}
