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

import com.linecorp.cse.reqshield.support.constant.ConfigValues.UNLIMITED_LOCK_ENTRIES
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNull

class LocalLockLimitTest {
    @AfterEach
    fun restoreDefault() {
        // The limit is a process-wide setting, so a test that changes it must put it back.
        LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES
    }

    @Test
    fun `an uncapped map never refuses a new entry`() {
        LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES

        assertFalse(LocalLockLimit.rejectsNewEntry(0))
        assertFalse(LocalLockLimit.rejectsNewEntry(Long.MAX_VALUE))
    }

    @Test
    fun `a capped map refuses a new entry only once it is full`() {
        LocalLockLimit.maxEntries = 3

        assertFalse(LocalLockLimit.rejectsNewEntry(0), "an empty map has room")
        assertFalse(LocalLockLimit.rejectsNewEntry(2), "the last free slot is still a slot")
        assertTrue(LocalLockLimit.rejectsNewEntry(3), "a full map must refuse")
        assertTrue(LocalLockLimit.rejectsNewEntry(4), "an over-full map must keep refusing")
    }

    @Test
    fun `a cap of one collapses nothing beyond the first key`() {
        LocalLockLimit.maxEntries = 1

        assertFalse(LocalLockLimit.rejectsNewEntry(0))
        assertTrue(LocalLockLimit.rejectsNewEntry(1))
    }

    @Test
    fun `a negative cap is rejected rather than silently disabling the limit`() {
        val failure = assertThrows<IllegalArgumentException> { LocalLockLimit.maxEntries = -1 }

        assertTrue(failure.message!!.contains("must not be negative"), failure.message)
        assertEquals(UNLIMITED_LOCK_ENTRIES, LocalLockLimit.maxEntries, "the rejected value must not be applied")
    }

    @Test
    fun `an absent value yields nothing to apply`() {
        assertNull(LocalLockLimit.parseMaxEntries(null))
    }

    @Test
    fun `a configured value is read as a number, surrounding whitespace included`() {
        assertEquals(5_000L, LocalLockLimit.parseMaxEntries("5000"))
        assertEquals(5_000L, LocalLockLimit.parseMaxEntries("  5000  "))
        assertEquals(UNLIMITED_LOCK_ENTRIES, LocalLockLimit.parseMaxEntries("0"))
    }

    @Test
    fun `an unusable value yields nothing to apply instead of failing`() {
        assertNull(LocalLockLimit.parseMaxEntries("not-a-number"))
        assertNull(LocalLockLimit.parseMaxEntries("-1"), "a negative cap is configuration noise, not a cap of zero")
        assertNull(LocalLockLimit.parseMaxEntries(""))
    }

    @Test
    fun `applying a configured value leaves the current cap alone unless the value is usable`() {
        LocalLockLimit.maxEntries = 42

        LocalLockLimit.applyConfiguredValue(null)
        assertEquals(42L, LocalLockLimit.maxEntries, "an absent value must not reset a cap set elsewhere")

        LocalLockLimit.applyConfiguredValue("not-a-number")
        assertEquals(42L, LocalLockLimit.maxEntries, "an unusable value must not reset a cap set elsewhere")

        LocalLockLimit.applyConfiguredValue("-7")
        assertEquals(42L, LocalLockLimit.maxEntries, "a negative value must not reset a cap set elsewhere")

        LocalLockLimit.applyConfiguredValue("7")
        assertEquals(7L, LocalLockLimit.maxEntries, "a usable value must be applied")

        LocalLockLimit.applyConfiguredValue("0")
        assertEquals(UNLIMITED_LOCK_ENTRIES, LocalLockLimit.maxEntries, "an explicit zero must uncap the map")
    }
}
