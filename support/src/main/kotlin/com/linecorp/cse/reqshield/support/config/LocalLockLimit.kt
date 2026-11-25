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

import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_LOCK_ENTRIES_PROPERTY
import com.linecorp.cse.reqshield.support.constant.ConfigValues.UNLIMITED_LOCK_ENTRIES
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val log = LoggerFactory.getLogger(LocalLockLimit::class.java)

/**
 * Cap on how many entries a local lock map may hold.
 *
 * An entry exists only while a lock is held, so the map's size tracks how many keys are being
 * locked *at the same time*, plus any lock whose holder never released it and that has not yet
 * timed out. The cap therefore bounds concurrent lock ownership, not request cardinality.
 *
 * Once the map is full, the next new key is handed a permit that no map entry backs. The caller
 * proceeds exactly as if it had taken the lock - it calls the supplier and writes the cache - so
 * the cap costs request collapsing for that key and nothing else. Making the caller wait instead
 * would be worse: there is no holder to wait for, so it would stall for the whole polling budget
 * and then return without ever populating the cache.
 *
 * Uncapped by default. Set [MAX_LOCK_ENTRIES_PROPERTY] as a system property, or let the Spring
 * modules pass the same key through [applyConfiguredValue], which also picks it up from
 * application.yml.
 *
 * The cap applies per lock map, and each core module owns one, so an application that somehow used
 * two of them would get the cap applied to each map separately.
 */
object LocalLockLimit {
    /**
     * Maximum number of entries a local lock map may hold; [UNLIMITED_LOCK_ENTRIES] disables the
     * cap. Assigning a negative value fails fast, which is what a programmatic caller wants; a
     * value that arrives as unparsed configuration is filtered by [parseMaxEntries] first, because
     * neither a class initializer nor an application context should die over a tuning knob typo.
     */
    @Volatile
    var maxEntries: Long = parseMaxEntries(System.getProperty(MAX_LOCK_ENTRIES_PROPERTY)) ?: UNLIMITED_LOCK_ENTRIES
        set(value) {
            require(value >= UNLIMITED_LOCK_ENTRIES) {
                "$MAX_LOCK_ENTRIES_PROPERTY must not be negative, but was $value"
            }
            field = value
        }

    private const val REJECTION_LOG_INTERVAL = 1000L

    private val rejectionCount = AtomicLong(0)

    /**
     * Applies a cap that arrived as configuration, the same way the system property is applied:
     * an absent value leaves the current cap alone, and an unusable one is reported and ignored.
     * The Spring modules route [MAX_LOCK_ENTRIES_PROPERTY] through here so that a typo behaves
     * identically whether it reached the library through application.yml or through -D.
     */
    fun applyConfiguredValue(rawValue: String?) {
        parseMaxEntries(rawValue)?.let { maxEntries = it }
    }

    /**
     * Whether a lock map holding [currentSize] entries must refuse to take a new one.
     *
     * [currentSize] is an estimate on a concurrent map and the check is not atomic with the
     * insertion it guards, so the cap is a soft one: concurrent callers can push the map a little
     * past it. That is fine for a footprint guard.
     *
     * Logs the first refusal and every [REJECTION_LOG_INTERVAL]th after it, so a sustained
     * overflow reports itself without flooding the log.
     */
    fun rejectsNewEntry(currentSize: Long): Boolean {
        val limit = maxEntries
        if (limit == UNLIMITED_LOCK_ENTRIES || currentSize < limit) return false

        val rejections = rejectionCount.incrementAndGet()
        if (rejections == 1L || rejections % REJECTION_LOG_INTERVAL == 0L) {
            log.warn(
                "Local lock map is at its cap of {} entries, so new keys are no longer collapsed " +
                    "({} refusals so far). Raise {} or shorten lockTimeoutMillis if this is unexpected.",
                limit,
                rejections,
                MAX_LOCK_ENTRIES_PROPERTY,
            )
        }
        return true
    }

    /**
     * Parses the configured form of the cap, returning null when there is nothing usable to apply
     * - either because it is absent or because it cannot be read as a non-negative number. Kept
     * separate from the [maxEntries] setter so that neither the class initializer nor a Spring
     * context refresh can be brought down by a malformed value.
     */
    internal fun parseMaxEntries(configured: String?): Long? {
        if (configured == null) return null

        val parsed = configured.trim().toLongOrNull()
        if (parsed == null || parsed < UNLIMITED_LOCK_ENTRIES) {
            log.warn(
                "Ignoring {}='{}': expected a non-negative number, leaving the current cap unchanged",
                MAX_LOCK_ENTRIES_PROPERTY,
                configured,
            )
            return null
        }

        return parsed
    }
}
