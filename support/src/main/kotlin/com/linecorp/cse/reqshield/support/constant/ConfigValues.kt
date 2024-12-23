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

package com.linecorp.cse.reqshield.support.constant

object ConfigValues {
    const val DEFAULT_LOCK_TIMEOUT_MILLIS = 3000L
    const val DEFAULT_DECISION_FOR_UPDATE = 80

    const val LOCK_MONITOR_INTERVAL_MILLIS = 10L

    const val MAX_ATTEMPT_GET_CACHE = 50
    const val GET_CACHE_INTERVAL_MILLIS = 50L

    const val MAX_ATTEMPT_SET_CACHE = 3
    const val SET_CACHE_RETRY_INTERVAL_MILLIS = 100L
}
