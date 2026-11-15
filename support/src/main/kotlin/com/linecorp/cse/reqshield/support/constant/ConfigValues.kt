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
    const val DEFAULT_TIME_TO_LIVE_MILLIS = 10 * 60 * 1000L

    const val LOCK_MONITOR_INTERVAL_MILLIS = 1000L

    const val MAX_ATTEMPT_GET_CACHE = 60
    const val GET_CACHE_INTERVAL_MILLIS = 50L

    /**
     * A request that got a lock registers its signal just after the lock call. A request that lost the lock a
     * moment earlier finds no signal yet, so it looks for the signal once more after this pause, which is part of
     * [GET_CACHE_INTERVAL_MILLIS]. Without it, that request would wait the whole interval beside a holder of its JVM.
     */
    const val SIGNAL_REGISTRATION_GRACE_MILLIS = 2L

    /**
     * While waiting for another request to fill the cache, this many consecutive cache-read
     * failures are treated as a cache outage and the waiter falls back to the supplier at once.
     */
    const val MAX_CONSECUTIVE_GET_CACHE_FAILURES = 3

    /**
     * With a global lock, a JVM asks the lock store for the refresh lock of one key at most once in
     * [GET_CACHE_INTERVAL_MILLIS]. The local lock that counts those attempts has keys with this prefix, so they do
     * not collide with the local locks of the cache keys.
     */
    const val GLOBAL_REFRESH_ATTEMPT_KEY_PREFIX = "refresh-attempt:"

    /** Prefix applied to every lock key so lock entries can never collide with cache entries. */
    const val LOCK_KEY_PREFIX = "reqshield:lock:"

    /**
     * Caps how many entries the local lock map may hold. The core modules read it from the system
     * properties; the Spring modules read the same key from the Environment, which also covers
     * application.yml and REQ_SHIELD_LOCK_MAX_ENTRIES.
     */
    const val MAX_LOCK_ENTRIES_PROPERTY = "req-shield.lock.max-entries"

    /** Value of [MAX_LOCK_ENTRIES_PROPERTY] that leaves the local lock map uncapped. */
    const val UNLIMITED_LOCK_ENTRIES = 0L
}
