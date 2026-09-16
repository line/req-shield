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

package com.linecorp.cse.reqshield.support.exception

import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ClientExceptionTest {
    @Test
    fun `message defaults to errorCode message and errorCode is exposed`() {
        val exception = ClientException(errorCode = ErrorCode.SUPPLIER_ERROR)

        assertEquals(ErrorCode.SUPPLIER_ERROR, exception.errorCode)
        assertEquals(ErrorCode.SUPPLIER_ERROR.message, exception.message)
    }

    @Test
    fun `explicit message overrides errorCode message`() {
        val exception =
            ClientException(
                errorCode = ErrorCode.GET_CACHE_ERROR,
                message = "custom message",
            )

        assertEquals("custom message", exception.message)
    }

    @Test
    fun `cause is chained and originErrorMessage defaults to cause message`() {
        val cause = RuntimeException("original failure")
        val exception =
            ClientException(
                errorCode = ErrorCode.SET_CACHE_ERROR,
                cause = cause,
            )

        assertSame(cause, exception.cause)
        assertEquals("original failure", exception.originErrorMessage)
    }

    @Test
    fun `explicit originErrorMessage wins over cause message`() {
        val cause = RuntimeException("original failure")
        val exception =
            ClientException(
                errorCode = ErrorCode.SET_CACHE_ERROR,
                cause = cause,
                originErrorMessage = "overridden origin message",
            )

        assertSame(cause, exception.cause)
        assertEquals("overridden origin message", exception.originErrorMessage)
    }

    @Test
    fun `with neither cause nor originErrorMessage both are null`() {
        val exception = ClientException(errorCode = ErrorCode.DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION)

        assertNull(exception.cause)
        assertNull(exception.originErrorMessage)
    }

    @Test
    fun `all ErrorCode entries expose their expected code`() {
        val expectedCodes =
            mapOf(
                ErrorCode.SUPPLIER_ERROR to "1001",
                ErrorCode.GET_CACHE_ERROR to "1002",
                ErrorCode.SET_CACHE_ERROR to "1003",
                ErrorCode.DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION to "1004",
                ErrorCode.DOES_NOT_EXIST_GLOBAL_UNLOCK_FUNCTION to "1005",
            )

        expectedCodes.forEach { (errorCode, expectedCode) ->
            assertEquals(expectedCode, errorCode.code)
        }
    }
}
