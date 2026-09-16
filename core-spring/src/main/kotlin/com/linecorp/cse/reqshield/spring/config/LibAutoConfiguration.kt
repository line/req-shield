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

package com.linecorp.cse.reqshield.spring.config

import com.linecorp.cse.reqshield.spring.aspect.ReqShieldAspect
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

@Configuration
@EnableAspectJAutoProxy
@Import(ReqShieldAspect::class)
open class LibAutoConfiguration {
    /**
     * Pool shared by every [com.linecorp.cse.reqshield.ReqShield] the aspect creates, used for the
     * asynchronous cache writes and for polling the cache while another request holds the lock.
     *
     * Spring's inferred destroy method calls [ScheduledExecutorService.shutdown] when the context is
     * closed; the threads are daemons anyway so a pending task can never block JVM shutdown.
     */
    @Bean
    open fun reqShieldExecutor(): ScheduledExecutorService {
        val threadCounter = AtomicLong(0)

        return Executors.newScheduledThreadPool(
            maxOf(2, Runtime.getRuntime().availableProcessors() * 2),
        ) { runnable ->
            Thread(runnable, "req-shield-executor-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
}
