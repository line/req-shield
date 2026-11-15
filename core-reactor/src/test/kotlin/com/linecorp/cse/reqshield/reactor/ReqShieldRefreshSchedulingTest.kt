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
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A refresh under a local lock asks for the lock on the thread of the request. So a read of an old entry hands work
 * to the scheduler only when it gets the lock, not on each read.
 */
class ReqShieldRefreshSchedulingTest {
    // The local locks and the signals are shared by all instances, so each test has its own key
    private val key = "refresh-scheduling-${UUID.randomUUID()}"
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()
    private val oldProduct = Product("old", "old")
    private val newProduct = Product("new", "new")
    private val timeout = Duration.ofSeconds(5)

    /** Counts the tasks and the workers that req-shield asks of the scheduler. */
    private class CountingScheduler(
        private val delegate: Scheduler,
    ) : Scheduler {
        val requests = AtomicInteger()

        override fun schedule(task: Runnable): Disposable {
            requests.incrementAndGet()
            return delegate.schedule(task)
        }

        override fun createWorker(): Scheduler.Worker {
            requests.incrementAndGet()
            return delegate.createWorker()
        }
    }

    private fun shield(
        scheduler: Scheduler,
        workMode: ReqShieldWorkMode = ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE,
        keyLock: KeyLock? = null,
        globalLock: Boolean = false,
    ): ReqShield<Product> {
        val set: (String, ReqShieldData<Product>, Long) -> Mono<Boolean> = { cacheKey, data, _ ->
            Mono.fromSupplier {
                cache[cacheKey] = data
                true
            }
        }
        val get: (String) -> Mono<ReqShieldData<Product>?> = { cacheKey -> Mono.justOrEmpty(cache[cacheKey]) }
        // A global lock that no test reaches: ONLY_CREATE_CACHE refreshes under a local lock of the JVM
        val lockFunction: ((String, String, Long) -> Mono<Boolean>)? = if (globalLock) ({ _, _, _ -> Mono.just(true) }) else null
        val unLockFunction: ((String, String) -> Mono<Boolean>)? = if (globalLock) ({ _, _ -> Mono.just(true) }) else null

        return ReqShield(
            if (keyLock == null) {
                ReqShieldConfiguration(
                    setCacheFunction = set,
                    getCacheFunction = get,
                    globalLockFunction = lockFunction,
                    globalUnLockFunction = unLockFunction,
                    isLocalLock = !globalLock,
                    scheduler = scheduler,
                    reqShieldWorkMode = workMode,
                )
            } else {
                ReqShieldConfiguration(
                    setCacheFunction = set,
                    getCacheFunction = get,
                    scheduler = scheduler,
                    keyLock = keyLock,
                    reqShieldWorkMode = workMode,
                )
            },
        )
    }

    /** An entry that is old enough for a refresh: 9 s of its 10 s are over, and the refresh starts at 80 %. */
    private fun oldEntry(): ReqShieldData<Product> =
        ReqShieldData(oldProduct, ReqShieldData.Status.NEW, System.currentTimeMillis() - 9_000, 10_000)

    private fun readsDuringARefreshShouldAskNothingOfTheScheduler(
        workMode: ReqShieldWorkMode,
        globalLock: Boolean,
    ) {
        cache[key] = oldEntry()
        val scheduler = CountingScheduler(Schedulers.boundedElastic())
        val finishRefresh = CompletableFuture<Product?>()
        val supplierCalls = AtomicInteger()
        val supplier =
            Callable<Mono<Product?>> {
                Mono.defer {
                    supplierCalls.incrementAndGet()
                    Mono.fromFuture(finishRefresh)
                }
            }
        val shield = shield(scheduler, workMode, globalLock = globalLock)

        try {
            // This read gets the lock and starts the refresh
            assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).block(timeout)?.value)
            await().atMost(Duration.ofSeconds(2)).until { supplierCalls.get() == 1 }
            val requestsOfTheRefresh = scheduler.requests.get()

            repeat(50) { assertEquals(oldProduct, shield.getAndSetReqShieldData(key, supplier, 10_000).block(timeout)?.value) }
            // A read that asked the scheduler would show here, after its task ran
            Thread.sleep(100)

            assertEquals(requestsOfTheRefresh, scheduler.requests.get(), "the reads during the refresh asked the scheduler")
            assertEquals(1, supplierCalls.get())
        } finally {
            finishRefresh.complete(newProduct)
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
        await().atMost(Duration.ofSeconds(2)).until { ReqShield.refreshSignals.find(key) == null }
    }

    @Test
    fun readsDuringARefreshUnderTheLocalLockShouldAskNothingOfTheScheduler() =
        readsDuringARefreshShouldAskNothingOfTheScheduler(ReqShieldWorkMode.CREATE_AND_UPDATE_CACHE, globalLock = false)

    @Test
    fun readsDuringARefreshOfOnlyCreateCacheShouldAskNothingOfTheSchedulerAlsoWithAGlobalLock() =
        readsDuringARefreshShouldAskNothingOfTheScheduler(ReqShieldWorkMode.ONLY_CREATE_CACHE, globalLock = true)

    @Test
    fun aCustomLockShouldBeAskedOnTheSchedulerAlsoWhenTheConfigurationSaysLocalLock() {
        cache[key] = oldEntry()
        val lockThread = AtomicReference<Thread?>()
        val localLock = KeyLocalLock(3000)
        // Not a KeyLocalLock: it may take time or answer on the thread of its client, as a lock in a store does
        val customLock =
            object : KeyLock {
                override fun tryLock(
                    key: String,
                    lockType: LockType,
                ): Mono<String> =
                    Mono.defer {
                        if (lockType == LockType.UPDATE) lockThread.set(Thread.currentThread())
                        localLock.tryLock(key, lockType)
                    }

                override fun unLock(
                    key: String,
                    lockType: LockType,
                    token: String,
                ): Mono<Boolean> = localLock.unLock(key, lockType, token)
            }
        // isLocalLock keeps its default, true
        val shield = shield(Schedulers.boundedElastic(), keyLock = customLock)

        assertEquals(oldProduct, shield.getAndSetReqShieldData(key, { Mono.just(newProduct) }, 10_000).block(timeout)?.value)

        await().atMost(Duration.ofSeconds(2)).until { lockThread.get() != null }
        assertNotSame(Thread.currentThread(), lockThread.get(), "the custom lock was asked on the thread of the request")
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(newProduct, cache[key]?.value) }
    }

    @Test
    fun theLockAndTheSignalShouldBeReleasedWhenTheSchedulerRejectsTheRefresh() {
        cache[key] = oldEntry()
        // A disposed scheduler rejects each task
        val rejecting = Schedulers.newSingle("rejecting").also { it.dispose() }
        val supplierCalls = AtomicInteger()
        val shield = shield(rejecting)

        val data =
            shield
                .getAndSetReqShieldData(
                    key,
                    {
                        supplierCalls.incrementAndGet()
                        Mono.just(newProduct)
                    },
                    10_000,
                ).block(timeout)

        // The caller has its data: only the refresh is skipped
        assertEquals(oldProduct, data?.value)
        await().atMost(Duration.ofSeconds(2)).until { ReqShield.refreshSignals.find(key) == null }
        // A lock that is still held would reject this attempt for the 3 s of its timeout
        await().atMost(Duration.ofSeconds(1)).untilAsserted {
            val token = KeyLocalLock(3000).tryLock(key, LockType.UPDATE).block(timeout)
            assertNotNull(token)
            assertTrue(KeyLocalLock(3000).unLock(key, LockType.UPDATE, token!!).block(timeout) == true)
        }
        assertEquals(0, supplierCalls.get())
    }
}
