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

package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.spring.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.support.redis.AbstractRedisTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.UUID

@SpringBootTest
@ExtendWith(SpringExtension::class)
class GlobalLockTest : AbstractRedisTest() {
    @Autowired
    private lateinit var globalLockSupport: GlobalLockSupport

    @Test
    fun `lock is exclusive and can only be released by the token that acquired it`() {
        val lockKey = "globalLockTest-${UUID.randomUUID()}"

        assertTrue(globalLockSupport.globalLock(lockKey, "ownerToken", 5000))
        // already held
        assertFalse(globalLockSupport.globalLock(lockKey, "otherToken", 5000))
        // compare-and-delete: a foreign token must not release someone else's lock
        assertFalse(globalLockSupport.globalUnLock(lockKey, "otherToken"))
        assertFalse(globalLockSupport.globalLock(lockKey, "otherToken", 5000))

        assertTrue(globalLockSupport.globalUnLock(lockKey, "ownerToken"))
        // released, so it can be acquired again
        assertTrue(globalLockSupport.globalLock(lockKey, "otherToken", 5000))
        assertTrue(globalLockSupport.globalUnLock(lockKey, "otherToken"))
    }
}
