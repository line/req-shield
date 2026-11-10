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

package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The background refresh runs in the configured scope, but with the context elements of the caller. */
class ReqShieldRefreshContextTest {
    private class CallerIdentity(
        val user: String,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<CallerIdentity>
    }

    private class RefreshContext(
        val user: String?,
        val coroutineName: String?,
        val parent: Job?,
        val thread: String,
    )

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO + CoroutineName("refresh-scope"))
    private val store = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val reqShield = reqShieldIn(scope)

    private fun reqShieldIn(scope: CoroutineScope) =
        ReqShield(
            ReqShieldConfiguration<String>(
                setCacheFunction = { key, data, _ ->
                    store[key] = data
                    true
                },
                getCacheFunction = { key -> store[key] },
                scope = scope,
            ),
        )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** An entry that has used 90% of its TTL, so the next read starts a background refresh. */
    private fun staleEntry(ttl: Long = 10_000) =
        ReqShieldData("old", ReqShieldData.Status.NEW, System.currentTimeMillis() - (ttl * 0.9).toLong(), ttl)

    @OptIn(ExperimentalCoroutinesApi::class) // Job.parent
    private suspend fun refreshContextOf(
        key: String,
        reqShield: ReqShield<String> = this.reqShield,
    ): RefreshContext {
        val observed = CompletableDeferred<RefreshContext>()
        store[key] = staleEntry()

        val returned =
            reqShield.getAndSetReqShieldData(
                key,
                {
                    val context = currentCoroutineContext()
                    observed.complete(
                        RefreshContext(
                            context[CallerIdentity]?.user,
                            context[CoroutineName]?.name,
                            context.job.parent,
                            Thread.currentThread().name,
                        ),
                    )
                    "new"
                },
                10_000,
            )
        assertEquals("old", returned.value)

        return withTimeout(5_000) { observed.await() }
    }

    @Test
    fun refreshSupplierShouldSeeTheContextElementsOfTheCaller() =
        runBlocking {
            val refresh = withContext(CallerIdentity("alice")) { refreshContextOf("context-${System.nanoTime()}") }

            assertEquals("alice", refresh.user)
        }

    @Test
    fun refreshShouldStayInTheConfiguredScope() =
        runBlocking {
            val refresh =
                withContext(CallerIdentity("alice") + CoroutineName("request")) {
                    refreshContextOf("scope-${System.nanoTime()}")
                }

            // A refresh that became a child of the request would be cancelled with it
            assertSame(scopeJob, refresh.parent)
            assertEquals("refresh-scope", refresh.coroutineName)
        }

    @Test
    fun refreshShouldNotRunOnTheDispatcherOfTheCallerWhenTheScopeHasNone() {
        val callerDispatcher = Executors.newSingleThreadExecutor { Thread(it, "caller-thread") }.asCoroutineDispatcher()
        val scopeWithoutDispatcher = CoroutineScope(SupervisorJob())

        try {
            val refresh =
                runBlocking(callerDispatcher) {
                    refreshContextOf("dispatcher-${System.nanoTime()}", reqShieldIn(scopeWithoutDispatcher))
                }

            // On the caller's dispatcher a slow supplier would hold up the request thread
            // startsWith: the coroutine debug mode appends the coroutine name to the thread name
            assertFalse(refresh.thread.startsWith("caller-thread"), refresh.thread)
        } finally {
            scopeWithoutDispatcher.cancel()
            callerDispatcher.close()
        }
    }
}
