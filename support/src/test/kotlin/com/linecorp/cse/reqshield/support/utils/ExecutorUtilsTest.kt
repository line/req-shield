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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutorUtilsTest {
    @Test
    fun workersShouldBeNamedDaemonThreadsWithTheContextClassLoaderOfTheCreator() {
        val creator = Thread.currentThread()
        val loaderOfTheCreator = creator.contextClassLoader
        val applicationLoader = URLClassLoader(arrayOf(), loaderOfTheCreator)
        creator.contextClassLoader = applicationLoader
        val executor =
            try {
                newBackgroundExecutor("test-executor-")
            } finally {
                creator.contextClassLoader = loaderOfTheCreator
            }

        try {
            val worker = executor.submit(Callable { Thread.currentThread() }).get(5, TimeUnit.SECONDS)

            assertTrue(worker.name.startsWith("test-executor-"), "the worker is named ${worker.name}")
            assertTrue(worker.isDaemon)
            assertSame(applicationLoader, worker.contextClassLoader)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun thePoolShouldHaveTwoThreadsForEachCpuAndRunItsTasksInFifoMode() {
        val executor = newBackgroundExecutor("test-executor-") as ForkJoinPool

        try {
            assertEquals(maxOf(2, Runtime.getRuntime().availableProcessors() * 2), executor.parallelism)
            assertTrue(executor.asyncMode)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun eachTaskThatIsHandedOverShouldRun() {
        val executor = newBackgroundExecutor("test-executor-")
        val tasks = 10_000
        val ran = AtomicInteger()
        val done = CountDownLatch(tasks)

        try {
            repeat(tasks) {
                executor.execute {
                    ran.incrementAndGet()
                    done.countDown()
                }
            }

            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(tasks, ran.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
