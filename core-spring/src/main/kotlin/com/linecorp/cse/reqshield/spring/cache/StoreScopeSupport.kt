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

package com.linecorp.cse.reqshield.spring.cache

/**
 * Implement alongside [ReqShieldCache] when the cache or the lock store of a request is chosen from the calling
 * thread (a `ThreadLocal` with the tenant, for example).
 *
 * Without it, a request can wait inside the JVM for a request of another store that works on the same key.
 */
interface StoreScopeSupport {
    /**
     * Names the store that the request of the calling thread uses: the same text for requests that use the same
     * store, and another text for another store. It runs at most once for a request and must not block.
     */
    fun storeScope(): String
}
