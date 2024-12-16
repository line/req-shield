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
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.BaseReqShieldTest.Companion.AWAIT_TIMEOUT
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Callable
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReqShieldTest : BaseReqShieldTest {
    private lateinit var reqShield: ReqShield<Product>
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

    private var timeToLiveMillis: Long = 10000

    private lateinit var globalLockFunc: (String, Long) -> Boolean
    private lateinit var globalUnLockFunc: (String) -> Boolean

    @BeforeEach
    fun setup() {
        cacheSetter = mockk<(String, ReqShieldData<Product>, Long) -> Boolean>()
        cacheGetter = mockk<(String) -> ReqShieldData<Product>?>()
        globalLockFunc = mockk<(String, Long) -> Boolean>()
        globalUnLockFunc = mockk<(String) -> Boolean>()
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
    override fun `test set method (Cache not exists And local lock acquired)`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns true
        every { keyLock.unLock(key, LockType.CREATE) } returns true

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { keyLock.unLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And global lock acquired)`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any()) } returns true
        every { globalUnLockFunc(any()) } returns true

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { globalLockFunc(any(), any()) }
            verify { globalUnLockFunc(any()) }
            verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            verify { keyGlobalLock.unLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And global lock acquired And Does not exist global lock function)`() {
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
    override fun `test set method (Cache not exists And local lock acquired And callable return null)`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns true
        every { keyLock.unLock(key, LockType.CREATE) } returns true
        every { callable.call() } returns null

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            assertNull(result.value)

            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { keyLock.unLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And global lock acquired And callable return null)`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any()) } returns true
        every { globalUnLockFunc(any()) } returns true

        every { callable.call() } returns null

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertNotNull(result)
            assertNull(result.value)

            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, result, timeToLiveMillis) }
            verify { globalLockFunc(any(), any()) }
            verify { globalUnLockFunc(any()) }
            verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            verify { keyGlobalLock.unLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And local lock acquired And Throw callable ClientException)`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns true
        every { keyLock.unLock(key, LockType.CREATE) } returns true
        every { callable.call() } throws Exception("callable error")

        val exceptionCode =
            assertThrows<ClientException> { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }.errorCode

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.SUPPLIER_ERROR, exceptionCode)
            verify { cacheGetter.invoke(key) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { keyLock.unLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And global lock acquired And Throw callable ClientException)`() {
        every { cacheGetter.invoke(key) } returns null
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any()) } returns true
        every { globalUnLockFunc(any()) } returns true

        every { callable.call() } throws Exception("callable error")

        val exceptionCode =
            assertThrows<ClientException> { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }.errorCode

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.SUPPLIER_ERROR, exceptionCode)
            verify { cacheGetter.invoke(key) }
            verify { globalLockFunc(any(), any()) }
            verify { globalUnLockFunc(any()) }
            verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            verify { keyGlobalLock.unLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And local lock acquired And Throw get cache ClientException)`() {
        every { cacheGetter.invoke(key) } throws Exception("get cache error")
        every { cacheSetter.invoke(key, any(), any()) } returns true
        every { keyLock.tryLock(key, LockType.CREATE) } returns true
        every { keyLock.unLock(key, LockType.CREATE) } returns true

        val exceptionCode =
            assertThrows<ClientException> { reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis) }.errorCode

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.GET_CACHE_ERROR, exceptionCode)
            verify { cacheGetter.invoke(key) }
            verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
            verify(inverse = true) { keyLock.unLock(key, LockType.CREATE) }
            verify(inverse = true) { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And global lock acquired And Throw get cache ClientException)`() {
        every { cacheGetter.invoke(key) } throws Exception("get cache error")
        every { cacheSetter.invoke(key, any(), any()) } returns true

        every { globalLockFunc(any(), any()) } returns true
        every { globalUnLockFunc(any()) } returns true

        val exceptionCode =
            assertThrows<ClientException> { reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis) }.errorCode

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(ErrorCode.GET_CACHE_ERROR, exceptionCode)
            verify { cacheGetter.invoke(key) }
            verify(inverse = true) { globalLockFunc(any(), any()) }
            verify(inverse = true) { globalUnLockFunc(any()) }
            verify(inverse = true) { keyGlobalLock.tryLock(key, LockType.CREATE) }
            verify(inverse = true) { keyGlobalLock.unLock(key, LockType.CREATE) }
            verify(inverse = true) { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And local lock not acquired)`() {
        every { cacheGetter.invoke(key) } returns null
        every { keyLock.tryLock(key, LockType.CREATE) } returns false

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            result.value != null
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { keyLock.tryLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache not exists And global lock not acquired)`() {
        every { cacheGetter.invoke(key) } returns null
        every { globalLockFunc(any(), any()) } returns false

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            result.value != null
            assertNotNull(result)
            verify { cacheGetter.invoke(key) }
            verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache exists, but not targeted for update)`() {
        val reqShieldData = ReqShieldData<Product>(value, timeToLiveMillis)

        every { cacheGetter.invoke(key) } returns reqShieldData
        every { keyLock.tryLock(key, LockType.UPDATE) } returns false

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            verify(inverse = true) { cacheSetter.invoke(key, reqShieldData, timeToLiveMillis) }
            verify(inverse = true) { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache exists and the update target)`() {
        timeToLiveMillis = 1000
        val reqShieldData = ReqShieldData<Product>(oldValue, timeToLiveMillis)
        val newReqShieldData = ReqShieldData<Product>(value, timeToLiveMillis)

        every { cacheGetter.invoke(key) } returns reqShieldData
        every { cacheSetter.invoke(key, any(), any()) } answers { true }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns true
        every { keyLock.unLock(key, LockType.UPDATE) } returns true

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, newReqShieldData, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.UPDATE) }
            verify { keyLock.unLock(key, LockType.UPDATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `test set method (Cache exists and the update target and callable return null)`() {
        timeToLiveMillis = 1000
        val reqShieldData = ReqShieldData<Product>(value, timeToLiveMillis)
        val reqShieldDataNull = ReqShieldData<Product>(null, timeToLiveMillis)

        every { cacheGetter.invoke(key) } returns reqShieldData
        every { cacheSetter.invoke(key, any(), any()) } answers { true }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns true
        every { keyLock.unLock(key, LockType.UPDATE) } returns true
        every { callable.call() } returns null

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        await().atMost(Duration.ofMillis(AWAIT_TIMEOUT)).untilAsserted {
            assertEquals(reqShieldData, result)
            verify { cacheGetter.invoke(key) }
            verify { cacheSetter.invoke(key, reqShieldDataNull, timeToLiveMillis) }
            verify { keyLock.tryLock(key, LockType.UPDATE) }
            verify { keyLock.unLock(key, LockType.UPDATE) }
            verify { callable.call() }
        }
    }

    @Test
    override fun `executeSetCacheFunction should handle exception from cacheSetter`() {
        every { keyLock.tryLock(any(), any()) } returns true
        every { keyLock.unLock(any(), any()) } returns true

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
                method.invoke(reqShield, cacheSetter, key, reqShieldData, lockType)
            }

        val cause = exception.cause
        assertTrue(cause is ClientException)
        assertEquals(ErrorCode.SET_CACHE_ERROR, (cause as ClientException).errorCode)

        verify { cacheSetter.invoke(key, reqShieldData, 1000L) }
        verify { keyLock.unLock(any(), any()) }
    }
}
