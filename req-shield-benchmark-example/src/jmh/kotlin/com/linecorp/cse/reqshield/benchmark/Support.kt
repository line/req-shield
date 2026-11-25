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

package com.linecorp.cse.reqshield.benchmark

import com.linecorp.cse.reqshield.support.model.ReqShieldData
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

const val TTL_MILLIS = 10 * 60 * 1000L

private val threadIds = AtomicInteger()

/** Gives each benchmark thread its own number, so that threads never share a key. */
fun nextThreadId(): Int = threadIds.incrementAndGet()

/**
 * Builds a pool the same way as the library default (ReqShieldConfiguration.sharedExecutor in core).
 * The benchmark owns the pool, so it can read the queue depth and wait for the queue to drain.
 */
fun libraryLikeExecutor(): ScheduledThreadPoolExecutor =
    Executors.newScheduledThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() * 2)) { runnable ->
        Thread(runnable, "bench-req-shield-executor").apply { isDaemon = true }
    } as ScheduledThreadPoolExecutor

/** Waits until the pool has no queued task and no running task. Returns the wait in milliseconds. */
fun awaitQuiescence(executor: ThreadPoolExecutor): Long {
    val start = System.nanoTime()
    var idleChecks = 0
    // A worker that took a task but is not yet counted as active is a short window, so require two idle checks.
    while (idleChecks < 2) {
        if (executor.queue.isEmpty() && executor.activeCount == 0) idleChecks++ else idleChecks = 0
        Thread.sleep(1)
    }
    return (System.nanoTime() - start) / 1_000_000
}

/** The arm without req-shield: read the cache, and on a miss call the supplier and write the cache. */
class CacheAside<T>(
    private val getCache: (String) -> ReqShieldData<T>?,
    private val setCache: (String, ReqShieldData<T>, Long) -> Boolean,
) {
    fun get(
        key: String,
        supplier: Callable<T?>,
        timeToLiveMillis: Long,
    ): ReqShieldData<T> {
        getCache(key)?.let { return it }
        val data = ReqShieldData(supplier.call(), timeToLiveMillis)
        setCache(key, data, timeToLiveMillis)
        return data
    }
}

/** Appends one line of harness-level facts (backlog, supplier calls, latency percentiles) to the events file. */
object Events {
    private val file = File(System.getProperty("bench.events", "build/jmh-events.jsonl"))

    @Synchronized
    fun write(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }
}

fun percentile(
    sorted: LongArray,
    p: Double,
): Long = if (sorted.isEmpty()) -1 else sorted[minOf(sorted.size - 1, Math.ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
