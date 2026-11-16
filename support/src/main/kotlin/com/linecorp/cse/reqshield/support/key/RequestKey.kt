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

package com.linecorp.cse.reqshield.support.key

/**
 * The key of one request, in two forms.
 *
 * [cache] is the key that the caller gave. The cache functions and the configured lock get it.
 *
 * [scoped] is the key of the locks and signals that live only in this JVM. It is [cache] with the store scope of
 * the request in front. Requests that use different stores (one store for each tenant, for example) then never wait
 * for each other inside the JVM. Without a store scope it is the same text as [cache].
 */
class RequestKey private constructor(
    val cache: String,
    val scoped: String,
) {
    companion object {
        /** The length of [storeScope] is written first, so no scope and key can give the text of another pair. */
        fun of(
            key: String,
            storeScope: String?,
        ): RequestKey = if (storeScope == null) RequestKey(key, key) else RequestKey(key, "${storeScope.length}:$storeScope:$key")
    }
}
