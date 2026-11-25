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
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.exception.code.ErrorCode
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

/**
 * Client functions may fail before they ever return a publisher (e.g. a `require` at the top of the
 * annotated method). Such a failure must become an onError signal so that the cleanup already wired
 * into the chain - lock release and error mapping - actually runs.
 */
class ReqShieldSyncThrowingClientFunctionTest {
    private val value = Product("testValue", "testValue")
    private val cachedValue = Product("oldTestValue", "oldTestValue")
    private val timeToLiveMillis = 10000L

    // Long enough that a leaked lock cannot be released by its own expiration during the test.
    private val lockTimeoutMillis = 60000L

    private val throwingSupplier =
        Callable<Mono<Product?>> { throw IllegalStateException("supplier failed before returning a Mono") }

    /** Unique per test: KeyLocalLock keeps its lock map in a companion object shared by the whole JVM. */
    private fun isolatedKey(name: String) = "$name-${UUID.randomUUID()}"

    /** Cached entry that has passed the decisionForUpdate threshold (90% of its TTL). */
    private fun updateTargetReqShieldData(): ReqShieldData<Product> =
        ReqShieldData(
            cachedValue,
            ReqShieldData.Status.NEW,
            nowToEpochTime() - (timeToLiveMillis * 0.9).toLong(),
            timeToLiveMillis,
        )

    @Test
    fun `should release the update lock when the supplier throws synchronously`() {
        val key = isolatedKey("sync-throwing-supplier-update")
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val cached = updateTargetReqShieldData()
        val reqShield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    getCacheFunction = { Mono.just(cached) },
                    keyLock = keyLock,
                    scheduler = Schedulers.immediate(),
                ),
            )

        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, throwingSupplier, timeToLiveMillis))
            .expectNext(cached)
            .verifyComplete()

        assertNotNull(
            keyLock.tryLock(key, LockType.UPDATE).block(),
            "the failed background refresh must have released its update lock",
        )
    }

    @Test
    fun `should still serve the cached value in only create cache mode when the supplier throws synchronously`() {
        val key = isolatedKey("sync-throwing-supplier-only-create")
        val cached = updateTargetReqShieldData()
        val reqShield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    getCacheFunction = { Mono.just(cached) },
                    keyLock = KeyLocalLock(lockTimeoutMillis),
                    scheduler = Schedulers.immediate(),
                    reqShieldWorkMode = ReqShieldWorkMode.ONLY_CREATE_CACHE,
                ),
            )

        // The refresh is fire-and-forget: its failure must not reach the caller of a cache hit.
        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, throwingSupplier, timeToLiveMillis))
            .expectNext(cached)
            .verifyComplete()
    }

    @Test
    fun `should map a synchronously throwing get cache function to a get cache client exception`() {
        val key = isolatedKey("sync-throwing-get-cache")
        val getCacheInvocations = AtomicInteger(0)
        val reqShield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ -> Mono.just(true) },
                    getCacheFunction = {
                        getCacheInvocations.incrementAndGet()
                        throw IllegalStateException("cache read failed before returning a Mono")
                    },
                    keyLock = KeyLocalLock(lockTimeoutMillis),
                    scheduler = Schedulers.immediate(),
                ),
            )

        val result = reqShield.getAndSetReqShieldData(key, Callable { Mono.just<Product?>(value) }, timeToLiveMillis)

        assertEquals(0, getCacheInvocations.get(), "the returned Mono must not read the cache before subscription")

        StepVerifier
            .create(result)
            .expectErrorMatches { it is ClientException && it.errorCode == ErrorCode.GET_CACHE_ERROR }
            .verify()

        assertEquals(1, getCacheInvocations.get())
    }

    @Test
    fun `should return the supplier value and release the create lock when the set cache function throws synchronously`() {
        val key = isolatedKey("sync-throwing-set-cache")
        val keyLock = KeyLocalLock(lockTimeoutMillis)
        val reqShield =
            ReqShield(
                ReqShieldConfiguration<Product>(
                    setCacheFunction = { _, _, _ -> throw IllegalStateException("cache write failed before returning a Mono") },
                    getCacheFunction = { Mono.empty() },
                    keyLock = keyLock,
                    scheduler = Schedulers.immediate(),
                ),
            )

        // The cache write is fire-and-forget, so its failure must not fail the request either.
        StepVerifier
            .create(reqShield.getAndSetReqShieldData(key, Callable { Mono.just<Product?>(value) }, timeToLiveMillis))
            .assertNext { assertEquals(value, it.value) }
            .verifyComplete()

        assertNotNull(
            keyLock.tryLock(key, LockType.CREATE).block(),
            "the failed cache write must have released its create lock",
        )
    }
}
