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

package com.linecorp.cse.reqshield.utils.support

import com.linecorp.cse.reqshield.support.utils.decideToUpdateCache
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommonUtilsTest {

    @Test
    fun decideToUpdateCacheTest() {
        val expireTime = Duration.ofMinutes(1).toMillis()
        val createdAt = LocalDateTime.now().atZone(ZoneId.systemDefault()).minusSeconds(55)

        val decide = decideToUpdateCache(createdAt.toInstant().toEpochMilli(), expireTime, 80)
        assertTrue(decide)
    }

    @Test
    fun decideToUpdateCacheTestFalse() {
        val expireTime = Duration.ofMinutes(1).toMillis()
        val createdAt = LocalDateTime.now().atZone(ZoneId.systemDefault()).minusSeconds(10)

        val decide = decideToUpdateCache(createdAt.toInstant().toEpochMilli(), expireTime, 80)
        assertFalse(decide)
    }
}