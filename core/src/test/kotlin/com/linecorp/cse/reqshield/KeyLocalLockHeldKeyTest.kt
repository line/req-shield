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

package com.linecorp.cse.reqshield

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An attempt on a key that another request holds fails without a lock on the map. These tests check that this
 * short path rejects only a lock that is still valid.
 */
class KeyLocalLockHeldKeyTest {
    // The lock map is shared by all instances, so each test has its own key
    private val key = "held-key-${UUID.randomUUID()}"

    @Test
    fun attemptsOnAHeldKeyShouldFailAndLeaveTheHolderAbleToUnlock() {
        val lock = KeyLocalLock(3000)
        val token = checkNotNull(lock.tryLock(key, LockType.CREATE))
        val pool = Executors.newFixedThreadPool(4)

        try {
            val attempts = List(4) { pool.submit<Int> { (1..1000).count { lock.tryLock(key, LockType.CREATE) != null } } }

            assertEquals(0, attempts.sumOf { it.get(5, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        // The other lock type of the same key is a lock of its own
        val updateToken = lock.tryLock(key, LockType.UPDATE)
        assertNotNull(updateToken)
        assertTrue(lock.unLock(key, LockType.UPDATE, updateToken!!))

        assertTrue(lock.unLock(key, LockType.CREATE, token))
        // The key is free again. The new lock is given back, so no entry stays in the shared map.
        val nextToken = lock.tryLock(key, LockType.CREATE)
        assertNotNull(nextToken)
        assertTrue(lock.unLock(key, LockType.CREATE, nextToken!!))
    }

    @Test
    fun anExpiredLockShouldPassToTheNextRequestAndRejectTheOldToken() {
        // Far below the 1 s interval of the lock monitor, so the monitor does not remove the entry first
        val lock = KeyLocalLock(300)
        val oldToken = checkNotNull(lock.tryLock(key, LockType.CREATE))
        assertNull(lock.tryLock(key, LockType.CREATE))

        Thread.sleep(400)
        val newToken = lock.tryLock(key, LockType.CREATE)

        assertNotNull(newToken)
        assertFalse(lock.unLock(key, LockType.CREATE, oldToken))
        assertNull(lock.tryLock(key, LockType.CREATE))
        assertTrue(lock.unLock(key, LockType.CREATE, newToken!!))
    }
}
