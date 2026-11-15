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
import com.linecorp.cse.reqshield.support.constant.ConfigValues.GLOBAL_CREATION_FRONT_KEY_PREFIX
import com.linecorp.cse.reqshield.support.exception.ClientException
import com.linecorp.cse.reqshield.support.model.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * With a global lock, one request of a JVM asks the lock store for the creation lock of a key. The other requests of
 * that JVM wait for that request, and do not call the lock store.
 */
class ReqShieldGlobalCreationFrontTest {
    /** The ReqShield that the test made last. Its signals and guard locks are the ones the test looks at. */
    private lateinit var created: ReqShield<Product>

    // The signals and the front locks are shared by all instances, so each test has its own key
    private val key = "global-front-${UUID.randomUUID()}"
    private val remoteLockKey = lockKeyOf(key, LockType.CREATE)
    private val cache = ConcurrentHashMap<String, ReqShieldData<Product>>()

    /** The lock store: lock key to token. */
    private val locks = ConcurrentHashMap<String, String>()
    private val lockCalls = AtomicInteger()
    private val unlockCalls = AtomicInteger()
    private val supplierCalls = AtomicInteger()
    private val cacheReads = AtomicInteger()

    // Runs the background cache writes of the shield
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Runs the requests, so a cancelled request does not cancel the test
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val remoteProduct = Product("remote", "remote")
    private val localProduct = Product("local", "local")

    @AfterEach
    fun tearDown() {
        callers.cancel()
        scope.cancel()
    }

    private fun shield(
        maxAttemptGetCache: Int = 60,
        lockFunction: suspend (String, String) -> Boolean = { lockKey, token -> locks.putIfAbsent(lockKey, token) == null },
    ): ReqShield<Product> =
        ReqShield(
            ReqShieldConfiguration(
                setCacheFunction = { cacheKey, data, _ ->
                    cache[cacheKey] = data
                    true
                },
                getCacheFunction = { cacheKey ->
                    cacheReads.incrementAndGet()
                    cache[cacheKey]
                },
                globalLockFunction = { lockKey, token, _ ->
                    lockCalls.incrementAndGet()
                    lockFunction(lockKey, token)
                },
                globalUnLockFunction = { lockKey, token ->
                    unlockCalls.incrementAndGet()
                    locks.remove(lockKey, token)
                },
                isLocalLock = false,
                maxAttemptGetCache = maxAttemptGetCache,
                scope = scope,
            ),
        ).also { created = it }

    private fun supplier(): suspend () -> Product? =
        {
            supplierCalls.incrementAndGet()
            localProduct
        }

    /** A request of another JVM holds the creation lock of the key. */
    private fun lockByAnotherJvm() {
        locks[remoteLockKey] = REMOTE_TOKEN
    }

    private fun unlockByAnotherJvm() {
        locks.remove(remoteLockKey, REMOTE_TOKEN)
    }

    private fun signal() = created.creationSignals.find(key)

    private suspend fun awaitUntil(condition: () -> Boolean) =
        withTimeout(2_000) {
            while (!condition()) delay(1)
        }

    private suspend fun awaitReads(count: Int) = awaitUntil { cacheReads.get() >= count }

    /** Waits until each task that the shield launched on its scope has ended. */
    private suspend fun awaitScopeTasks() = scope.coroutineContext.job.children.toList().forEach { it.join() }

    /** Starts [CALLERS] requests for the key at the same time. */
    private fun startCallers(
        shield: ReqShield<Product>,
        supplier: suspend () -> Product?,
    ): List<Deferred<ReqShieldData<Product>>> {
        val start = CompletableDeferred<Unit>()
        val requests =
            List(CALLERS) {
                callers.async {
                    start.await()
                    shield.getAndSetReqShieldData(key, supplier, 10_000)
                }
            }
        start.complete(Unit)
        return requests
    }

    /**
     * No front lock and no signal of the key is left: the next request for the key asks the lock store, gets the
     * lock, and gives it back. With a front lock left, that request would not ask the store.
     */
    private suspend fun assertTheNextRequestGetsTheLock(shield: ReqShield<Product>) {
        awaitScopeTasks()
        assertNull(signal())
        cache.remove(key)
        unlockByAnotherJvm()
        awaitUntil { locks.isEmpty() }
        val lockCallsBefore = lockCalls.get()
        val unlockCallsBefore = unlockCalls.get()
        val supplierCallsBefore = supplierCalls.get()

        val data = withTimeout(2_000) { shield.getAndSetReqShieldData(key, supplier(), 10_000) }

        assertEquals(localProduct, data.value)
        assertEquals(lockCallsBefore + 1, lockCalls.get())
        assertEquals(supplierCallsBefore + 1, supplierCalls.get())
        awaitScopeTasks()
        assertEquals(unlockCallsBefore + 1, unlockCalls.get())
        assertTrue(locks.isEmpty())
        assertNull(signal())
    }

    @Test
    fun requestsShouldAskTheStoreOnceAndGetTheEntryOfAnotherJvm() =
        runBlocking {
            lockByAnotherJvm()
            val shield = shield()

            val requests = startCallers(shield, supplier())
            // Each request reads before the front lock and once more in its wait, and the representative polls
            awaitReads(2 * CALLERS + 2)
            // The other JVM writes the entry, then gives its lock back
            cache[key] = ReqShieldData(remoteProduct, 10_000)
            unlockByAnotherJvm()

            requests.forEach { assertEquals(remoteProduct, withTimeout(2_000) { it.await() }.value) }
            assertEquals(1, lockCalls.get())
            assertEquals(0, unlockCalls.get())
            assertEquals(0, supplierCalls.get())
            assertTheNextRequestGetsTheLock(shield)
        }

    @Test
    fun requestsShouldAskTheStoreOnceWhenARequestOfThisJvmGetsTheLock() =
        runBlocking {
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            val shield = shield()

            val requests =
                startCallers(shield) {
                    supplierCalls.incrementAndGet()
                    supplierStarted.complete(Unit)
                    finishSupplier.await()
                    localProduct
                }
            withTimeout(2_000) { supplierStarted.await() }
            // The holder read before and after its lock, and each waiter read before the front lock and in its wait
            awaitReads(2 * CALLERS)
            finishSupplier.complete(Unit)

            requests.forEach { assertEquals(localProduct, withTimeout(2_000) { it.await() }.value) }
            assertEquals(1, supplierCalls.get())
            assertEquals(1, lockCalls.get())
            awaitScopeTasks()
            assertEquals(1, unlockCalls.get())
            assertTrue(locks.isEmpty())
            assertNull(signal())
        }

    @Test
    fun theFrontLockShouldBeFreeWhenItsSignalCompletes() =
        runBlocking {
            val supplierStarted = CompletableDeferred<Unit>()
            val finishSupplier = CompletableDeferred<Unit>()
            val shield = shield()
            val request =
                callers.async {
                    shield.getAndSetReqShieldData(
                        key,
                        {
                            supplierStarted.complete(Unit)
                            finishSupplier.await()
                            localProduct
                        },
                        10_000,
                    )
                }
            withTimeout(2_000) { supplierStarted.await() }
            val frontLockFreeAtSignal = CompletableDeferred<Boolean>()
            // The handler runs in the release, at the moment the signal completes. The probe never suspends.
            assertNotNull(signal()).invokeOnCompletion {
                val probe = created.creationFrontLock
                val frontKey = GLOBAL_CREATION_FRONT_KEY_PREFIX + key
                val token = runBlocking { probe.tryLock(frontKey, LockType.CREATE) }
                if (token != null) runBlocking { probe.unLock(frontKey, LockType.CREATE, token) }
                frontLockFreeAtSignal.complete(token != null)
            }

            finishSupplier.complete(Unit)

            assertEquals(localProduct, withTimeout(2_000) { request.await() }.value)
            // A waiter that wakes while the front lock is still held loses it and polls at 50 ms
            assertTrue(withTimeout(2_000) { frontLockFreeAtSignal.await() }, "the signal completed before the front lock was free")
            assertTheNextRequestGetsTheLock(shield)
        }

    @Test
    fun aWaiterShouldBeTheNextHolderRightAfterTheHolderOfThisJvmFailed() =
        runBlocking {
            val holderStarted = CompletableDeferred<Unit>()
            val failHolder = CompletableDeferred<Unit>()
            val shield = shield()
            val holder =
                callers.async {
                    assertFailsWith<ClientException> {
                        shield.getAndSetReqShieldData(
                            key,
                            {
                                holderStarted.complete(Unit)
                                failHolder.await()
                                error("the supplier of the holder failed")
                            },
                            10_000,
                        )
                    }
                }
            withTimeout(2_000) { holderStarted.await() }
            val waiter =
                callers.async {
                    val data = shield.getAndSetReqShieldData(key, supplier(), 10_000)
                    System.nanoTime() to data
                }
            // Holder: 2 reads. Waiter: 1 read before the front lock and 1 read in its wait.
            awaitReads(4)
            // A poll at 50 ms would read 45 ms after the failure, which is over the bound below
            delay(55)
            assertEquals(1, lockCalls.get())
            assertTrue(waiter.isActive, "the waiter ended before the holder failed")

            val failedAtNanos = System.nanoTime()
            failHolder.complete(Unit)

            val (endedAtNanos, data) = withTimeout(2_000) { waiter.await() }
            assertEquals(localProduct, data.value)
            val waitedMillis = (endedAtNanos - failedAtNanos) / 1_000_000
            // Not a poll interval of 50 ms: the release of the holder wakes the waiter
            assertTrue(waitedMillis < 40, "the waiter got its value $waitedMillis ms after the failure")
            withTimeout(2_000) { holder.await() }
            assertEquals(1, supplierCalls.get())
            assertEquals(2, lockCalls.get())
            awaitScopeTasks()
            assertEquals(2, unlockCalls.get())
            assertTrue(locks.isEmpty())
        }

    @Test
    fun theFrontLockShouldBeFreeAfterTheRequestThatReadsForTheJvmGaveUp() =
        runBlocking {
            lockByAnotherJvm()
            // Reads at 0, 5 and 15 ms, then each request calls the supplier without a lock
            val shield = shield(maxAttemptGetCache = 3)

            val requests = startCallers(shield, supplier())

            requests.forEach { assertEquals(localProduct, withTimeout(3_000) { it.await() }.value) }
            assertEquals(CALLERS, supplierCalls.get())
            // When the reading request gives up, a waiter takes its place and asks the store once more. So the count
            // is not 1 here, but no request asks more often than it reads.
            assertTrue(lockCalls.get() in 1..CALLERS * 3, "lock calls: ${lockCalls.get()}")
            assertEquals(0, unlockCalls.get())
            // A request that gave up writes no entry
            assertNull(cache[key])
            assertTheNextRequestGetsTheLock(shield)
        }

    @Test
    fun theFrontLockShouldBeFreeAfterTheLockStoreFailed() =
        runBlocking {
            val storeFails = AtomicBoolean(true)
            val shield =
                shield(lockFunction = { lockKey, token ->
                    if (storeFails.getAndSet(false)) throw IllegalStateException("the lock store is down")
                    locks.putIfAbsent(lockKey, token) == null
                })

            assertFailsWith<IllegalStateException> { shield.getAndSetReqShieldData(key, supplier(), 10_000) }

            assertEquals(0, supplierCalls.get())
            assertTheNextRequestGetsTheLock(shield)
        }

    @Test
    fun aCancelledRepresentativeShouldFreeTheFrontLockWhileItPolls() =
        runBlocking {
            lockByAnotherJvm()
            val shield = shield()
            val representative = callers.async { shield.getAndSetReqShieldData(key, supplier(), 10_000) }
            // 1 read before the front lock, then 2 reads of its poll
            awaitReads(3)
            val signalOfRepresentative = assertNotNull(signal())
            val waiter = callers.async { shield.getAndSetReqShieldData(key, supplier(), 10_000) }
            // The waiter loses the front lock and waits for the signal of the representative
            delay(20)
            assertEquals(1, lockCalls.get())
            assertTrue(waiter.isActive)

            representative.cancelAndJoin()

            // It ended with its cancellation, not with a failure
            assertFailsWith<CancellationException> { representative.await() }
            assertTrue(signalOfRepresentative.isCompleted)
            // The signal wakes the waiter, which takes the free front lock and asks the store. Without the signal,
            // or with the front lock still held, the waiter would poll at 50 ms until its 60 reads are used up.
            awaitUntil { lockCalls.get() == 2 }
            cache[key] = ReqShieldData(remoteProduct, 10_000)
            assertEquals(remoteProduct, withTimeout(2_000) { waiter.await() }.value)
            assertEquals(0, supplierCalls.get())
            assertTheNextRequestGetsTheLock(shield)
        }

    @Test
    fun aRepresentativeCancelledDuringTheLockCallShouldFreeTheFrontLock() =
        runBlocking {
            val lockCallStarted = CompletableDeferred<Unit>()
            val firstCall = AtomicBoolean(true)
            // The first call of the store gives no answer until its caller is cancelled
            val shield =
                shield(lockFunction = { lockKey, token ->
                    if (firstCall.getAndSet(false)) {
                        lockCallStarted.complete(Unit)
                        awaitCancellation()
                    }
                    locks.putIfAbsent(lockKey, token) == null
                })
            val representative = callers.async { shield.getAndSetReqShieldData(key, supplier(), 10_000) }
            withTimeout(2_000) { lockCallStarted.await() }
            val signalOfRepresentative = assertNotNull(signal())
            val waiter = callers.async { shield.getAndSetReqShieldData(key, supplier(), 10_000) }
            // Representative: 1 read. Waiter: 1 read before the front lock and 1 read in its wait.
            awaitReads(3)
            assertEquals(1, lockCalls.get())
            assertTrue(waiter.isActive)
            val unlockCallsBefore = unlockCalls.get()

            representative.cancelAndJoin()

            // It ended with its cancellation, not with a failure
            assertFailsWith<CancellationException> { representative.await() }
            assertTrue(signalOfRepresentative.isCompleted)
            // The signal wakes the waiter, which takes the free front lock, gets the lock and calls the supplier
            assertEquals(localProduct, withTimeout(2_000) { waiter.await() }.value)
            assertEquals(2, lockCalls.get())
            assertEquals(1, supplierCalls.get())
            awaitScopeTasks()
            // KeyGlobalLock released the token of the cancelled call, and the waiter released its lock after its write
            assertEquals(unlockCallsBefore + 2, unlockCalls.get())
            assertTheNextRequestGetsTheLock(shield)
        }

    private companion object {
        const val CALLERS = 20
        const val REMOTE_TOKEN = "token-of-another-jvm"
    }
}
