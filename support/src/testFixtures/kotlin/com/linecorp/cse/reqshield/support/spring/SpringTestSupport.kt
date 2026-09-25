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

package com.linecorp.cse.reqshield.support.spring

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.function.Supplier

/**
 * Runs [block] against a context built from [configurations] plus [bean] registered under [beanName], and closes it.
 *
 * Bean definition overriding is disallowed as in Spring Boot, so a library bean of the same name fails the refresh.
 */
inline fun <reified T : Any> withNamedBean(
    beanName: String,
    bean: T,
    vararg configurations: Class<*>,
    block: (AnnotationConfigApplicationContext) -> Unit,
) {
    val context = AnnotationConfigApplicationContext()
    context.setAllowBeanDefinitionOverriding(false)
    context.register(*configurations)
    context.registerBean(beanName, T::class.java, Supplier { bean })
    context.refresh()
    context.use(block)
}
