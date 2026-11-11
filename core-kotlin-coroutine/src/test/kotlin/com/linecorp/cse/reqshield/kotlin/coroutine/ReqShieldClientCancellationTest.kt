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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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

    private val logs = ListAppender<ILoggingEvent>()
    private val reqShieldLogger = LoggerFactory.getLogger(ReqShield::class.java) as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        reqShieldLogger.addAppender(logs)
    }

    /** Waits for a log line of [level] that contains [text]; background tasks log after the caller has returned. */
    private suspend fun awaitLog(
        level: Level,
        text: String,
    ): ILoggingEvent =
        withTimeout(5_000) {
            var found: ILoggingEvent? = null
            while (found == null) {
                // Copied first: the appender list is written by the background coroutine
                found = logs.list.toList().firstOrNull { it.level == level && it.formattedMessage.contains(text) }
                if (found == null) delay(5)
            }
            found
        }

    /** A lock whose calls time out inside themselves, the way a lock store call wrapped in `withTimeout` does. */
    private inner class TimingOutLock(
        private val tryLockTimesOut: Boolean = false,
        private val unLockTimesOut: Boolean = false,
    ) : KeyLock {
        val unLockCalls = AtomicInteger(0)

        override suspend fun tryLock(
            key: String,
            lockType: LockType,
        ): String? = if (tryLockTimesOut) timeOut() else keyLock.tryLock(key, lockType)

        override suspend fun unLock(
            key: String,
            lockType: LockType,
            token: String,
        ): Boolean {
            unLockCalls.incrementAndGet()
            return if (unLockTimesOut) timeOut() else keyLock.unLock(key, lockType, token)
        }

        private suspend fun <R> timeOut(): R =
            withTimeout(10) {
                delay(10_000)
                error("unreachable")
            }
    }

    private fun reqShield(
        getCacheFunction: suspend (String) -> ReqShieldData<String>? = { store[it] },
        maxAttemptGetCache: Int = 2,
        keyLock: KeyLock = this.keyLock,
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
        reqShieldLogger.detachAppender(logs)
    }

    private fun staleEntry(ttl: Long) =
        ReqShieldData(
            "old",
            ReqShieldData.Status.NEW,
            System.currentTimeMillis() - (ttl * 0.9).toLong(),
            ttl,
        )

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
    fun supplierTimeoutInTheRefreshShouldBeLoggedKeepTheEntryAndReleaseTheLock() =
        runBlocking {
            val ttl = 10_000L
            val stale = staleEntry(ttl)
            store[key] = stale
            val supplierCalls = AtomicInteger(0)

            val returned =
                reqShield().getAndSetReqShieldData(
                    key,
                    {
                        supplierCalls.incrementAndGet()
                        timingOutSupplier()
                    },
                    ttl,
                )

            assertEquals("old", returned.value)
            // Without the fix the refresh ended as a cancelled coroutine and left no log
            val logged = awaitLog(Level.ERROR, "failed to update the cache")
            assertTrue(logged.throwableProxy.className.endsWith("ClientException"), logged.throwableProxy.className)
            assertEquals(1, supplierCalls.get())
            assertLockIsFree(LockType.UPDATE)
            assertEquals(0, writes.get())
            assertEquals(stale, store[key])
        }

    @Test
    fun lockTimeoutBeforeTheRefreshShouldBeLoggedAndKeepTheCacheHit() =
        runBlocking {
            store[key] = staleEntry(10_000)
            val supplierCalls = AtomicInteger(0)

            val returned =
                reqShield(keyLock = TimingOutLock(tryLockTimesOut = true)).getAndSetReqShieldData(
                    key,
                    {
                        supplierCalls.incrementAndGet()
                        "new"
                    },
                    10_000,
                )

            // The caller is still active, so the timeout of the lock call only skips this refresh
            assertEquals("old", returned.value)
            awaitLog(Level.WARN, "failed to lock key")
            assertEquals(0, supplierCalls.get())
        }

    @Test
    fun unlockTimeoutAfterTheCacheWriteShouldBeLoggedAndKeepTheWrite() =
        runBlocking {
            val lock = TimingOutLock(unLockTimesOut = true)

            val returned = reqShield(keyLock = lock).getAndSetReqShieldData(key, { "value" }, 1_000)

            assertEquals("value", returned.value)
            // Without the fix the timeout of the unlock ended the write coroutine as cancelled, with no log
            awaitLog(Level.ERROR, "failed to unlock key")
            assertEquals("value", store[key]?.value)
            assertEquals(1, lock.unLockCalls.get())
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
