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
import com.linecorp.cse.reqshield.reactor.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.support.BaseReqShieldTest
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_KEY_PREFIX
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReqShieldTest : BaseReqShieldTest {
    private lateinit var reqShield: ReqShield<Product>
    private lateinit var reqShieldOnlyUpdateCache: ReqShield<Product>
    private lateinit var reqShieldOnlyCreateCache: ReqShield<Product>
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
    private val token = "token"

    private var timeToLiveMillis: Long = 10000

    private lateinit var globalLockFunc: (String, String, Long) -> Mono<Boolean>
    private lateinit var globalUnLockFunc: (String, String) -> Mono<Boolean>

    @BeforeEach
    fun setup() {
        cacheSetter = mockk<(String, ReqShieldData<Product>, Long) -> Mono<Boolean>>()
        cacheGetter = mockk<(String) -> Mono<ReqShieldData<Product>?>>()
        globalLockFunc = mockk<(String, String, Long) -> Mono<Boolean>>()
        globalUnLockFunc = mockk<(String, String) -> Mono<Boolean>>()
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

    /** ReqShield instance that gives up waiting for the lock holder quickly. */
    private fun reqShieldWithMaxAttempt(maxAttemptGetCache: Int): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                cacheSetter,
                cacheGetter,
                keyLock = keyLock,
                maxAttemptGetCache = maxAttemptGetCache,
            ),
        )

    /** Cached entry that is not yet old enough to be refreshed. */
    private fun freshReqShieldData(cachedValue: Product?): ReqShieldData<Product> =
        ReqShieldData(cachedValue, ReqShieldData.Status.NEW, nowToEpochTime(), timeToLiveMillis)

    @Test
    fun shouldReuseCacheWhenAnEarlierMissResumesAfterAnotherRequestFinishes() {
        val cached = AtomicReference<ReqShieldData<Product>?>()
        val reads = AtomicInteger()
        val calls = AtomicInteger()
        lateinit var delayedMiss: MonoSink<ReqShieldData<Product>?>
        val shield =
            ReqShield(
                ReqShieldConfiguration(
                    setCacheFunction = { _, data, _ ->
                        Mono.fromCallable {
                            cached.set(data)
                            true
                        }
                    },
                    getCacheFunction = {
                        Mono.defer {
                            if (reads.incrementAndGet() == 1) {
                                // Hold a cache miss until the other request has stored its result and released the lock.
                                Mono.create { delayedMiss = it }
                            } else {
                                Mono.justOrEmpty(cached.get())
                            }
                        }
                    },
                    scheduler = Schedulers.immediate(),
                ),
            )
        val isolatedKey = "delayed-miss-${java.util.UUID.randomUUID()}"
        val supplier =
            Callable {
                Mono.fromCallable<Product?> {
                    calls.incrementAndGet()
                    value
                }
            }

        StepVerifier
            .create(shield.getAndSetReqShieldData(isolatedKey, supplier, timeToLiveMillis))
            .then {
                val winner = shield.getAndSetReqShieldData(isolatedKey, supplier, timeToLiveMillis).block()
                assertEquals(value, winner?.value)
                assertNotNull(cached.get())
                delayedMiss.success()
            }.expectNextMatches { it === cached.get() }
            .verifyComplete()

        assertEquals(1, calls.get())
    }

    @Test
    fun shouldReuseNullValuedCacheAfterAcquiringLocalLock() {
        val cached = freshReqShieldData(null)
        every { cacheGetter(key) } returnsMany listOf(Mono.empty(), Mono.just(cached))
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.just(true)

        StepVerifier.create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectNext(cached)
            .verifyComplete()

        verify(timeout = 1000, exactly = 1) { keyLock.unLock(key, LockType.CREATE, token) }
        verify(exactly = 0) { callable.call() }
        verify(exactly = 0) { cacheSetter(any(), any(), any()) }
    }

    @Test
    fun shouldReuseCacheAfterAcquiringGlobalLockAndReleaseItsToken() {
        val cached = freshReqShieldData(value)
        every { cacheGetter(key) } returnsMany listOf(Mono.empty(), Mono.just(cached))
        every { globalLockFunc(any(), any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any(), any()) } returns Mono.just(true)

        StepVerifier.create(reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectNext(cached)
            .verifyComplete()

        val owner = slot<String>()
        val lockKey = "$LOCK_KEY_PREFIX${key}_${LockType.CREATE.name}"
        verify(exactly = 1) { globalLockFunc(lockKey, capture(owner), 3000) }
        verify(timeout = 1000, exactly = 1) { globalUnLockFunc(lockKey, owner.captured) }
        verify(exactly = 0) { callable.call() }
        verify(exactly = 0) { cacheSetter(any(), any(), any()) }
    }

    @Test
    fun shouldReleaseLockWhenCacheRecheckFails() {
        val failure = IllegalStateException("cache recheck failed")
        every { cacheGetter(key) } returnsMany listOf(Mono.empty(), Mono.error(failure))
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.just(true)

        StepVerifier.create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { it is ClientException && it.errorCode == ErrorCode.GET_CACHE_ERROR && it.cause === failure }
            .verify()

        verify(timeout = 1000, exactly = 1) { keyLock.unLock(key, LockType.CREATE, token) }
        verify(exactly = 0) { callable.call() }
    }

    @Test
    fun shouldReleaseLockWhenCacheRecheckIsCancelled() {
        every { cacheGetter(key) } returnsMany listOf(Mono.empty(), Mono.never())
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.just(true)

        StepVerifier.create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .then { verify(timeout = 1000, exactly = 2) { cacheGetter(key) } }
            .thenCancel()
            .verify()

        verify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, token) }
        verify(exactly = 0) { callable.call() }
    }

    @Test
    fun shouldKeepLockUntilAsyncCacheWriteCompletesAfterRecheckMiss() {
        lateinit var pendingWrite: MonoSink<Boolean>
        every { cacheGetter(key) } returns Mono.empty()
        every { cacheSetter(key, any(), any()) } returns Mono.create { pendingWrite = it }
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.just(true)
        val shield =
            ReqShield(
                ReqShieldConfiguration(cacheSetter, cacheGetter, keyLock = keyLock, scheduler = Schedulers.immediate()),
            )

        StepVerifier.create(shield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectNextMatches { it.value == value }
            .verifyComplete()

        verify(exactly = 2) { cacheGetter(key) }
        verify(exactly = 0) { keyLock.unLock(key, LockType.CREATE, token) }
        pendingWrite.success(true)
        verify(exactly = 1) { keyLock.unLock(key, LockType.CREATE, token) }
        verify(exactly = 1) { callable.call() }
    }

    /** Cached entry that has passed the decisionForUpdate threshold (90% of its TTL). */
    private fun updateTargetReqShieldData(cachedValue: Product?): ReqShieldData<Product> =
        ReqShieldData(
            cachedValue,
            ReqShieldData.Status.NEW,
            nowToEpochTime() - (timeToLiveMillis * 0.9).toLong(),
            timeToLiveMillis,
        )

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.just(true)

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { keyLock.unLock(key, LockType.CREATE, token) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndOnlyUpdateCache() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        val result = reqShieldOnlyUpdateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
        verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any(), any()) } returns Mono.just(true)

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
            }.verifyComplete()

        awaitFireAndForget()

        val lockKey = "$LOCK_KEY_PREFIX${key}_${LockType.CREATE.name}"
        val tokenSlot = slot<String>()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { globalLockFunc(lockKey, capture(tokenSlot), 3000) }
        // The very token handed out by tryLock must be the one used to release the lock
        verify { globalUnLockFunc(lockKey, tokenSlot.captured) }
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
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.empty()
        every { callable.call() } returns Mono.empty()

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
                assertNull(it.value)
            }.verifyComplete()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { keyLock.unLock(key, LockType.CREATE, token) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndCallableReturnNull() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any(), any()) } returns Mono.just(true)

        every { callable.call() } returns Mono.empty()

        val result = reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
                assertNull(it.value)
            }.verifyComplete()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify { cacheSetter.invoke(key, any(), any()) }
        verify { globalLockFunc(any(), any(), any()) }
        verify { globalUnLockFunc(any(), any()) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowCallableClientException() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.CREATE, token) } returns Mono.empty()
        every { callable.call() } returns Mono.error(Exception("callable error"))

        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.SUPPLIER_ERROR
            }.verify()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { keyLock.unLock(key, LockType.CREATE, token) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowCallableClientException() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        every { globalLockFunc(any(), any(), any()) } returns Mono.just(true)
        every { globalUnLockFunc(any(), any()) } returns Mono.just(true)

        every { callable.call() } returns Mono.error(Exception("callable error"))

        StepVerifier
            .create(reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.SUPPLIER_ERROR
            }.verify()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify { globalLockFunc(any(), any(), any()) }
        verify { globalUnLockFunc(any(), any()) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowGetCacheClientException() {
        every { cacheGetter.invoke(key) } returns Mono.error(Exception("get cache error"))
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.GET_CACHE_ERROR
            }.verify()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }
        verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
        verify(inverse = true) { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockAcquiredAndThrowGetCacheClientException() {
        every { cacheGetter.invoke(key) } returns Mono.error(Exception("get cache error"))
        every { cacheSetter.invoke(key, any(), any()) } returns Mono.just(true)

        StepVerifier
            .create(reqShieldForGlobalLock.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches { throwable ->
                throwable is ClientException && throwable.errorCode == ErrorCode.GET_CACHE_ERROR
            }.verify()

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { globalLockFunc(any(), any(), any()) }
        verify(inverse = true) { globalUnLockFunc(any(), any()) }
        verify(inverse = true) { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndLocalLockNotAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.empty()

        val reqShieldWithFewAttempts = reqShieldWithMaxAttempt(3)
        val result = reqShieldWithFewAttempts.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
                assertEquals(value, it.value)
            }.verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
        verify { keyLock.tryLock(key, LockType.CREATE) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheNotExistsAndGlobalLockNotAcquired() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { globalLockFunc(any(), any(), any()) } returns Mono.just(false)

        val reqShieldWithFewAttempts =
            ReqShield(
                ReqShieldConfiguration(
                    cacheSetter,
                    cacheGetter,
                    globalLockFunc,
                    globalUnLockFunc,
                    isLocalLock = false,
                    keyLock = keyGlobalLock,
                    maxAttemptGetCache = 3,
                ),
            )

        val result = reqShieldWithFewAttempts.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .assertNext {
                assertNotNull(it)
                assertEquals(value, it.value)
            }.verifyComplete()

        verify { cacheGetter.invoke(key) }
        verify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
        verify { globalLockFunc(any(), any(), any()) }
        verify(inverse = true) { globalUnLockFunc(any(), any()) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheExistsButNotTargetedForUpdate() {
        timeToLiveMillis = 1000
        val reqShieldData = freshReqShieldData(value)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectNextMatches {
                assertEquals(reqShieldData, it)
                true
            }.expectComplete()
            .verify()

        // A fresh entry is not refreshed, so the update lock must never be taken
        verify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
        verify(inverse = true) { callable.call() }
        verify { cacheGetter.invoke(key) }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTarget() {
        timeToLiveMillis = 1000
        val reqShieldData = updateTargetReqShieldData(oldValue)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)
        every { cacheSetter.invoke(key, any(), any()) } answers { Mono.just(true) }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.UPDATE, token) } returns Mono.empty()

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectNextMatches {
                assertEquals(reqShieldData, it)
                true
            }.expectComplete()
            .verify()

        awaitFireAndForget()

        verify { keyLock.tryLock(key, LockType.UPDATE) }
        verify { cacheGetter.invoke(key) }
        verify {
            cacheSetter.invoke(
                key,
                match { it.value == value && it.timeToLiveMillis == timeToLiveMillis },
                timeToLiveMillis,
            )
        }
        verify { keyLock.unLock(key, LockType.UPDATE, token) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetOnlyCreateCache() {
        timeToLiveMillis = 1000
        val reqShieldData = updateTargetReqShieldData(oldValue)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)
        every { cacheSetter.invoke(key, any(), any()) } answers { Mono.just(true) }

        val result = reqShieldOnlyCreateCache.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectNextMatches {
                assertEquals(reqShieldData, it)
                true
            }.expectComplete()
            .verify()

        awaitFireAndForget()

        verify(inverse = true) { keyLock.tryLock(key, LockType.UPDATE) }
        verify { cacheGetter.invoke(key) }
        verify {
            cacheSetter.invoke(
                key,
                match { it.value == value && it.timeToLiveMillis == timeToLiveMillis },
                timeToLiveMillis,
            )
        }
        verify(inverse = true) { keyLock.unLock(key, LockType.UPDATE, any()) }
        verify { callable.call() }
    }

    @Test
    override fun testSetMethodCacheExistsAndTheUpdateTargetAndCallableReturnNull() {
        timeToLiveMillis = 1000
        val reqShieldData = updateTargetReqShieldData(value)

        every { cacheGetter.invoke(key) } returns Mono.just(reqShieldData)
        every { cacheSetter.invoke(key, any(), any()) } answers { Mono.just(true) }
        every { keyLock.tryLock(key, LockType.UPDATE) } returns Mono.just(token)
        every { keyLock.unLock(key, LockType.UPDATE, token) } returns Mono.empty()
        every { callable.call() } returns Mono.empty()

        val result = reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis)

        StepVerifier
            .create(result)
            .expectSubscription()
            .expectNextMatches { it == reqShieldData }
            .expectComplete()
            .verify(Duration.ofSeconds(1))

        awaitFireAndForget()

        verify { cacheGetter.invoke(key) }
        verify {
            cacheSetter.invoke(
                key,
                match { it.value == null && it.timeToLiveMillis == timeToLiveMillis },
                timeToLiveMillis,
            )
        }
        verify { keyLock.tryLock(key, LockType.UPDATE) }
        verify { keyLock.unLock(key, LockType.UPDATE, token) }
        verify { callable.call() }
    }

    @Test
    override fun executeSetCacheFunctionShouldHandleExceptionFromCacheSetter() {
        every { keyLock.unLock(any(), any(), any()) } returns Mono.just(true)

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
                    @Suppress("UNCHECKED_CAST")
                    method.invoke(reqShield, cacheSetter, key, reqShieldData, lockType, token) as Mono<Unit>
                } catch (e: InvocationTargetException) {
                    Mono.error(e.cause ?: e)
                }
            }

        StepVerifier
            .create(mono)
            .expectErrorSatisfies { throwable ->
                assertTrue(throwable is ClientException)
                assertEquals(ErrorCode.SET_CACHE_ERROR, (throwable as ClientException).errorCode)
                assertNotNull(throwable.cause)
            }.verify()

        verify { cacheSetter.invoke(key, reqShieldData, 1000L) }
        verify { keyLock.unLock(key, lockType, token) }
    }

    @Test
    fun `should return the value another request wrote to the cache while waiting for the lock`() {
        // 1 initial read + 3 polls: the lock holder fills the cache on the 3rd poll
        val cachedData = freshReqShieldData(oldValue)
        val getCacheInvocations = AtomicInteger(0)

        every { cacheGetter.invoke(key) } answers {
            if (getCacheInvocations.incrementAndGet() <= 3) Mono.empty() else Mono.just(cachedData)
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.empty()

        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectNext(cachedData)
            .verifyComplete()

        assertEquals(4, getCacheInvocations.get())
        // The waiter must not call the supplier when the cache gets filled
        verify(inverse = true) { callable.call() }
        verify(inverse = true) { cacheSetter.invoke(key, any(), any()) }
    }

    @Test
    fun `should fall back to the supplier after consecutive get cache failures`() {
        val getCacheInvocations = AtomicInteger(0)

        every { cacheGetter.invoke(key) } answers {
            // 1st call is the initial read, the 3 following polls all fail
            if (getCacheInvocations.incrementAndGet() == 1) {
                Mono.empty()
            } else {
                Mono.error(RuntimeException("cache down"))
            }
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.empty()

        // maxAttemptGetCache is far higher than the failure threshold, so bailing out is what stops the polling
        StepVerifier
            .create(reqShieldWithMaxAttempt(30).getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .assertNext { assertEquals(value, it.value) }
            .verifyComplete()

        // 1 initial read + MAX_CONSECUTIVE_GET_CACHE_FAILURES(3) failing polls
        assertEquals(4, getCacheInvocations.get())
        verify(exactly = 1) { callable.call() }
        verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
    }

    @Test
    fun `should keep polling when get cache failures are not consecutive`() {
        val maxAttemptGetCache = 6
        val getCacheInvocations = AtomicInteger(0)

        every { cacheGetter.invoke(key) } answers {
            // Initial read, then polls alternating between two failures and an empty (successful) read.
            // Without resetting the counter on a successful read, polling would bail out on the 4th poll.
            when (getCacheInvocations.incrementAndGet()) {
                1, 4, 7 -> Mono.empty()
                else -> Mono.error(RuntimeException("cache down"))
            }
        }
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.empty()

        StepVerifier
            .create(
                reqShieldWithMaxAttempt(maxAttemptGetCache).getAndSetReqShieldData(key, callable, timeToLiveMillis),
            ).assertNext { assertEquals(value, it.value) }
            .verifyComplete()

        // Every poll was attempted: 1 initial read + first poll + maxAttemptGetCache retries
        assertEquals(2 + maxAttemptGetCache, getCacheInvocations.get())
        verify(exactly = 1) { callable.call() }
    }

    @Test
    fun `should surface supplier error when the supplier fails after waiting for the lock`() {
        every { cacheGetter.invoke(key) } returns Mono.empty()
        every { keyLock.tryLock(key, LockType.CREATE) } returns Mono.empty()
        every { callable.call() } returns Mono.error(IllegalStateException("supplier down"))

        StepVerifier
            .create(reqShieldWithMaxAttempt(2).getAndSetReqShieldData(key, callable, timeToLiveMillis))
            .expectErrorMatches {
                it is ClientException && it.errorCode == ErrorCode.SUPPLIER_ERROR && it.cause != null
            }.verify()

        verify(exactly = 1) { callable.call() }
        // The waiter holds no lock, so it must not try to release one
        verify(inverse = true) { keyLock.unLock(key, LockType.CREATE, any()) }
    }

    /** Gives the fire-and-forget cache writes / unlocks a chance to run before verifying them. */
    private fun awaitFireAndForget() {
        StepVerifier
            .create(Mono.delay(Duration.ofMillis(100)))
            .expectSubscription()
            .thenAwait(Duration.ofMillis(100))
            .expectNextCount(1)
            .verifyComplete()
    }
}
