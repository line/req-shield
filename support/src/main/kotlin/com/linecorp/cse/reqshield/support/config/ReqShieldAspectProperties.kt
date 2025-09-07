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

package com.linecorp.cse.reqshield.support.config

/**
 * Configuration properties for ReqShield aspect internal caches.
 *
 * Usage in Spring Boot:
 * 1. Add @EnableConfigurationProperties(ReqShieldAspectProperties::class) to your config
 * 2. Configure in application.yml:
 *    req-shield:
 *      aspect:
 *        cache-max-size: 500
 *        enable-metrics: true
 */
data class ReqShieldAspectProperties(
    /**
     * Maximum size for aspect internal caches (keyGeneratorMap and reqShieldMap).
     * Default: 1000
     */
    val cacheMaxSize: Int = 1000,
    /**
     * Enable cache usage metrics logging.
     * Default: false
     */
    val enableMetrics: Boolean = false,
)
