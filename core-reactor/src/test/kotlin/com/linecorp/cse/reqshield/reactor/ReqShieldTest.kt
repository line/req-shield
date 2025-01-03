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

package com.linecorp.cse.reqshield.reactor

import com.linecorp.cse.reqshield.reactor.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Callable
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReqShieldTest : BaseReqShieldTest {
    private lateinit var reqShield: ReqShield<Product>
    private lateinit var reqShieldForGlobalLock: ReqShield<Product>
    private lateinit var reqShieldForGlobalLockForError: ReqShield<Product>
    private lateinit var cacheSetter: (String, ReqShieldData<Product>, Long) -> Mono<Boolean>
    private lateinit var cacheGetter: (String) -> Mono<ReqShieldData<Product>?>
    private lateinit var keyLock: KeyLock
    private lateinit var keyGlobalLock: KeyLock
    private val key = "testKey"
    private val oldValue = Product("oldTestValue", "oldTestValue")
    private val value = Product("testValue", "testValue")
    private val callable: Callable<Mono<Product?>> = mockk()

    private var timeToLiveMillis: Long = 10000

    private lateinit var globalLockFunc: (String, Long) -> Mono<Boolean>
    private lateinit var globalUnLockFunc: (String) -> Mono<Boolean>

    @BeforeEach
    fun setup() {
        cacheSetter = mockk<(String, ReqShieldData<Product>, Long) -> Mono<Boolean>>()
        cacheGetter = mockk<(String) -> Mono<ReqShieldData<Product>?>>()
        globalLockFunc = mockk<(String, Long) -> Mono<Boolean>>()
        globalUnLockFunc = mockk<(String) -> Mono<Boolean>>()
        keyLock = mockk<KeyLock>()

        keyGlobalLock = KeyGlobalLock(globalLockFunc, globalUnLockFunc, 3000)

        every { callable.call() } returns Mono.just(value)

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
    override fun testSetMethodCacheNotExistsAndLocalLockAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(true)
        every { keyLock.unLock(key, LockType.CREATE) } returns Mono.just(true)

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { keyLock.unLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any()) } returns Mono.just(true)

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { globalLockFunc(any(), any()) }
        verify { globalUnLockFunc(any()) }
        verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
        verify { keyGlobalLock.unLock(key, LockType.CREATE) }
        verify { callable.call() }
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
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndCallableReturnNull() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(true)
        every { keyLock.unLock(key, LockType.CREATE) } returns Mono.empty()
        every { callable.call() } returns Mono.empty()

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
                assertNull(it.value)
            }.verifyComplete()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { keyLock.unLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndCallableReturnNull() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any()) } returns Mono.just(true)

        every { callable.call() } returns Mono.empty()

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
                assertNull(it.value)
            }.verifyComplete()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { globalLockFunc(any(), any()) }
        verify { globalUnLockFunc(any()) }
        verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
        verify { keyGlobalLock.unLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowCallableClientException() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(true)
        every { keyLock.unLock(key, LockType.CREATE) } returns Mono.empty()
        every { callable.call() } returns Mono.error(Exception("callable error"))

        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.SUPPLIER_ERROR
            }.verify()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { keyLock.unLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowCallableClientException() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any()) } returns Mono.just(true)

        every { callable.call() } returns Mono.error(Exception("callable error"))

        StepVerifier
            .create(reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.SUPPLIER_ERROR
            }.verify()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { globalLockFunc(any(), any()) }
        verify { globalUnLockFunc(any()) }
        verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
        verify { keyGlobalLock.unLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowGetCacheClientException() {
        every { cacheGetter.invoke(key) } returns Mono.error(Exception("get cache error"))
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(true)
        every { keyLock.unLock(key, LockType.CREATE) } returns Mono.just(true)

        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.GET_CACHE_ERROR
            }.verify()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
        verify(inverse = true) { keyLock.unLock(key, LockType.CREATE) }
        verify(inverse = true) { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowGetCacheClientException() {
        every { cacheGetter.invoke(key) } returns Mono.error(Exception("get cache error"))
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any()) } returns Mono.just(true)

        StepVerifier
            .create(reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.GET_CACHE_ERROR
            }.verify()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { globalLockFunc(any(), any()) }
        verify(inverse = true) { globalUnLockFunc(any()) }
        verify(inverse = true) { keyGlobalLock.tryLock(key, LockType.CREATE) }
        verify(inverse = true) { keyGlobalLock.unLock(key, LockType.CREATE) }
        verify(inverse = true) { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockNotAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(false)

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockNotAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { globalLockFunc(any(), any()) } returns Mono.just(false)

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
        verify { globalLockFunc(any(), any()) }
        verify { keyGlobalLock.tryLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheExistsButNotTargetedForUpdate() {
        timeToLiveMillis = 1000
        val reqShieldData = ReqShieldData(value, timeToLiveMillis)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)
        every { keyLock.tryLock(key, LockType.UPDATE) } returns Mono.just(false)

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectNextMatches {
                assertEquals(reqShieldData, it)
                true
            }.expectComplete()
            .verify()

        verify { keyLock.tryLock(key, LockType.UPDATE) }
        verify { cacheGetter.invoke(key) }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTarget() {
        timeToLiveMillis = 1000
        val reqShieldData = ReqShieldData(oldValue, timeToLiveMillis)
        val newReqShieldData = ReqShieldData(value, timeToLiveMillis)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)
        every { cacheSetter.invoke(key, any(), any()) } answers { Mono.just(true) }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns Mono.just(true)
        every { keyLock.unLock(key, LockType.UPDATE) } returns Mono.empty()

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectNextMatches {
                assertEquals(reqShieldData, it)
                true
            }.expectComplete()
            .verify()

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { keyLock.tryLock(key, LockType.UPDATE) }
        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, newReqShieldData, timeToLiveMillis) }
        verify { keyLock.unLock(key, LockType.UPDATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetAndCallableReturnNull() {
        timeToLiveMillis = 1000
        val reqShieldData = ReqShieldData(value, timeToLiveMillis)
        val reqShieldDataNull = ReqShieldData<Product>(null, timeToLiveMillis)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)
        every { cacheSetter.invoke(key, any(), any()) } answers { Mono.just(true) }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns Mono.just(true)
        every { keyLock.unLock(key, LockType.UPDATE) } returns Mono.empty()
        every { callable.call() } returns Mono.empty()

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectSubscription()
            .expectNextMatches { it == reqShieldData }
            .expectComplete()
            .verify(Duration.ofSeconds(1))

        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, reqShieldDataNull, timeToLiveMillis) }
        verify { keyLock.tryLock(key, LockType.UPDATE) }
        verify { keyLock.unLock(key, LockType.UPDATE) }
        verify { callable.call() }
    }

    @Test
    override fun executeSetCacheFunctionShouldHandleExceptionFromCacheSetter() {
        every { keyLock.tryLock(any(), any()) } returns Mono.just(true)
        every { keyLock.unLock(any(), any()) } returns Mono.just(true)

        val key = "key"
        val reqShieldData = ReqShieldData(value, 1000L)
        val lockType = LockType.CREATE

        val method: Method =
            ReqShield::class.java.declaredMethods.firstOrNull { it.name == "executeSetCacheFunction" }
                ?: throw NoSuchMethodException("Method executeSetCacheFunction not found")

        method.isAccessible = true

        every { cacheSetter.invoke(any(), any(), any()) } returns Mono.error(Exception("set cache error"))

        val mono =
            Mono.defer {
                try {
                    method.invoke(reqShield, cacheSetter, key, reqShieldData, lockType) as Mono<Unit>
                } catch (e: InvocationTargetException) {
                    Mono.error(e.cause ?: e)
                }
            }

        StepVerifier
            .create(mono)
            .expectErrorSatisfies { throwable ->
                assertTrue(throwable is ClientException)
                assertEquals(ErrorCode.SET_CACHE_ERROR, (throwable as ClientException).errorCode)
            }.verify()

        verify { cacheSetter.invoke(key, reqShieldData, 1000L) }
        verify { keyLock.unLock(any(), any()) }
    }
}
