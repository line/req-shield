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

package com.linecorp.cse.reqshield.support.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReqShieldDataTest {
    @Test
    fun `secondary constructor sets status NEW and ttl with non-null value`() {
        val before = System.currentTimeMillis()
        val data = ReqShieldData(value = "cached-value", timeToLiveMillis = 5000L)
        val after = System.currentTimeMillis()

        assertEquals(ReqShieldData.Status.NEW, data.status)
        assertEquals(5000L, data.timeToLiveMillis)
        assertEquals("cached-value", data.value)
        assertTrue(data.createdAt in (before - 1000)..(after + 1000))
    }

    @Test
    fun `secondary constructor allows null value`() {
        val data = ReqShieldData<String>(timeToLiveMillis = 1000L)

        assertNull(data.value)
        assertEquals(ReqShieldData.Status.NEW, data.status)
    }

    @Test
    fun `primary constructor keeps explicit status and createdAt`() {
        val data =
            ReqShieldData(
                value = "value",
                status = ReqShieldData.Status.NORMAL,
                createdAt = 12345L,
                timeToLiveMillis = 999L,
            )

        assertEquals(ReqShieldData.Status.NORMAL, data.status)
        assertEquals(12345L, data.createdAt)
        assertEquals(999L, data.timeToLiveMillis)
    }

    @Test
    fun `data class equality holds for identical fields`() {
        val first = ReqShieldData(value = "same", status = ReqShieldData.Status.CREATING, createdAt = 1L, timeToLiveMillis = 2L)
        val second = ReqShieldData(value = "same", status = ReqShieldData.Status.CREATING, createdAt = 1L, timeToLiveMillis = 2L)

        assertEquals(first, second)
    }

    @Test
    fun `data class equality fails when value differs`() {
        val first = ReqShieldData(value = "one", status = ReqShieldData.Status.CREATING, createdAt = 1L, timeToLiveMillis = 2L)
        val second = ReqShieldData(value = "two", status = ReqShieldData.Status.CREATING, createdAt = 1L, timeToLiveMillis = 2L)

        assertFalse(first == second)
    }
}
