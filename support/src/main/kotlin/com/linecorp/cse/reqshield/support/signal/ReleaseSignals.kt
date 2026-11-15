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
 * Each core module keeps one instance for creation locks and one for refresh locks. [S] is the signal type of the
 * module: each core module waits in its own way. [complete] wakes the waiters of a signal and must be safe to call
 * from any thread.
 */
class ReleaseSignals<S : Any>(
    private val complete: (S) -> Unit,
) {
    private class Entry<S>(
        val token: String,
        val signal: S,
    )

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
        entries.put(key, Entry(token, signal))?.let { complete(it.signal) }
    }

    /** The signal of the holder that works on [key], or null when this JVM knows of none. */
    fun find(key: String): S? = entries[key]?.signal

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
