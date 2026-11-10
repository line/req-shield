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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * A client function that throws a CancellationException while its caller is still active (a `withTimeout` inside it)
 * has failed; the caller was not cancelled. ReqShieldTest covers the cancellation of the caller itself.
 */
class ReqShieldClientCancellationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ConcurrentHashMap<String, ReqShieldData<String>>()
    private val writes = AtomicInteger(0)
    private val key = "client-cancellation-${System.nanoTime()}"
    private val keyLock = KeyLocalLock(60_000)

    private fun reqShield(
        getCacheFunction: suspend (String) -> ReqShieldData<String>? = { store[it] },
        maxAttemptGetCache: Int = 2,
    ) = ReqShield(
        ReqShieldConfiguration<String>(
            setCacheFunction = { cacheKey, data, _ ->
                writes.incrementAndGet()
                store[cacheKey] = data
                true
            },
            getCacheFunction = getCacheFunction,
            keyLock = keyLock,
            maxAttemptGetCache = maxAttemptGetCache,
            scope = scope,
        ),
    )

    private val timingOutSupplier: suspend () -> String? = {
        withTimeout(10) {
            delay(10_000)
            "late"
        }
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun assertLockIsFree(lockType: LockType) {
        val token =
            withTimeout(5_000) {
                var acquired = keyLock.tryLock(key, lockType)
                while (acquired == null) {
                    delay(5)
                    acquired = keyLock.tryLock(key, lockType)
                }
                acquired
            }
        assertTrue(keyLock.unLock(key, lockType, token))
    }

    @Test
    fun supplierTimeoutOnCreationShouldBeASupplierErrorAndReleaseTheLock() =
        runBlocking {
            val error = assertFailsWith<ClientException> { reqShield().getAndSetReqShieldData(key, timingOutSupplier, 1_000) }

            assertEquals(ErrorCode.SUPPLIER_ERROR, error.errorCode)
            assertTrue(error.cause is TimeoutCancellationException, error.cause.toString())
            assertEquals(0, writes.get())
            assertLockIsFree(LockType.CREATE)
        }

    @Test
    fun supplierTimeoutInTheWaiterFallbackShouldBeASupplierError() =
        runBlocking {
            // Another request holds the create lock and never fills the cache, so this request falls back to the supplier
            assertNotNull(keyLock.tryLock(key, LockType.CREATE))

            val error = assertFailsWith<ClientException> { reqShield().getAndSetReqShieldData(key, timingOutSupplier, 1_000) }

            assertEquals(ErrorCode.SUPPLIER_ERROR, error.errorCode)
        }

    @Test
    fun cacheReadTimeoutShouldBeAGetCacheError() =
        runBlocking {
            val shield =
                reqShield(getCacheFunction = {
                    withTimeout(10) {
                        delay(10_000)
                        null
                    }
                })

            val error = assertFailsWith<ClientException> { shield.getAndSetReqShieldData(key, { "value" }, 1_000) }

            assertEquals(ErrorCode.GET_CACHE_ERROR, error.errorCode)
        }

    @Test
    fun cacheReadTimeoutsWhileWaitingShouldEndInTheSupplierFallback() =
        runBlocking {
            assertNotNull(keyLock.tryLock(key, LockType.CREATE))
            val reads = AtomicInteger(0)
            // The first read is the plain miss; every poll after it times out, which counts as a cache-read failure
            val shield =
                reqShield(
                    getCacheFunction = {
                        if (reads.incrementAndGet() == 1) {
                            null
                        } else {
                            withTimeout(10) {
                                delay(10_000)
                                null
                            }
                        }
                    },
                    maxAttemptGetCache = 60,
                )

            val result = shield.getAndSetReqShieldData(key, { "from-supplier" }, 1_000)

            assertEquals("from-supplier", result.value)
            // 1 miss + MAX_CONSECUTIVE_GET_CACHE_FAILURES (3) failed polls
            assertEquals(4, reads.get())
        }

    @Test
    fun supplierTimeoutInTheRefreshShouldKeepTheEntryAndReleaseTheLock() =
        runBlocking {
            val ttl = 10_000L
            val stale = ReqShieldData("old", ReqShieldData.Status.NEW, System.currentTimeMillis() - (ttl * 0.9).toLong(), ttl)
            store[key] = stale

            val returned = reqShield().getAndSetReqShieldData(key, timingOutSupplier, ttl)

            assertEquals("old", returned.value)
            assertLockIsFree(LockType.UPDATE)
            assertEquals(0, writes.get())
            assertEquals(stale, store[key])
        }

    @Test
    fun cancellationOfTheCallerShouldStillPropagateUnwrapped() =
        runBlocking {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50) {
                    reqShield().getAndSetReqShieldData(key, {
                        delay(10_000)
                        "late"
                    }, 1_000)
                }
            }

            assertLockIsFree(LockType.CREATE)
        }

    @Test
    fun cancellationThrownByTheSupplierOfAnActiveCallerShouldBeASupplierError() =
        runBlocking {
            val error =
                assertFailsWith<ClientException> {
                    reqShield().getAndSetReqShieldData(key, { throw CancellationException("inner job cancelled") }, 1_000)
                }

            assertEquals(ErrorCode.SUPPLIER_ERROR, error.errorCode)
        }
}
