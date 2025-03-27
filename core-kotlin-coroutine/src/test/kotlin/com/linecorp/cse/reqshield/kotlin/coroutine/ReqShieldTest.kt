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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.LocalDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private val key = "testKey"
    private val oldValue = Product("oldTestValue", "oldTestName")
    private val value = Product("testValue", "testName")
    private val callable: suspend () -> Product? = mockk()

    private var timeToLiveMillis: Long = 10000

    private lateinit var globalLockFunc: suspend (String, Long) -> Boolean
    private lateinit var globalUnLockFunc: suspend (String) -> Boolean

    @BeforeEach
    fun setup() {
        cacheSetter = mockk<suspend (String, ReqShieldData<Product>, Long) -> Boolean>()
        cacheGetter = mockk<suspend (String) -> ReqShieldData<Product>?>()
        globalLockFunc = mockk<suspend (String, Long) -> Boolean>()
        globalUnLockFunc = mockk<suspend (String) -> Boolean>()
        keyLock = mockk<KeyLock>()

        keyGlobalLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, 3000)

        coEvery { callable() } returns value

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

        mockkStatic(LocalDateTime::class)
        every { LocalDateTime.now() } returns LocalDateTime.of(2023, 11, 13, 12, 0, 0, 0)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(LocalDateTime::class)
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquired() =
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns true
            coEvery { keyLock.unLock(key, LockType.CREATE) } returns true

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            delay(100)

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify { keyLock.unLock(key, LockType.CREATE) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndOnlyUpdateCache() {
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            val result = reqShieldOnlyUpdateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            delay(100)

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.CREATE) }
            coVerify { callable() }
        }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquired() =
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(any(), any()) } returns true
            coEvery { globalUnLockFunc(any()) } returns true

            val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            delay(100)

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { globalLockFunc(any(), any()) }
            coVerify { globalUnLockFunc(any()) }
            coVerify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            coVerify { keyGlobalLock.unLock(key, LockType.CREATE) }
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
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns true
            coEvery { keyLock.unLock(key, LockType.CREATE) } returns true
            coEvery { callable() } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            delay(100)

            assertNotNull(result)
            assertNull(result.value)

            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify { keyLock.unLock(key, LockType.CREATE) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndCallableReturnNull() =
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(any(), any()) } returns true
            coEvery { globalUnLockFunc(any()) } returns true

            coEvery { callable() } returns null

            val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            delay(100)

            assertNotNull(result)
            assertNull(result.value)

            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            coVerify { globalLockFunc(any(), any()) }
            coVerify { globalUnLockFunc(any()) }
            coVerify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            coVerify { keyGlobalLock.unLock(key, LockType.CREATE) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowCallableClientException() =
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns true
            coEvery { keyLock.unLock(key, LockType.CREATE) } returns true
            coEvery { callable() } throws Exception("callable error")

            val result = runCatching { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }
            delay(100)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ClientException)
            assertEquals(ErrorCode.SUPPLIER_ERROR, (result.exceptionOrNull() as? ClientException)?.errorCode)

            coVerify { cacheGetter.invoke(key) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
            coVerify { keyLock.unLock(key, LockType.CREATE) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowCallableClientException() =
        runBlocking {
            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(any(), any()) } returns true
            coEvery { globalUnLockFunc(any()) } returns true

            coEvery { callable() } throws Exception("callable error")

            val result = runCatching { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }
            delay(100)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ClientException)
            assertEquals(ErrorCode.SUPPLIER_ERROR, (result.exceptionOrNull() as? ClientException)?.errorCode)

            coVerify { cacheGetter.invoke(key) }
            coVerify { globalLockFunc(any(), any()) }
            coVerify { globalUnLockFunc(any()) }
            coVerify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            coVerify { keyGlobalLock.unLock(key, LockType.CREATE) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowGetCacheClientException() =
        runTest {
            coEvery { cacheGetter.invoke(key) } throws Exception("get cache error")
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns true
            coEvery { keyLock.unLock(key, LockType.CREATE) } returns true

            val result = runCatching { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }
            delay(100)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ClientException)
            assertEquals(ErrorCode.GET_CACHE_ERROR, (result.exceptionOrNull() as? ClientException)?.errorCode)

            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.CREATE) }
            coVerify(inverse = true) { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowGetCacheClientException() =
        runBlocking {
            coEvery { cacheGetter.invoke(key) } throws Exception("get cache error")
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true

            coEvery { globalLockFunc(any(), any()) } returns true
            coEvery { globalUnLockFunc(any()) } returns true

            val result = runCatching { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }
            delay(100)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ClientException)
            assertEquals(ErrorCode.GET_CACHE_ERROR, (result.exceptionOrNull() as? ClientException)?.errorCode)

            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { globalLockFunc(any(), any()) }
            coVerify(inverse = true) { globalUnLockFunc(any()) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.CREATE) }
            coVerify(inverse = true) { callable() }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockNotAcquired() =
        runBlocking {
            val timeToLiveMillis: Long = 10000

            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { keyLock.tryLock(key, LockType.CREATE) } returns false

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            withTimeoutOrNull(1000L) {
                while (result.value == null) {
                    delay(100L) // Check every 100 milliseconds
                }
            }

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            coVerify { keyLock.tryLock(key, LockType.CREATE) }
        }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockNotAcquired() =
        runBlocking {
            val timeToLiveMillis: Long = 10000

            coEvery { cacheGetter.invoke(key) } returns null
            coEvery { globalLockFunc(any(), any()) } returns false

            val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            withTimeoutOrNull(1000L) {
                while (result.value == null) {
                    delay(100L) // Check every 100 milliseconds
                }
            }

            assertNotNull(result)
            coVerify { cacheGetter.invoke(key) }
            coVerify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
            coVerify { globalLockFunc(any(), any()) }
            coVerify { keyGlobalLock.tryLock(key, LockType.CREATE) }
        }

    @Test
    override fun testSetMethodCacheExistsButNotTargetedForUpdate() =
        runTest {
            val timeToLiveMillis: Long = 10000
            val reqShieldData = ReqShieldData(value, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } returns true
            coEvery { keyLock.tryLock(key, LockType.UPDATE) } returns false

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }
        }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTarget() =
        runBlocking {
            val timeToLiveMillis: Long = 1000
            val reqShieldData = ReqShieldData(oldValue, timeToLiveMillis)
            val newReqShieldData = ReqShieldData(value, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } coAnswers { true }
            coEvery { keyLock.tryLock(key, LockType.UPDATE) } returns true
            coEvery { keyLock.unLock(key, LockType.UPDATE) } returns true

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            delay(100)

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, newReqShieldData, timeToLiveMillis) }
            coVerify { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify { keyLock.unLock(key, LockType.UPDATE) }
            coVerify { callable() }
        }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetOnlyCreateCache() {
        runBlocking {
            val timeToLiveMillis: Long = 1000
            val reqShieldData = ReqShieldData(oldValue, timeToLiveMillis)
            val newReqShieldData = ReqShieldData(value, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } coAnswers { true }

            val result = reqShieldOnlyCreateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)

            delay(100)

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, newReqShieldData, timeToLiveMillis) }
            coVerify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify(inverse = true) { keyLock.unLock(key, LockType.UPDATE) }
            coVerify { callable() }
        }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetAndCallableReturnNull() =
        runBlocking {
            timeToLiveMillis = 1000
            val reqShieldData = ReqShieldData(value, timeToLiveMillis)
            val reqShieldDataNull = ReqShieldData<Product>(null, timeToLiveMillis)

            coEvery { cacheGetter.invoke(key) } returns reqShieldData
            coEvery { cacheSetter.invoke(key, any(), any()) } coAnswers { true }
            coEvery { keyLock.tryLock(key, LockType.UPDATE) } returns true
            coEvery { keyLock.unLock(key, LockType.UPDATE) } returns true
            coEvery { callable() } returns null

            val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)
            delay(100)

            assertEquals(reqShieldData, result)
            coVerify { cacheGetter.invoke(key) }
            coVerify { cacheSetter.invoke(key, reqShieldDataNull, timeToLiveMillis) }
            coVerify { keyLock.tryLock(key, LockType.UPDATE) }
            coVerify { keyLock.unLock(key, LockType.UPDATE) }
            coVerify { callable() }
        }

    @Test
    override fun executeSetCacheFunctionShouldHandleExceptionFromCacheSetter() =
        runBlocking {
            coEvery { keyLock.tryLock(any(), any()) } returns true
            coEvery { keyLock.unLock(any(), any()) } returns true

            val key = "key"
            val reqShieldData = ReqShieldData(value, 1000L)
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
                    method.invoke(reqShield, cacheSetter, key, reqShieldData, lockType, continuation)
                }

            val cause = exception.cause
            assertTrue(cause is ClientException)
            assertEquals(ErrorCode.SET_CACHE_ERROR, cause.errorCode)

            coVerify { cacheSetter.invoke(key, reqShieldData, 1000L) }
            coVerify { keyLock.unLock(any(), any()) }
        }
}
