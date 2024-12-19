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

package com.linecorp.cse.reqshield.support

interface BaseReqShieldTest {
    fun `test set method (Cache not exists And local lock acquired)`()

    fun `test set method (Cache not exists And global lock acquired)`()

    fun `test set method (Cache not exists And global lock acquired And Does not exist global lock function)`()

    fun `test set method (Cache not exists And local lock acquired And callable return null)`()

    fun `test set method (Cache not exists And global lock acquired And callable return null)`()

    fun `test set method (Cache not exists And local lock acquired And Throw callable ClientException)`()

    fun `test set method (Cache not exists And global lock acquired And Throw callable ClientException)`()

    fun `test set method (Cache not exists And local lock acquired And Throw get cache ClientException)`()

    fun `test set method (Cache not exists And global lock acquired And Throw get cache ClientException)`()

    fun `test set method (Cache not exists And local lock not acquired)`()

    fun `test set method (Cache not exists And global lock not acquired)`()

    fun `test set method (Cache exists, but not targeted for update)`()

    fun `test set method (Cache exists and the update target)`()

    fun `test set method (Cache exists and the update target and callable return null)`()

    fun `executeSetCacheFunction should handle exception from cacheSetter`()

    companion object {
        val AWAIT_TIMEOUT = 500L
    }
}
