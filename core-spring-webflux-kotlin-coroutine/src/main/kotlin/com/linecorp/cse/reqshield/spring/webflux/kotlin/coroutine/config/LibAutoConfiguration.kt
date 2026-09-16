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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.config

import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.aspect.ReqShieldAspect
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import kotlin.coroutines.CoroutineContext

@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@Import(ReqShieldAspect::class)
open class LibAutoConfiguration {
    /**
     * Scope shared by every [com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield] the aspect
     * creates, used for the fire-and-forget cache writes.
     *
     * SupervisorJob keeps one failed write from cancelling the others, and the exception handler is
     * the last-resort backstop for anything the write path did not already log.
     */
    @Bean
    open fun reqShieldCoroutineScope(): CoroutineScope =
        ReqShieldCoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e ->
                    log.error("[Req-Shield] background task failed", e)
                },
        )

    /**
     * [CoroutineScope] has no `cancel` member, so the bean carries its own shutdown hook: closing
     * the application context cancels the scope and with it every pending cache write.
     */
    private class ReqShieldCoroutineScope(
        override val coroutineContext: CoroutineContext,
    ) : CoroutineScope,
        DisposableBean {
        override fun destroy() {
            coroutineContext.cancel()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LibAutoConfiguration::class.java)
    }
}
