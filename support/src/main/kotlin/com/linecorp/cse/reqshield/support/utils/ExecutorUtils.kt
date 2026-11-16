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

import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.atomic.AtomicLong

/**
 * A pool for the background cache writes and refreshes, with 2 threads for each CPU and at least 2.
 *
 * It is a [ForkJoinPool] in FIFO mode. A request hands each cache write to the pool, and with a
 * ScheduledThreadPoolExecutor that hand-over cost more than all other work of a cache miss: all threads take their
 * tasks from one queue under one lock. A ForkJoinPool gives each worker its own queue.
 *
 * The threads are daemons, so a pending cache write never blocks the shutdown of the JVM. Their names start with
 * [threadNamePrefix]. Their context class loader is the one of the thread that calls this function: the tasks are
 * cache functions of the application, and the workers of the default factory would get the system class loader.
 */
fun newBackgroundExecutor(threadNamePrefix: String): ExecutorService {
    val threadCounter = AtomicLong(0)
    val contextClassLoader = Thread.currentThread().contextClassLoader
    val threadFactory =
        ForkJoinPool.ForkJoinWorkerThreadFactory { pool ->
            object : ForkJoinWorkerThread(pool) {}.apply {
                name = "$threadNamePrefix${threadCounter.incrementAndGet()}"
                isDaemon = true
                this.contextClassLoader = contextClassLoader
            }
        }
    // No handler for uncaught exceptions: req-shield logs the failure of each task itself. true: FIFO mode.
    return ForkJoinPool(maxOf(2, Runtime.getRuntime().availableProcessors() * 2), threadFactory, null, true)
}
