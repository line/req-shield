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
import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_ATTEMPT_GET_CACHE
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val LOCAL_TOKEN = "local-token"

@OptIn(ExperimentalCoroutinesApi::class)
class ReqShieldTest : BaseReqShieldTest {
    private lateinit var reqShield: ReqShield<Product>
    private lateinit var reqShieldOnlyUpdateCache: ReqShield<Product>
    private lateinit var reqShieldOnlyCreateCache: ReqShield<Product>
    private lateinit var reqShieldForGlobalLock: ReqShield<Product>
    private lateinit var reqShieldForGlobalLockForError: ReqShield<Product>
    private lateinit var cacheSetter: suspend (String, ReqShieldData<Product>, Long) -> Boolean
    private lateinit var cacheGetter: suspend (String) -> ReqShieldData<Product>?
    private lateinit var keyLock: KeyLock
    private lateinit var keyGlobalLock: KeyLock

    /** Runs the fire-and-forget cache writes, so tests can await them instead of sleeping. */
    private lateinit var backgroundScope: CoroutineScope
    private val key = "testKey"
    private val createLockKey = lockKeyOf(key, LockType.CREATE)
    private val updateLockKey = lockKeyOf(key, LockType.UPDATE)
    private val oldValue = Product("oldTestValue", "oldTestName")
    private val value = Product("testValue", "testName")
    private val callable: suspend () -> Product? = mockk()

    private var timeToLiveMillis: Long = 10000

    private lateinit var globalLockFunc: suspend (String, String, Long) -> Boolean
    private lateinit var globalUnLockFunc: suspend (String, String) -> Boolean

    @BeforeEach
    fun setup() {
        cacheSetter = mockk<suspend (String, ReqShieldData<Product>, Long) -> Boolean>()
        cacheGetter = mockk<suspend (String) -> ReqShieldData<Product>?>()
        globalLockFunc = mockk<suspend (String, String, Long) -> Boolean>()
        globalUnLockFunc = mockk<suspend (String, String) -> Boolean>()
        keyLock = mockk<KeyLock>()

        keyGlobalLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, 3000)
        backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        coEvery { callable() } returns value

        reqShield = reqShieldOf()
        reqShieldOnlyUpdateCache = reqShieldOf(workMode = ReqShieldWorkMode.ONLY_UPDATE_CACHE)
        reqShieldOnlyCreateCache = reqShieldOf(workMode = ReqShieldWorkMode.ONLY_CREATE_CACHE)

        reqShieldForGlobalLock =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    globalLockFunc,
                    globalUnLockFunc,
                    isLocalLock = false,
                    keyLock = keyGlobalLock,
                    scope = backgroundScope,
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        backgroundScope.cancel()
    }

    private fun reqShieldOf(
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        maxAttemptGetCache: Int = MAX_ATTEMPT_GET_CACHE,
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = cacheSetter,
                getCacheFunction = cacheGetter,
                keyLock = keyLock,
                maxAttemptGetCache = maxAttemptGetCache,
                reqShieldWorkMode = workMode,
                scope = backgroundScope,
            ),
        )

    /** Awaits every cache write already submitted to [backgroundScope]. */
    private suspend fun awaitBackgroundWrites() {
        backgroundScope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    private fun cachedData(
        cachedValue: Product?,
        ttl: Long,
        createdAt: Long = nowToEpochTime(),
    ): ReqShieldData<Product> = ReqShieldData(cachedValue, ReqShieldData.Status.NEW, createdAt, ttl)

    /** A cache entry that has consumed 90% of its TTL, i.e. past the default 80% update threshold. */
    private fun updateTargetData(
        cachedValue: Product?,
        ttl: Long,
    ): ReqShieldData<Product> = cachedData(cachedValue, ttl, createdAt = nowToEpochTime() - (ttl * 0.9).toLong())

    @Test
    fun shouldReuseCacheWhenAnEarlierMissResumesAfterAnotherRequestFinishes() =
        runTest {
            val cached = AtomicReference<ReqShieldData<Product>?>()
            var reads = 0
            var calls = 0
            val missObserved = CompletableDeferred<Unit>()
            val resumeMiss = CompletableDeferred<Unit>()
            val isolatedKey = "delayed-miss-${java.util.UUID.randomUUID()}"
            val shield =
                ReqShield(
                    ReqShieldConfiguration(
                        setCacheFunction = { _, data, _ ->
                            cached.set(data)
                            true
                        },
                        getCacheFunction = {
                            if (++reads == 1) {
                                // Resume this stale miss only after the winner has written the cache and unlocked.
                                missObserved.complete(Unit)
                                resumeMiss.await()
                                null
                            } else {
                                cached.get()
                            }
                        },
                        scope = this@ReqShieldTest.backgroundScope,
                    ),
                )
            val supplier: suspend () -> Product? = {
                calls++
                value
            }
            val delayed = async { shield.getAndSetReqShieldData(isolatedKey, supplier, timeToLiveMillis) }

            missObserved.await()
            val winner = shield.getAndSetReqShieldData(isolatedKey, supplier, timeToLiveMillis)
            awaitBackgroundWrites()
            assertSame(winner, cached.get())
            resumeMiss.complete(Unit)
            val result = delayed.await()
            awaitBackgroundWrites()
            assertEquals(1, calls)
            assertSame(winner, result)
        }

    @Test
    fun shouldReuseNullValuedCacheAfterAcquiringLocalLock() =
        runTest {
            val cached = cachedData(null, timeToLiveMillis)
            coEvery { cacheGetter(key) } returnsMany listOf(null, cached)
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true

            assertSame(cached, reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))

            coVerify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            coVerify(exactly = 0) { callable() }
            coVerify(exactly = 0) { cacheSetter(any(), any(), any()) }
        }

    @Test
    fun shouldReuseCacheAfterAcquiringGlobalLockAndReleaseItsToken() =
        runTest {
            val cached = cachedData(value, timeToLiveMillis)
            coEvery { cacheGetter(key) } returnsMany listOf(null, cached)
            coEvery { globalLockFunc(any(), any(), any()) } returns true
            coEvery { globalUnLockFunc(any(), any()) } returns true

            assertSame(cached, reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))

            val owner = slot<String>()
            coVerify(exactly = 1) { globalLockFunc(createLockKey, capture(owner), 3000) }
            coVerify(exactly = 1) { globalUnLockFunc(createLockKey, owner.captured) }
            coVerify(exactly = 0) { callable() }
            coVerify(exactly = 0) { cacheSetter(any(), any(), any()) }
        }

    @Test
    fun shouldReleaseLockWhenCacheRecheckFails() =
        runTest {
            val failure = IllegalStateException("cache recheck failed")
            coEvery { cacheGetter(key) } returns null andThenThrows failure
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true

            val error = assertFailsWith<ClientException> { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

            assertEquals(ErrorCode.GET_CACHE_ERROR, error.errorCode)
            assertSame(failure, error.cause)
            coVerify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            coVerify(exactly = 0) { callable() }
        }

    @Test
    fun shouldReleaseGlobalLockWhenCacheRecheckIsCancelled() =
        runTest {
            val recheckStarted = CompletableDeferred<Unit>()
            var reads = 0
            var unlockCompleted = false
            coEvery { cacheGetter(key) } coAnswers {
                if (++reads == 1) {
                    null
                } else {
                    recheckStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            coEvery { globalLockFunc(any(), any(), any()) } returns true
            coEvery { globalUnLockFunc(any(), any()) } coAnswers {
                // Cleanup must be able to suspend even when the caller has been cancelled.
                delay(1)
                unlockCompleted = true
                true
            }
            val caller = launch { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

            recheckStarted.await()
            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            assertTrue(unlockCompleted)
            val owner = slot<String>()
            coVerify(exactly = 1) { globalLockFunc(createLockKey, capture(owner), 3000) }
            coVerify(exactly = 1) { globalUnLockFunc(createLockKey, owner.captured) }
            coVerify(exactly = 0) { callable() }
            coVerify(exactly = 0) { cacheSetter(any(), any(), any()) }
        }

    @Test
    fun shouldKeepLockUntilAsyncCacheWriteCompletesAfterRecheckMiss() =
        runTest {
            val writeStarted = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            coEvery { cacheGetter(key) } returns null
            coEvery { cacheSetter(key, any(), any()) } coAnswers {
                writeStarted.complete(Unit)
                finishWrite.await()
                true
            }
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true

            assertEquals(value, reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis).value)
            writeStarted.await()
            coVerify(exactly = 2) { cacheGetter(key) }
            coVerify(exactly = 0) { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            finishWrite.complete(Unit)
            awaitBackgroundWrites()
            coVerify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            coVerify(exactly = 1) { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquired() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertNotNull(result)
            assertEquals(value, result.value)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndOnlyUpdateCache() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            val result = reqShieldOnlyUpdateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquired() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(createLockKey, any(), any()) } returns true
            coEvery { globalUnLockFunc(createLockKey, any()) } returns true

            val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }

            // The very token handed out by the lock function must be the one released.
            val tokenSlot = slot<String>()
            coVerify { globalLockFunc(createLockKey, capture(tokenSlot), 3000) }
            coVerify { globalUnLockFunc(createLockKey, tokenSlot.captured) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndDoesNotExistGlobalLockFunction() {
        val result =
            assertThrows<IllegalArgumentException> {
                reqShieldForGlobalLockForError =
                    ReqShield(
                        ReqShieldConfiguration(
                            cacheSetter,
                            cacheGetter,
                            isLocalLock = false,
                            keyLock = keyGlobalLock,
                        ),
                    )
            }.message

        kotlin.test.assertEquals(result, ErrorCode.DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION.message)
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndCallableReturnNull() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true
            coEvery { callable() } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertNotNull(result)
            assertNull(result.value)

            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndCallableReturnNull() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(createLockKey, any(), any()) } returns true
            coEvery { globalUnLockFunc(createLockKey, any()) } returns true

            coEvery { callable() } returns null

            val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertNotNull(result)
            assertNull(result.value)

            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }

            val tokenSlot = slot<String>()
            coVerify { globalLockFunc(createLockKey, capture(tokenSlot), 3000) }
            coVerify { globalUnLockFunc(createLockKey, tokenSlot.captured) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowCallableClientException() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true
            coEvery { callable() } throws Exception("callable error")

            val result = runCatching { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }
            awaitBackgroundWrites()

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as? ClientException
            assertNotNull(exception)
            assertEquals(ErrorCode.SUPPLIER_ERROR, exception!!.errorCode)
            assertEquals("callable error", exception.cause?.message)

            coVerify { cacheGetter.invoke(key) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
            coVerify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowCallableClientException() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(createLockKey, any(), any()) } returns true
            coEvery { globalUnLockFunc(createLockKey, any()) } returns true

            coEvery { callable() } throws Exception("callable error")

            val result = runCatching { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }
            awaitBackgroundWrites()

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as? ClientException
            assertNotNull(exception)
            assertEquals(ErrorCode.SUPPLIER_ERROR, exception!!.errorCode)
            assertEquals("callable error", exception.cause?.message)

            coVerify { cacheGetter.invoke(key) }

            val tokenSlot = slot<String>()
            coVerify { globalLockFunc(createLockKey, capture(tokenSlot), 3000) }
            coVerify { globalUnLockFunc(createLockKey, tokenSlot.captured) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowGetCacheClientException() =
        runTest {
            coEvery { cacheGetter.invoke(key) } throws Exception("get cache error")
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true

            val result = runCatching { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as? ClientException
            assertNotNull(exception)
            assertEquals(ErrorCode.GET_CACHE_ERROR, exception!!.errorCode)
            assertEquals("get cache error", exception.cause?.message)

            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
            coVerify(inverse = true) { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowGetCacheClientException() =
        runTest {
            coEvery { cacheGetter.invoke(key) } throws Exception("get cache error")
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(any(), any(), any()) } returns true
            coEvery { globalUnLockFunc(any(), any()) } returns true

            val result = runCatching { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as? ClientException
            assertNotNull(exception)
            assertEquals(ErrorCode.GET_CACHE_ERROR, exception!!.errorCode)

            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { globalLockFunc(any(), any(), any()) }
            coVerify(inverse = true) { globalUnLockFunc(any(), any()) }
            coVerify(inverse = true) { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockNotAcquired() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            // The cache was never filled by the lock owner, so the supplier is the last resort.
            assertNotNull(result)
            assertEquals(value, result.value)
            coVerify(exactly = 1 + MAX_ATTEMPT_GET_CACHE) { cacheGetter.invoke(key) }
            coVerify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockNotAcquired() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { globalLockFunc(createLockKey, any(), any()) } returns false

            val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            assertNotNull(result)
            assertEquals(value, result.value)
            coVerify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            coVerify { globalLockFunc(createLockKey, any(), any()) }
            coVerify(inverse = true) { globalUnLockFunc(any(), any()) }
        }

    @Test
    override fun testSetMethodCacheExistsButNotTargetedForUpdate() =
        runTest {
            val timeToLiveMillis: Long = 10000
            // Freshly created entry: far from the 80% update threshold.
            val reqShieldData = cachedData(value, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            coVerify(inverse = true) { callable() }
        }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTarget() =
        runTest {
            val timeToLiveMillis: Long = 1000
            val reqShieldData = updateTargetData(oldValue, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.UPDATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.UPDATE, LOCAL_TOKEN) } returns true

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            // The stale entry is served while the refresh happens in the background.
            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }

            val dataSlot = slot<ReqShieldData<Product>>()
            coVerify { cacheSetter.invoke(key, capture(dataSlot), timeToLiveMillis) }
            assertEquals(value, dataSlot.captured.value)
            assertEquals(timeToLiveMillis, dataSlot.captured.timeToLiveMillis)

            coVerify { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify { keyLock.unLock(key, LockType.UPDATE, LOCAL_TOKEN) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetOnlyCreateCache() =
        runTest {
            val timeToLiveMillis: Long = 1000
            val reqShieldData = updateTargetData(oldValue, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            val result = reqShieldOnlyCreateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }

            val dataSlot = slot<ReqShieldData<Product>>()
            coVerify { cacheSetter.invoke(key, capture(dataSlot), timeToLiveMillis) }
            assertEquals(value, dataSlot.captured.value)

            coVerify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.UPDATE, any()) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetAndCallableReturnNull() =
        runTest {
            timeToLiveMillis = 1000
            val reqShieldData = updateTargetData(value, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.UPDATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.UPDATE, LOCAL_TOKEN) } returns true
            coEvery { callable() } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }

            val dataSlot = slot<ReqShieldData<Product>>()
            coVerify { cacheSetter.invoke(key, capture(dataSlot), timeToLiveMillis) }
            assertNull(dataSlot.captured.value)

            coVerify { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify { keyLock.unLock(key, LockType.UPDATE, LOCAL_TOKEN) }
            coVerify { callable() }
        }

    @Test
    override fun executeSetCacheFunctionShouldHandleExceptionFromCacheSetter() =
        runBlocking {
            coEvery { keyLock.unLock(any(), any(), any()) } returns true

            val key = "key"
            val reqShieldData = cachedData(value, 1000L)
            val lockType = LockType.CREATE

            val method: Method =
                ReqShield::class.java.declaredMethods.firstOrNull { it.name == "executeSetCacheFunction" }
                    ?: throw NoSuchMethodException("Method executeSetCacheFunction not found")
            method.isAccessible = true

            val continuation =
                object : Continuation<Unit> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<Unit>) {
                        result.getOrThrow()
                    }
                }
            coEvery { cacheSetter.invoke(any(), any(), any()) } throws Exception("set cache error")
            val exception =
                assertFailsWith<InvocationTargetException> {
                    method.invoke(reqShield, cacheSetter, key, reqShieldData, lockType, LOCAL_TOKEN, continuation)
                }

            val cause = exception.cause
            assertTrue(cause is ClientException)
            assertEquals(ErrorCode.SET_CACHE_ERROR, cause.errorCode)
            assertEquals("set cache error", cause.cause?.message)

            coVerify { cacheSetter.invoke(key, reqShieldData, 1000L) }
            coVerify { keyLock.unLock(key, lockType, LOCAL_TOKEN) }
        }

    @Test
    fun `should not unlock when no lock was acquired and the cache setter fails`() =
        runBlocking {
            val key = "key"
            val reqShieldData = cachedData(value, 1000L)

            val method: Method =
                ReqShield::class.java.declaredMethods.first { it.name == "executeSetCacheFunction" }
            method.isAccessible = true

            val continuation =
                object : Continuation<Unit> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<Unit>) {
                        result.getOrThrow()
                    }
                }
            coEvery { cacheSetter.invoke(any(), any(), any()) } throws Exception("set cache error")

            assertFailsWith<InvocationTargetException> {
                method.invoke(reqShield, cacheSetter, key, reqShieldData, LockType.CREATE, null, continuation)
            }

            // A null token means this call never held the lock, so it must not release anyone else's.
            coVerify(inverse = true) { keyLock.unLock(any(), any(), any()) }
        }

    @Test
    fun `should return the entry another request cached while waiting for the lock`() =
        runTest {
            val cached = cachedData(value, timeToLiveMillis)
            val reads = AtomicInteger(0)

            coEvery { cacheGetter.invoke(key) } coAnswers {
                if (reads.incrementAndGet() >= 3) cached else null
            }
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            assertEquals(cached, result)
            // 1 read for the initial miss + 2 polls until the lock owner filled the cache
            assertEquals(3, reads.get())
            coVerify(inverse = true) { callable() }
            coVerify(inverse = true) { cacheSetter.invoke(any(), any(), any()) }
        }

    @Test
    fun `should fall back to the supplier after consecutive cache read failures`() =
        runTest {
            val reads = AtomicInteger(0)

            coEvery { cacheGetter.invoke(key) } coAnswers {
                if (reads.incrementAndGet() == 1) null else throw Exception("cache is down")
            }
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            assertEquals(value, result.value)
            // The wait gives up as soon as the failures become consecutive, long before maxAttemptGetCache
            assertEquals(1 + MAX_CONSECUTIVE_GET_CACHE_FAILURES, reads.get())
            coVerify(exactly = 1) { callable() }
            coVerify(inverse = true) { cacheSetter.invoke(any(), any(), any()) }
        }

    @Test
    fun `should keep waiting when cache read failures are not consecutive`() =
        runTest {
            val maxAttemptGetCache = 6
            val reqShieldWithShortWait = reqShieldOf(maxAttemptGetCache = maxAttemptGetCache)
            val reads = AtomicInteger(0)

            // Alternate failure / miss so the failure streak never reaches the threshold.
            coEvery { cacheGetter.invoke(key) } coAnswers {
                val read = reads.incrementAndGet()
                if (read > 1 && read % 2 == 0) throw Exception("cache is flaky") else null
            }
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns null

            val result = reqShieldWithShortWait.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            // Every attempt was used: an intermittent failure must not end the wait early
            assertEquals(1 + maxAttemptGetCache, reads.get())
            assertEquals(value, result.value)
            coVerify(exactly = 1) { callable() }
        }

    @Test
    fun `should raise a supplier error when the fallback supplier fails`() =
        runTest {
            val reqShieldWithShortWait = reqShieldOf(maxAttemptGetCache = 2)

            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns null
            coEvery { callable() } throws IllegalStateException("supplier is down")

            val exception =
                assertFailsWith<ClientException> {
                    reqShieldWithShortWait.getAndSetReqShieldData(key, callable, timeToLiveMillis)
                }

            assertEquals(ErrorCode.SUPPLIER_ERROR, exception.errorCode)
            assertNotNull(exception.cause)
            assertEquals("supplier is down", exception.cause?.message)
            // No lock was taken while waiting, so there is nothing to release.
            coVerify(inverse = true) { keyLock.unLock(any(), any(), any()) }
        }

    @Test
    fun `should not fail the caller when the background cache write fails`() =
        runTest {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } throws Exception("set cache error")
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) } returns true

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            // The write is fire-and-forget: its failure is logged, never handed to the caller,
            // and the lock is released anyway.
            assertEquals(value, result.value)
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { keyLock.unLock(key, LockType.CREATE, LOCAL_TOKEN) }
        }

    @Test
    fun `should release the lock when the background cache update fails`() =
        runTest {
            val timeToLiveMillis: Long = 1000
            val reqShieldData = updateTargetData(oldValue, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { keyLock.tryLock(key, LockType.UPDATE) } returns LOCAL_TOKEN
            coEvery { keyLock.unLock(key, LockType.UPDATE, LOCAL_TOKEN) } returns true
            coEvery { callable() } throws Exception("callable error")

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            awaitBackgroundWrites()

            // The stale entry keeps being served, and the failing supplier still releases the lock.
            assertEquals(reqShieldData, result)
            coVerify { keyLock.unLock(key, LockType.UPDATE, LOCAL_TOKEN) }
            coVerify(inverse = true) { cacheSetter.invoke(any(), any(), any()) }
        }

    @Test
    fun `should stop polling the cache when the caller is cancelled`() =
        runBlocking {
            val reads = AtomicInteger(0)

            coEvery { cacheGetter.invoke(key) } coAnswers {
                reads.incrementAndGet()
                null
            }
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns null

            val waiter =
                launch(Dispatchers.Default) {
                    reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
                }

            withTimeout(2000L) {
                while (reads.get() < 3) {
                    delay(10L)
                }
            }
            waiter.cancelAndJoin()

            val readsAtCancellation = reads.get()
            delay(GET_CACHE_INTERVAL_MILLIS * 5)

            assertEquals(readsAtCancellation, reads.get(), "Polling must stop once the caller is cancelled")
            coVerify(inverse = true) { callable() }
        }

    @Test
    fun shouldReleaseLocalCreateLockWhenSupplierTimesOut() =
        runTest {
            val localLock = KeyLocalLock(60_000)
            val uniqueKey = "supplier-timeout-${System.nanoTime()}"
            val shield =
                ReqShield(
                    ReqShieldConfiguration(
                        setCacheFunction = cacheSetter,
                        getCacheFunction = { null },
                        keyLock = localLock,
                        scope = backgroundScope,
                    ),
                )

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    shield.getAndSetReqShieldData(uniqueKey, { awaitCancellation() }, timeToLiveMillis)
                }
            }

            val token = localLock.tryLock(uniqueKey, LockType.CREATE)
            assertNotNull(token, "Cancellation must release the lock before its TTL")
            assertTrue(localLock.unLock(uniqueKey, LockType.CREATE, token!!))
            coVerify(exactly = 0) { cacheSetter.invoke(any(), any(), any()) }
        }

    @Test
    fun shouldReleaseGlobalCreateLockWhenSupplierIsCancelled() =
        verifyGlobalLockReleasedOnCancellation(LockType.CREATE, cancelSetter = false)

    @Test
    fun shouldReleaseGlobalUpdateLockWhenSupplierIsCancelled() =
        verifyGlobalLockReleasedOnCancellation(LockType.UPDATE, cancelSetter = false)

    @Test
    fun shouldReleaseGlobalLockWhenCacheSetterIsCancelled() = verifyGlobalLockReleasedOnCancellation(LockType.CREATE, cancelSetter = true)

    @Test
    fun shouldReleaseGlobalCreateLockWhenScopeIsCancelledBeforeWriteStarts() =
        verifyGlobalLockReleasedBeforeBackgroundTaskStarts(LockType.CREATE)

    @Test
    fun shouldReleaseGlobalUpdateLockWhenScopeIsCancelledBeforeSupplierStarts() =
        verifyGlobalLockReleasedBeforeBackgroundTaskStarts(LockType.UPDATE)

    private fun verifyGlobalLockReleasedBeforeBackgroundTaskStarts(lockType: LockType) =
        runTest {
            val locks = mutableMapOf<String, String>()
            var releases = 0
            val globalLock =
                KeyGlobalLock(
                    globalLockFunction = { lockKey, token, _ -> locks.putIfAbsent(lockKey, token) == null },
                    globalUnLockFunction = { lockKey, token ->
                        // Suspending cleanup must finish even after cancellation.
                        delay(1)
                        releases++
                        locks.remove(lockKey, token)
                    },
                    lockTimeoutMillis = 60_000,
                )
            val taskScope = CoroutineScope(coroutineContext + SupervisorJob())
            var supplierCalls = 0
            var writes = 0
            val shield =
                ReqShield(
                    ReqShieldConfiguration<Product>(
                        setCacheFunction = { _, _, _ ->
                            writes++
                            true
                        },
                        getCacheFunction = {
                            if (lockType == LockType.UPDATE) updateTargetData(oldValue, timeToLiveMillis) else null
                        },
                        keyLock = globalLock,
                        scope = taskScope,
                    ),
                )

            try {
                val result =
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierCalls++
                            value
                        },
                        timeToLiveMillis,
                    )
                val expectedSupplierCalls = if (lockType == LockType.CREATE) 1 else 0
                assertEquals(if (lockType == LockType.CREATE) value else oldValue, result.value)
                assertEquals(expectedSupplierCalls, supplierCalls)
                assertEquals(0, writes, "The background task must still be queued")
                assertNull(globalLock.tryLock(key, lockType), "The queued task already owns the lock")

                val owner = taskScope.coroutineContext.job.children.single()
                val cancellation = CancellationException("scope stopped before dispatch")
                var propagated: Throwable? = null
                owner.invokeOnCompletion { propagated = it }
                // Cancel before yielding to the test dispatcher, so the task has not started.
                taskScope.cancel(cancellation)
                owner.join()

                assertSame(cancellation, propagated)
                assertEquals(expectedSupplierCalls, supplierCalls, "Cancellation must not start the supplier")
                assertEquals(0, writes, "Cancellation must not start the cache write")
                assertEquals(1, releases, "The owned token must be released exactly once")
                val token = globalLock.tryLock(key, lockType)
                assertNotNull(token, "Pre-start cancellation must release the lock before its TTL")
                assertTrue(globalLock.unLock(key, lockType, token!!))
            } finally {
                taskScope.cancel()
            }
        }

    private fun verifyGlobalLockReleasedOnCancellation(
        lockType: LockType,
        cancelSetter: Boolean,
    ) = runTest {
        val locks = mutableMapOf<String, String>()
        val globalLock =
            KeyGlobalLock(
                globalLockFunction = { lockKey, token, _ -> locks.putIfAbsent(lockKey, token) == null },
                globalUnLockFunction = { lockKey, token ->
                    // A suspending unlock must finish even after its caller was cancelled.
                    delay(1)
                    locks.remove(lockKey, token)
                },
                lockTimeoutMillis = 60_000,
            )
        val started = CompletableDeferred<Unit>()
        val cancellation = CancellationException("request cancelled")
        var propagated: Throwable? = null
        val taskScope = CoroutineScope(coroutineContext + SupervisorJob())
        val shield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ ->
                        check(cancelSetter) { "A cancelled supplier must not write the cache" }
                        started.complete(Unit)
                        awaitCancellation()
                    },
                    getCacheFunction = {
                        if (lockType == LockType.UPDATE) updateTargetData(oldValue, timeToLiveMillis) else null
                    },
                    keyLock = globalLock,
                    scope = taskScope,
                ),
            )

        try {
            val caller =
                taskScope.launch {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            if (!cancelSetter) {
                                started.complete(Unit)
                                awaitCancellation()
                            }
                            value
                        },
                        timeToLiveMillis,
                    )
                }
            started.await()
            val owner = if (lockType == LockType.CREATE && !cancelSetter) caller else taskScope.coroutineContext.job.children.single()
            owner.invokeOnCompletion { propagated = it }
            assertNull(globalLock.tryLock(key, lockType), "The supplier or setter must hold the lock")

            owner.cancel(cancellation)
            owner.join()

            assertSame(cancellation, propagated)
            val token = globalLock.tryLock(key, lockType)
            assertNotNull(token, "Suspending cleanup must release the owned lock")
            assertTrue(globalLock.unLock(key, lockType, token!!))
        } finally {
            taskScope.cancel()
        }
    }
}
