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

package com.linecorp.cse.reqshield.support.exception.code

enum class ErrorCode(
    val code: String,
    val message: String
) {
    SUPPLIER_ERROR("1001", "An error occurred in the supplier function provided by client."),
    GET_CACHE_ERROR("1002", "An error occurred in the get cache function provided by client."),
    SET_CACHE_ERROR("1003", "An error occurred in the set cache function provided by client."),
    DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION("1004", "If isLocalLock is false, globalLockFunction must be implemented."),
    DOES_NOT_EXIST_GLOBAL_UNLOCK_FUNCTION("1005", "If isLocalLock is false, globalUnLockFunction must be implemented."),
}