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

package com.linecorp.cse.reqshield.reactor.config

import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono

class ReqShieldConfigurationTest {
    @Test
    fun testGlobalLockWithoutLockFunctionAndWithoutExplicitKeyLock() {
        val exception =
            assertThrows<IllegalArgumentException> {
                ReqShieldConfiguration<String>(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    getCacheFunction = { Mono.empty() },
                    isLocalLock = false,
                )
            }

        assertEquals(ErrorCode.DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION.message, exception.message)
    }

    @Test
    fun testGlobalLockWithoutUnLockFunctionAndWithoutExplicitKeyLock() {
        val exception =
            assertThrows<IllegalArgumentException> {
                ReqShieldConfiguration<String>(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    getCacheFunction = { Mono.empty() },
                    globalLockFunction = { _, _, _ -> Mono.just(true) },
                    isLocalLock = false,
                )
            }

        assertEquals(ErrorCode.DOES_NOT_EXIST_GLOBAL_UNLOCK_FUNCTION.message, exception.message)
    }
}
