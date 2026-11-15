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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommonUtilsTest {
    @Test
    fun theWaitsOfTheReadingRequestShouldDoubleUpToThePollInterval() {
        assertEquals(listOf(5L, 10L, 20L, 40L, 50L, 50L), (0..5).map { representativePollMillis(it) })
    }

    @Test
    fun noWaitShouldBeLongerThanThePollIntervalForAnyIndex() {
        listOf(6, 31, 32, 62, 63, 64, 1_000, Int.MAX_VALUE).forEach {
            assertEquals(GET_CACHE_INTERVAL_MILLIS, representativePollMillis(it), "wait index $it")
        }
        assertTrue((0..100).all { representativePollMillis(it) in 1..GET_CACHE_INTERVAL_MILLIS })
    }

    @Test
    fun aNegativeWaitIndexShouldBeRejected() {
        assertThrows(IllegalArgumentException::class.java) { representativePollMillis(-1) }
    }
}
