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

package com.linecorp.cse.reqshield.support.utils

import java.util.concurrent.atomic.AtomicLong

private val tokenStripes = AtomicLong(0)

/** The stripe of this thread and the count of its tokens. */
private val threadTokens = ThreadLocal.withInitial { longArrayOf(tokenStripes.incrementAndGet(), 0) }

/**
 * The next ownership token of a local lock. Tokens are compared only within this JVM, so a counter is enough, and
 * it is cheaper than a UUID.
 *
 * Each thread counts its own tokens. One counter for all threads made each lock attempt write a value that all
 * other threads read, which slowed the attempts of many threads down. A thread takes its stripe from a shared
 * counter once: a thread id can be given to a later thread, a stripe cannot, so a stripe and a count name one
 * token only.
 */
fun nextLocalLockToken(): String {
    val tokens = threadTokens.get()
    return "local-${tokens[0]}-${++tokens[1]}"
}
