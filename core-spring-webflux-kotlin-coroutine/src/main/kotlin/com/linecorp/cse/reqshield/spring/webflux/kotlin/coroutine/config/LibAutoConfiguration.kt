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
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_LOCK_ENTRIES_PROPERTY
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

@Configuration
@EnableAspectJAutoProxy
@Import(ReqShieldAspect::class)
open class LibAutoConfiguration(
    environment: Environment,
) {
    init {
        // The lock map is static, so the cap has to be pushed onto it once at startup. Reading it
        // from the Environment rather than a system property lets application.yml carry the value;
        // the Environment still ranks a -D override above the yml entry. The raw String is handed
        // to LocalLockLimit so that a malformed value is ignored with a warning here too, instead
        // of failing the context refresh the way Environment's own Long conversion would.
        LocalLockLimit.applyConfiguredValue(environment.getProperty(MAX_LOCK_ENTRIES_PROPERTY))
    }
}
