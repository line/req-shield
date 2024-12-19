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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache

import com.linecorp.cse.reqshield.support.model.ReqShieldData

interface AsyncCache<T> {
    suspend fun get(key: String): ReqShieldData<T>?

    suspend fun put(
        key: String,
        value: ReqShieldData<T>,
        timeToLiveMillis: Long,
    ): Boolean

    suspend fun evict(key: String): Boolean

    /**
     * Attempt a global lock on a specific key.
     *
     * @param key The key to get the lock.
     * @param timeToLiveMillis The validity of the lock in milliseconds.
     * @return Whether the lock was successfully obtained. Returns `true` by default.
     *
     * This method provides a default implementation, but if you need a locking mechanism
     * You must implement and use your own locking logic. The default implementation is true, and if the value of ReqShieldConfiguration > isLocalLock is false, you will use the function you implemented.
     * actual production environments should override this method appropriately to manage locks.
     */
    suspend fun globalLock(
        key: String,
        timeToLiveMillis: Long,
    ): Boolean = true

    /**
     * Releases the global lock on a specific key.
     *
     * @param key The key you want to unlock.
     * @return Whether the lock was successfully obtained. Returns `true` by default.
     *
     * This method provides a default implementation, but if you need a locking mechanism
     * You must implement and use your own unlocking logic. The default implementation is true, and if the value of ReqShieldConfiguration > isLocalLock is false, you will use the function you implemented.
     * actual production environments should override this method appropriately to manage locks.
     */
    suspend fun globalUnLock(key: String): Boolean = true
}
