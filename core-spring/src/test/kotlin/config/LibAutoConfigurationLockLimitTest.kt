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

package config

import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_LOCK_ENTRIES_PROPERTY
import com.linecorp.cse.reqshield.support.constant.ConfigValues.UNLIMITED_LOCK_ENTRIES
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * The lock map is static, so [LibAutoConfiguration] is what carries a configured cap onto it.
 * These cover the binding itself; that Spring can build the class at all is already proven by the
 * integration tests, which register it in a real context.
 */
class LibAutoConfigurationLockLimitTest {
    @AfterEach
    fun restoreDefault() {
        LocalLockLimit.maxEntries = UNLIMITED_LOCK_ENTRIES
    }

    private fun environmentWith(vararg properties: Pair<String, Any>): Environment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
        }

    @Test
    fun `binds the lock entry cap from the environment`() {
        LibAutoConfiguration(environmentWith(MAX_LOCK_ENTRIES_PROPERTY to "5000"))

        assertEquals(5_000L, LocalLockLimit.maxEntries)
    }

    @Test
    fun `leaves the cap alone when the property is absent`() {
        LocalLockLimit.maxEntries = 42

        LibAutoConfiguration(StandardEnvironment())

        assertEquals(42L, LocalLockLimit.maxEntries, "an absent property must not reset a cap set elsewhere")
    }

    @Test
    fun `an explicit zero leaves the map uncapped`() {
        LocalLockLimit.maxEntries = 42

        LibAutoConfiguration(environmentWith(MAX_LOCK_ENTRIES_PROPERTY to "0"))

        assertEquals(UNLIMITED_LOCK_ENTRIES, LocalLockLimit.maxEntries)
    }

    @Test
    fun `a yml integer is bound just like a quoted string`() {
        // application.yml hands `max-entries: 5000` over as an Integer, not a String.
        LibAutoConfiguration(environmentWith(MAX_LOCK_ENTRIES_PROPERTY to 5000))

        assertEquals(5_000L, LocalLockLimit.maxEntries)
    }

    @Test
    fun `an unusable cap is ignored instead of bringing the context down`() {
        LocalLockLimit.maxEntries = 42

        LibAutoConfiguration(environmentWith(MAX_LOCK_ENTRIES_PROPERTY to "not-a-number"))
        assertEquals(42L, LocalLockLimit.maxEntries, "a malformed value must leave the cap alone")

        LibAutoConfiguration(environmentWith(MAX_LOCK_ENTRIES_PROPERTY to "-1"))
        assertEquals(42L, LocalLockLimit.maxEntries, "a negative value must leave the cap alone")
    }
}
