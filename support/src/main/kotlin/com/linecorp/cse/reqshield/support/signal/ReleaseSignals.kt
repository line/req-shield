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

package com.linecorp.cse.reqshield.support.signal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The signals of the locks that requests of this JVM hold, by cache key. A request that got a lock registers a
 * one-shot signal here, and a request that waits for the work under that lock waits on the signal instead of
 * sleeping for the whole poll interval. The signal completes when the holder gives the lock back, so the waiter
 * reads the cache at once.
 *
 * It only shortens a wait. A waiter that finds no signal - the holder runs in another JVM, or the lock is not one
 * of this library - still polls at the fixed interval, and a waiter never trusts the signal: it reads the cache.
 *
 * A signal is completed only by the call that took it out of the map, so no signal that is still in the map is
 * complete, and a waiter can never spin on one.
 *
 * An entry stays until its holder releases the lock or the next holder of the same key registers. A holder whose
 * release never runs (an executor that drops its task without an exception, for example) leaves its entry behind
 * until the lock of that key is taken again.
 *
 * With [validMillis], a signal stands for its lock only for that time: the timeout of the lock. After it the holder
 * no longer holds the lock, so [find] completes the signal and answers null. Without it, the waiters of a holder
 * that is slower than its lock timeout would wait for work that another request may already take over.
 *
 * Each core module keeps one instance for creation locks and one for refresh locks. [S] is the signal type of the
 * module: each core module waits in its own way. [complete] wakes the waiters of a signal and must be safe to call
 * from any thread.
 */
class ReleaseSignals<S : Any>(
    /** The timeout of the locks of these signals, in milliseconds. Null: a signal stays until its release. */
    validMillis: Long? = null,
    private val complete: (S) -> Unit,
) {
    private class Entry<S>(
        val token: String,
        val signal: S,
        // Read only when the signals have a time limit
        val registeredAtNanos: Long,
    )

    private val validNanos: Long? = validMillis?.let(TimeUnit.MILLISECONDS::toNanos)

    // An entry lives only while a lock is held, so the map is almost empty. With the default 16 bins, holders
    // of different keys would contend for the same bins.
    private val entries = ConcurrentHashMap<String, Entry<S>>(INITIAL_CAPACITY)

    /**
     * Registers [signal] for the holder that got [token] for [key]. A signal that is still there belongs to a
     * holder that lost the lock without a release (its lock timed out), so its waiters are sent back to the cache.
     */
    fun register(
        key: String,
        token: String,
        signal: S,
    ) {
        val registeredAtNanos = if (validNanos == null) 0L else System.nanoTime()
        entries.put(key, Entry(token, signal, registeredAtNanos))?.let { complete(it.signal) }
    }

    /**
     * The signal of the holder that works on [key], or null when this JVM knows of none. A signal whose time limit
     * is over is completed here and not answered: its waiters read the cache and go on as after a release.
     */
    fun find(key: String): S? {
        val entry = entries[key] ?: return null
        if (validNanos != null && System.nanoTime() - entry.registeredAtNanos >= validNanos) {
            if (entries.remove(key, entry)) complete(entry.signal)
            return null
        }
        return entry.signal
    }

    /**
     * Completes the signal that [register] stored for [token]. A holder whose lock timed out does not touch the
     * signal of the holder that took the lock over.
     */
    fun release(
        key: String,
        token: String,
    ) {
        val entry = entries[key] ?: return
        if (entry.token == token && entries.remove(key, entry)) {
            complete(entry.signal)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
