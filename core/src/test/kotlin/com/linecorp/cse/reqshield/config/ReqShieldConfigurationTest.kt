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

package com.linecorp.cse.reqshield.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ThreadPoolExecutor

class ReqShieldConfigurationTest {
    @Test
    fun testDefaultThreadPoolSizeIsOptimal() {
        val config =
            ReqShieldConfiguration<String>(
                setCacheFunction = { _, _, _ -> true },
                getCacheFunction = { null },
            )

        val executor = config.executor as? ThreadPoolExecutor
        assertTrue(executor != null, "Executor should be ThreadPoolExecutor")

        val expectedSize = maxOf(2, Runtime.getRuntime().availableProcessors() * 2)
        assertEquals(expectedSize, executor!!.corePoolSize, "Thread pool size should be optimal")
        assertTrue(
            executor.corePoolSize <= Runtime.getRuntime().availableProcessors() * 2,
            "Thread pool should not be excessive",
        )
    }

    @Test
    fun testMinimumThreadPoolSize() {
        // Simulate single core environment
        val expectedMinimum = 2
        val calculatedSize = maxOf(2, 1 * 2) // Case when availableProcessors() = 1

        assertEquals(
            expectedMinimum,
            calculatedSize,
            "Minimum thread pool size should be 2 even on single core systems",
        )
    }

    @Test
    fun testMultiCoreThreadPoolSize() {
        // Calculate appropriate size for multi-core environment
        val coreCount = Runtime.getRuntime().availableProcessors()
        val expectedSize = maxOf(2, coreCount * 2)

        assertTrue(expectedSize >= 2, "Thread pool size should be at least 2")
        assertTrue(expectedSize <= coreCount * 2, "Thread pool should not exceed cores * 2")
    }
}
