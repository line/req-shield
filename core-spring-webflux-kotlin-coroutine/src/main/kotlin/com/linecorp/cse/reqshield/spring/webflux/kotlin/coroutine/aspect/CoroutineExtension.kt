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

@file:Suppress("UNCHECKED_CAST")

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.aspect

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.aspectj.lang.ProceedingJoinPoint
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * AOP Coroutine Extension Fun
 */
val ProceedingJoinPoint.coroutineContinuation: Continuation<Any?>
    get() = this.args.last() as Continuation<Any?>

val ProceedingJoinPoint.coroutineArgs: Array<Any?>
    get() = this.args.sliceArray(0 until this.args.size - 1)

suspend fun ProceedingJoinPoint.proceedCoroutine(args: Array<Any?> = this.coroutineArgs): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        this.proceed(args + continuation)
    }

fun ProceedingJoinPoint.runCoroutine(block: suspend () -> Any?): Any? =
    block.startCoroutineUninterceptedOrReturn(this.coroutineContinuation)

/**
 * Bounded dispatcher for non-suspend join point execution to prevent IO dispatcher exhaustion.
 * Limits concurrent blocking calls to prevent thread pool saturation under heavy load.
 *
 * Parallelism can be configured via system property:
 * - `reqshield.blocking.parallelism`: explicit parallelism value (1-1024)
 * - Default: availableProcessors * 2, clamped to [4, 256]
 *
 * Examples:
 * - `-Dreqshield.blocking.parallelism=64` for high-throughput environments
 * - `-Dreqshield.blocking.parallelism=8` for resource-constrained environments
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val boundedBlockingDispatcher: CoroutineDispatcher by lazy {
    val defaultParallelism =
        (Runtime.getRuntime().availableProcessors() * 2)
            .coerceIn(4, 256) // Min 4, max 256

    val parallelism =
        System.getProperty("reqshield.blocking.parallelism")
            ?.toIntOrNull()
            ?.coerceIn(1, 1024) // Configured value also bounded
            ?: defaultParallelism

    Dispatchers.IO.limitedParallelism(parallelism)
}

/**
 * Proceed supporting both suspend and non-suspend join points.
 * If the last argument is a Continuation, treat as suspend; otherwise proceed normally.
 * Uses a bounded dispatcher for non-suspend calls to prevent IO thread pool exhaustion.
 */
suspend fun ProceedingJoinPoint.proceedSmart(): Any? =
    if (this.args.isNotEmpty() && this.args.last() is Continuation<*>) {
        this.proceedCoroutine()
    } else {
        // Use bounded dispatcher to prevent IO thread pool exhaustion
        // when many synchronous methods are proxied concurrently
        withContext(boundedBlockingDispatcher) { this@proceedSmart.proceed() }
    }
