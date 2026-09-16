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

package com.linecorp.cse.reqshield

import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.BaseReqShieldTest.Companion.AWAIT_TIMEOUT
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GET_CACHE_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import com.linecorp.cse.reqshield.support.constant.ConfigValues.MAX_CONSECUTIVE_GET_CACHE_FAILURES
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReqShieldTest : BaseReqShieldTest {
    private lateinit var reqShield: ReqShield<Product>
    private lateinit var reqShieldOnlyUpdateCache: ReqShield<Product>
    private lateinit var reqShieldOnlyCreateCache: ReqShield<Product>
    private lateinit var reqShieldForGlobalLock: ReqShield<Product>
    private lateinit var reqShieldForGlobalLockForError: ReqShield<Product>
    private lateinit var cacheSetter: (String, ReqShieldData<Product>, Long) -> Boolean
    private lateinit var cacheGetter: (String) -> ReqShieldData<Product>?
    private lateinit var keyLock: KeyLock
    private lateinit var keyGlobalLock: KeyLock
    private val key = "testKey"
    private val oldValue = Product("oldTestValue", "oldTestName")
    private val value = Product("testId", "testName")
    private val callable: Callable<Product?> = mockk()
    private val createToken = "create-token"
    private val updateToken = "update-token"

    private var timeToLiveMillis: Long = 10000

    private lateinit var globalLockFunc: (String, String, Long) -> Boolean
    private lateinit var globalUnLockFunc: (String, String) -> Boolean

    @BeforeEach
    fun setup() {
        cacheSetter = mockk<(String, ReqShieldData<Product>, Long) -> Boolean>()
        cacheGetter = mockk<(String) -> ReqShieldData<Product>?>()
        globalLockFunc = mockk<(String, String, Long) -> Boolean>()
        globalUnLockFunc = mockk<(String, String) -> Boolean>()
        keyLock = mockk<KeyLock>()

        keyGlobalLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, 3000)

        every { callable.call() } returns value

        reqShield =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    keyLock = keyLock,
                ),
            )

        reqShieldOnlyUpdateCache =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    keyLock = keyLock,
                    reqShieldWorkMode = ReqShieldWorkMode.ONLY_UPDATE_CACHE,
                ),
            )

        reqShieldOnlyCreateCache =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    keyLock = keyLock,
                    reqShieldWorkMode = ReqShieldWorkMode.ONLY_CREATE_CACHE,
                ),
            )

        reqShieldForGlobalLock =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    globalLockFunc,
                    globalUnLockFunc,
                    isLocalLock = false,
                    keyLock = keyGlobalLock,
                ),
            )
    }

    /**
     * Builds a cache entry that is old enough to be an update target:
     * 90% of its TTL has already passed while decisionForUpdate defaults to 80%.
     */
    private fun updateTargetData(cachedValue: Product?): ReqShieldData<Product> =
        ReqShieldData(
            value = cachedValue,
            status = ReqShieldData.Status.NEW,
            createdAt = System.currentTimeMillis() - (timeToLiveMillis * 0.9).toLong(),
            timeToLiveMillis = timeToLiveMillis,
        )

    /** Builds a freshly created cache entry, which must not be an update target. */
    private fun freshData(cachedValue: Product?): ReqShieldData<Product> =
        ReqShieldData(
            value = cachedValue,
            status = ReqShieldData.Status.NEW,
            createdAt = System.currentTimeMillis(),
            timeToLiveMillis = timeToLiveMillis,
        )

    @Test
    fun shouldReuseCacheWhenAnEarlierMissResumesAfterAnotherRequestFinishes() {
        val cached = AtomicReference<ReqShieldData<Product>?>()
        val reads = AtomicInteger()
        val calls = AtomicInteger()
        val missObserved = CountDownLatch(1)
        val resumeMiss = CountDownLatch(1)
        val callerExecutor = Executors.newSingleThreadExecutor()
        val cacheExecutor = Executors.newSingleThreadScheduledExecutor()
        val isolatedKey = "delayed-miss-${java.util.UUID.randomUUID()}"
        val shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { _, data, _ ->
                        cached.set(data)
                        true
                    },
                    getCacheFunction = {
                        if (reads.incrementAndGet() == 1) {
                            // Resume this stale miss only after the winner has written the cache and unlocked.
                            missObserved.countDown()
                            check(resumeMiss.await(2, TimeUnit.SECONDS))
                            null
                        } else {
                            cached.get()
                        }
                    },
                    executor = cacheExecutor,
                ),
            )
        val supplier =
            Callable {
                calls.incrementAndGet()
                value
            }

        try {
            val delayed =
                callerExecutor.submit<ReqShieldData<Product>> {
                    shield.getAndSetReqShieldData(isolatedKey, supplier, timeToLiveMillis)
                }
            assertTrue(missObserved.await(2, TimeUnit.SECONDS))
            val winner = shield.getAndSetReqShieldData(isolatedKey, supplier, timeToLiveMillis)
            // A barrier on the single-thread executor also waits for the cache write's unlock.
            cacheExecutor.submit {}.get(2, TimeUnit.SECONDS)
            assertSame(winner, cached.get())
            resumeMiss.countDown()
            val result = delayed.get(2, TimeUnit.SECONDS)
            assertEquals(1, calls.get())
            assertSame(winner, result)
        } finally {
            resumeMiss.countDown()
            callerExecutor.shutdownNow()
            cacheExecutor.shutdownNow()
            assertTrue(callerExecutor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(cacheExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun shouldReuseNullValuedCacheAfterAcquiringLocalLock() {
        val cached = freshData(null)
        every { cacheGetter(key) } returnsMany listOf(null, cached)
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true

        assertSame(cached, reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))

        verify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, createToken) }
        verify(exactly = 0) { callable.call() }
        verify(exactly = 0) { cacheSetter(any(), any(), any()) }
    }

    @Test
    fun shouldReuseCacheAfterAcquiringGlobalLockAndReleaseItsToken() {
        val cached = freshData(value)
        every { cacheGetter(key) } returnsMany listOf(null, cached)
        every { globalLockFunc(any(), any(), any()) } returns true
        every { globalUnLockFunc(any(), any()) } returns true

        assertSame(cached, reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))

        val owner = slot<String>()
        val lockKey = "$LOCK_KEY_PREFIX${key}_${LockType.CREATE.name}"
        verify(exactly = 1) { globalLockFunc(lockKey, capture(owner), 3000) }
        verify(exactly = 1) { globalUnLockFunc(lockKey, owner.captured) }
        verify(exactly = 0) { callable.call() }
        verify(exactly = 0) { cacheSetter(any(), any(), any()) }
    }

    @Test
    fun shouldReleaseLockWhenCacheRecheckFails() {
        val failure = IllegalStateException("cache recheck failed")
        every { cacheGetter(key) } returns null andThenThrows failure
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true

        val error = assertThrows<ClientException> { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

        assertEquals(ErrorCode.GET_CACHE_ERROR, error.errorCode)
        assertSame(failure, error.cause)
        verify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, createToken) }
        verify(exactly = 0) { callable.call() }
    }

    @Test
    fun shouldKeepLockUntilAsyncCacheWriteCompletesAfterRecheckMiss() {
        val writeStarted = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val cacheExecutor = Executors.newSingleThreadScheduledExecutor()
        every { cacheGetter(key) } returns null
        every { cacheSetter(key, any(), any()) } answers {
            writeStarted.countDown()
            check(finishWrite.await(2, TimeUnit.SECONDS))
            true
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true
        val shield = ReqShield(ReqShieldConfiguration(cacheSetter, cacheGetter, keyLock = keyLock, executor = cacheExecutor))

        try {
            assertEquals(value, shield.getAndSetReqShieldData(key, callable, timeToLiveMillis).value)
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            verify(exactly = 2) { cacheGetter(key) }
            verify(exactly = 0) { keyLock.unLock(key, LockType.CREATE, createToken) }
            finishWrite.countDown()
            cacheExecutor.submit {}.get(2, TimeUnit.SECONDS)
            verify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, createToken) }
            verify(exactly = 1) { callable.call() }
        } finally {
            finishWrite.countDown()
            cacheExecutor.shutdownNow()
            assertTrue(cacheExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquired() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { keyLock.unLock(key, LockType.CREATE, createToken) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndOnlyUpdateCache() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        val result = reqShieldOnlyUpdateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquired() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any(), any()) } returns true
        every { globalUnLockFunc(any(), any()) } returns true

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { globalLockFunc(any(), any(), any()) }
            verify { globalUnLockFunc(any(), any()) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndDoesNotExistGlobalLockFunction() {
        val result =
            assertThrows<IllegalArgumentException> {
                reqShieldForGlobalLockForError =
                    ReqShield<Product>(
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
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndCallableReturnNull() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true
        every { callable.call() } returns null

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            assertNull(result.value)

            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { keyLock.unLock(key, LockType.CREATE, createToken) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndCallableReturnNull() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any(), any()) } returns true
        every { globalUnLockFunc(any(), any()) } returns true

        every { callable.call() } returns null

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            assertNull(result.value)

            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { globalLockFunc(any(), any(), any()) }
            verify { globalUnLockFunc(any(), any()) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowCallableClientException() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true
        every { callable.call() } throws Exception("callable error")

        val exception =
            assertThrows<ClientException> { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.SUPPLIER_ERROR, exception.errorCode)
            assertEquals("callable error", exception.cause?.message)
            verify { cacheGetter.invoke(key) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { keyLock.unLock(key, LockType.CREATE, createToken) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowCallableClientException() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any(), any()) } returns true
        every { globalUnLockFunc(any(), any()) } returns true

        every { callable.call() } throws Exception("callable error")

        val exceptionCode =
            assertThrows<ClientException> { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }.errorCode

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.SUPPLIER_ERROR, exceptionCode)
            verify { cacheGetter.invoke(key) }
            verify { globalLockFunc(any(), any(), any()) }
            verify { globalUnLockFunc(any(), any()) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowGetCacheClientException() {
        every { cacheGetter.invoke(key) } throws Exception("get cache error")
        every { cacheSetter.invoke(key, any(), any()) } returns true

        val exception =
            assertThrows<ClientException> { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.GET_CACHE_ERROR, exception.errorCode)
            assertEquals("get cache error", exception.cause?.message)
            verify { cacheGetter.invoke(key) }
            verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
            verify(inverse = true) { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowGetCacheClientException() {
        every { cacheGetter.invoke(key) } throws Exception("get cache error")
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any(), any()) } returns true
        every { globalUnLockFunc(any(), any()) } returns true

        val exceptionCode =
            assertThrows<ClientException> { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }.errorCode

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.GET_CACHE_ERROR, exceptionCode)
            verify { cacheGetter.invoke(key) }
            verify(inverse = true) { globalLockFunc(any(), any(), any()) }
            verify(inverse = true) { globalUnLockFunc(any(), any()) }
            verify(inverse = true) { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockNotAcquired() {
        every { cacheGetter.invoke(key) } returns null
        every { keyLock.tryLock(key, LockType.CREATE) } returns null

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            assertEquals(value, result.value)
            verify { cacheGetter.invoke(key) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockNotAcquired() {
        every { cacheGetter.invoke(key) } returns null
        every { globalLockFunc(any(), any(), any()) } returns false

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            assertEquals(value, result.value)
            verify { cacheGetter.invoke(key) }
            verify { globalLockFunc(any(), any(), any()) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheExistsButNotTargetedForUpdate() {
        val reqShieldData = freshData(value)

        every { cacheGetter.invoke(key) } returns reqShieldData

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            // A fresh cache entry is not an update target, so no lock is even attempted
            verify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
            verify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            verify(inverse = true) { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTarget() {
        timeToLiveMillis = 1000
        val reqShieldData = updateTargetData(oldValue)

        every { cacheGetter.invoke(key) } returns reqShieldData
        every { cacheSetter.invoke(key, any(), any()) } answers { true }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns updateToken
        every { keyLock.unLock(key, LockType.UPDATE, updateToken) } returns true

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, match { it.value == value }, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.UPDATE) }
            verify { keyLock.unLock(key, LockType.UPDATE, updateToken) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetOnlyCreateCache() {
        timeToLiveMillis = 1000
        val reqShieldData = updateTargetData(oldValue)

        every { cacheGetter.invoke(key) } returns reqShieldData
        every { cacheSetter.invoke(key, any(), any()) } answers { true }

        val result = reqShieldOnlyCreateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, match { it.value == value }, timeToLiveMillis) }
            // ONLY_CREATE_CACHE collapses on creation only, so the update takes no lock
            verify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
            verify(inverse = true) { keyLock.unLock(key, LockType.UPDATE, any()) }
            verify { callable.call() }
        }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetAndCallableReturnNull() {
        timeToLiveMillis = 1000
        val reqShieldData = updateTargetData(value)

        every { cacheGetter.invoke(key) } returns reqShieldData
        every { cacheSetter.invoke(key, any(), any()) } answers { true }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns updateToken
        every { keyLock.unLock(key, LockType.UPDATE, updateToken) } returns true
        every { callable.call() } returns null

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, match { it.value == null }, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.UPDATE) }
            verify { keyLock.unLock(key, LockType.UPDATE, updateToken) }
            verify { callable.call() }
        }
    }

    @Test
    override fun executeSetCacheFunctionShouldHandleExceptionFromCacheSetter() {
        every { keyLock.unLock(any(), any(), any()) } returns true

        val key = "key"
        val reqShieldData = ReqShieldData(value, 1000L)
        val lockType = LockType.CREATE

        val method: Method =
            ReqShield::class.java.declaredMethods.firstOrNull { it.name == "executeSetCacheFunction" }
                ?: throw NoSuchMethodException("Method executeSetCacheFunction not found")

        method.isAccessible = true

        every { cacheSetter.invoke(any(), any(), any()) } throws Exception("set cache error")

        val exception =
            assertFailsWith<InvocationTargetException> {
                method.invoke(reqShield, cacheSetter, key, reqShieldData, lockType, createToken)
            }

        val cause = exception.cause
        assertTrue(cause is ClientException)
        assertEquals(ErrorCode.SET_CACHE_ERROR, (cause as ClientException).errorCode)

        verify { cacheSetter.invoke(key, reqShieldData, 1000L) }
        verify { keyLock.unLock(key, lockType, createToken) }
    }

    @Test
    fun `should not unlock when no lock was taken and the supplier fails`() {
        every { cacheGetter.invoke(key) } returns null
        every { callable.call() } throws Exception("callable error")

        val exception =
            assertThrows<ClientException> {
                reqShieldOnlyUpdateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            }

        assertEquals(ErrorCode.SUPPLIER_ERROR, exception.errorCode)
        // ONLY_UPDATE_CACHE takes no CREATE lock, so nothing may be released on failure
        verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
    }

    @Test
    fun `should release the lock and keep serving the caller when the asynchronous cache write fails`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } throws Exception("set cache error")
        every { keyLock.tryLock(key, LockType.CREATE) } returns createToken
        every { keyLock.unLock(key, LockType.CREATE, createToken) } returns true

        // The cache write is fire-and-forget, so its failure must not reach the caller
        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        assertEquals(value, result.value)
        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            // The lock must still be released even though the write failed
            verify { keyLock.unLock(key, LockType.CREATE, createToken) }
        }
    }

    @Test
    fun `should restore the interrupt flag and report a get cache error when the waiting thread is interrupted`() {
        every { cacheGetter.invoke(key) } returns null
        every { keyLock.tryLock(key, LockType.CREATE) } returns null

        var thrown: Throwable? = null
        var interruptFlagRestored = false
        val waiter =
            Thread {
                try {
                    reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
                } catch (e: Throwable) {
                    thrown = e
                    interruptFlagRestored = Thread.currentThread().isInterrupted
                }
            }

        waiter.start()
        Thread.sleep(100) // let the waiter enter the cache polling wait
        waiter.interrupt()
        waiter.join(2000)

        val exception = thrown
        assertTrue(exception is ClientException, "Expected a ClientException but was $exception")
        assertEquals(ErrorCode.GET_CACHE_ERROR, (exception as ClientException).errorCode)
        assertTrue(interruptFlagRestored, "The interrupt flag must be restored on the caller thread")
        verify(exactly = 0) { callable.call() }
    }

    @Test
    fun `should return the data another request wrote into the cache without calling the supplier`() {
        val cachedData = freshData(value)
        var getCount = 0
        // 1st read: the cache miss that leads into the lock-wait, the 3rd poll finds the data
        every { cacheGetter.invoke(key) } answers {
            getCount++
            if (getCount >= 4) cachedData else null
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns null

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        assertEquals(cachedData, result)
        verify(exactly = 0) { callable.call() }
    }

    @Test
    fun `should fall back to the supplier once when cache reads keep failing`() {
        var getCount = 0
        every { cacheGetter.invoke(key) } answers {
            getCount++
            if (getCount == 1) null else throw RuntimeException("cache connection error")
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns null

        val startTime = System.currentTimeMillis()
        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(value, result.value)
        // The supplier is called on the caller thread, exactly once - never from the polling task
        verify(exactly = 1) { callable.call() }
        // Consecutive failures short-circuit the wait instead of polling maxAttemptGetCache times
        assertTrue(elapsed < 1000, "Consecutive cache failures should stop the wait early, took ${elapsed}ms")
        assertEquals(1 + MAX_CONSECUTIVE_GET_CACHE_FAILURES, getCount)
    }

    @Test
    fun `should keep waiting when cache read failures are not consecutive`() {
        val maxAttemptGetCache = 5
        val reqShieldSmallAttempt =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    keyLock = keyLock,
                    maxAttemptGetCache = maxAttemptGetCache,
                ),
            )

        var getCount = 0
        // After the initial miss the polls alternate: empty, failed, empty, failed, ...
        // so the consecutive failure counter is reset before it can reach its limit.
        every { cacheGetter.invoke(key) } answers {
            getCount++
            if (getCount > 1 && getCount % 2 == 1) throw RuntimeException("transient cache error")
            null
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns null

        val result = reqShieldSmallAttempt.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        assertEquals(value, result.value)
        verify(exactly = 1) { callable.call() }
        // 1 initial read + 5 empty polls + 4 interleaved failures; bailing out early would read less
        assertTrue(
            getCount >= 1 + maxAttemptGetCache * 2 - 1,
            "Expected at least ${1 + maxAttemptGetCache * 2 - 1} cache reads but was $getCount",
        )
    }

    @Test
    fun `should throw ClientException with supplier error when the supplier fails after max attempts`() {
        val reqShieldSmallAttempt =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    keyLock = keyLock,
                    maxAttemptGetCache = 3,
                ),
            )

        every { cacheGetter.invoke(key) } returns null
        every { keyLock.tryLock(key, LockType.CREATE) } returns null
        every { callable.call() } throws IllegalStateException("supplier down")

        val exception =
            assertThrows<ClientException> {
                reqShieldSmallAttempt.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            }

        // The failure must not leak as an ExecutionException wrapper and must keep its cause
        assertEquals(ErrorCode.SUPPLIER_ERROR, exception.errorCode)
        assertEquals("supplier down", exception.cause?.message)
        verify(exactly = 1) { callable.call() }
    }

    @Test
    fun shouldCancelQueuedPollingWhenWaitingTimesOut() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val releaseExecutor = CountDownLatch(1)
        val executorBlocked = CountDownLatch(1)
        val reads = AtomicInteger()
        every { keyLock.tryLock(key, LockType.CREATE) } returns null
        val shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = cacheSetter,
                    getCacheFunction = {
                        reads.incrementAndGet()
                        null
                    },
                    keyLock = keyLock,
                    executor = executor,
                    maxAttemptGetCache = 1,
                ),
            )

        try {
            executor.submit {
                executorBlocked.countDown()
                releaseExecutor.await()
            }
            assertTrue(executorBlocked.await(2, TimeUnit.SECONDS))

            val result = shield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            assertEquals(value, result.value)
            verify(exactly = 1) { callable.call() }
            releaseExecutor.countDown()
            // The barrier runs after the queued poll would have become eligible.
            executor.schedule({}, GET_CACHE_INTERVAL_MILLIS * 2, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS)
            assertEquals(1, reads.get(), "Only the initial cache read should run")
        } finally {
            releaseExecutor.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
