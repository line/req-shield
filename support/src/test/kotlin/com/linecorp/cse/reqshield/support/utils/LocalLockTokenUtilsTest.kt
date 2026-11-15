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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalLockTokenUtilsTest {
    @Test
    fun tokensOfOneThreadShouldAllDiffer() {
        val tokens = List(1_000) { nextLocalLockToken() }

        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun tokensOfDifferentThreadsShouldAllDiffer() {
        val threads = 8
        val tokensPerThread = 5_000
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val tokens =
                List(threads) { pool.submit<List<String>> { List(tokensPerThread) { nextLocalLockToken() } } }
                    .flatMap { it.get(5, TimeUnit.SECONDS) }

            assertEquals(threads * tokensPerThread, tokens.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aThreadThatStartsAfterAnotherOneEndedShouldNotRepeatItsTokens() {
        // A later thread can get the id of a thread that ended, so the id of the thread must not name the tokens
        val tokens = HashSet<String>()
        repeat(50) {
            val thread = Thread { synchronized(tokens) { repeat(10) { tokens.add(nextLocalLockToken()) } } }
            thread.start()
            thread.join(5_000)
        }

        assertEquals(500, tokens.size)
        assertTrue(tokens.all { it.startsWith("local-") })
    }
}
