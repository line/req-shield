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

/**
 * Wraps failures raised by client-provided functions (supplier, cache getter/setter, lock functions).
 *
 * The original exception is chained as [cause] so callers keep the full stack trace.
 * This class intentionally does not log: synchronous failures are propagated to the caller,
 * and fire-and-forget paths inside ReqShield log at the point where the error is dropped.
 */
class ClientException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
    cause: Throwable? = null,
    val originErrorMessage: String? = cause?.message,
) : RuntimeException(message, cause)
