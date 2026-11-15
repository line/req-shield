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

package com.linecorp.cse.reqshield.support.utils

import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.REPRESENTATIVE_FIRST_POLL_MILLIS

/**
 * The wait before the next cache read of the request that reads the cache for its JVM, while a request of another
 * JVM holds the global lock: 5, 10, 20, 40 ms and then the poll interval of 50 ms. [waitIndex] is 0 for the wait
 * after the first read.
 *
 * Only one request of a JVM reads at these short waits, so the cache gets few extra reads, and the other requests
 * of that JVM get an entry of a fast supplier long before a whole poll interval ends. No wait is longer than the
 * poll interval, so an entry of a slow supplier is found as early as with the fixed interval.
 */
fun representativePollMillis(waitIndex: Int): Long {
    require(waitIndex >= 0) { "waitIndex must not be negative, but was $waitIndex" }
    // 5 shl 4 is 80, which is over the interval already: larger shifts are not computed, so they cannot overflow
    return if (waitIndex >= 4) {
        GET_CACHE_INTERVAL_MILLIS
    } else {
        minOf(
            GET_CACHE_INTERVAL_MILLIS,
            REPRESENTATIVE_FIRST_POLL_MILLIS shl waitIndex,
        )
    }
}

fun decideToUpdateCache(
    createdAt: Long,
    timeToLiveMillis: Long,
    decisionForUpdate: Int,
): Boolean {
    val currentTime = System.currentTimeMillis()
    val passedDuration = currentTime - createdAt

    return passedDuration >= timeToLiveMillis * (decisionForUpdate / 100.0)
}
