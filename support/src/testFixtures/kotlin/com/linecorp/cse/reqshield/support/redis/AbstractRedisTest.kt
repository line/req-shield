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

@ContextConfiguration(initializers = [AbstractRedisTest.Companion.Initializer::class])
abstract class AbstractRedisTest {
    companion object {
        // Lazy initialization to avoid starting Testcontainers when external Redis is available
        private val redisContainer by lazy { RedisContainer.instance }

        // Lazy-initialized Redis connection info - computed once on first access
        private val connectionInfo: Pair<String, Int> by lazy {
            val externalHost =
                System.getProperty("test.redis.host")
                    ?: System.getenv("TEST_REDIS_HOST")
            val externalPortStr =
                System.getProperty("test.redis.port")
                    ?: System.getenv("TEST_REDIS_PORT")

            if (!externalHost.isNullOrBlank() && !externalPortStr.isNullOrBlank()) {
                val parsedPort =
                    externalPortStr.toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid TEST_REDIS_PORT value: '$externalPortStr'. Expected a valid integer.",
                        )
                externalHost to parsedPort
            } else {
                // Ensure the Testcontainers Redis is started before reading host/port
                if (!redisContainer.isRunning) {
                    redisContainer.start()
                }
                redisContainer.host to redisContainer.getMappedPort(6379)
            }
        }

        // Redis connection info accessible to subclasses
        val redisHost: String get() = connectionInfo.first
        val redisPort: Int get() = connectionInfo.second

        internal class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                val env = context.environment
                val properties: HashMap<String, Any> = hashMapOf()

                // Trigger lazy initialization and get host/port
                val host = redisHost
                val port = redisPort

                // Spring Boot 2.x style
                properties["spring.redis.host"] = host
                properties["spring.redis.port"] = port
                // Spring Boot 3.x (Spring Data Redis) style
                properties["spring.data.redis.host"] = host
                properties["spring.data.redis.port"] = port

                val propertySource = MapPropertySource("testProperties", properties)
                env.propertySources.addFirst(propertySource)
            }
        }
    }
}
