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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ReleaseSignalsTest {
    /** A signal that counts how often it was completed. */
    private class Signal {
        val completions = AtomicInteger()
    }

    private val signals = ReleaseSignals<Signal> { it.completions.incrementAndGet() }

    @Test
    fun aRegisteredSignalShouldBeFoundAndNotComplete() {
        val signal = Signal()

        signals.register("key", "token", signal)

        assertSame(signal, signals.find("key"))
        assertEquals(0, signal.completions.get())
        assertNull(signals.find("other-key"))
    }

    @Test
    fun releaseShouldCompleteTheSignalOnceAndRemoveIt() {
        val signal = Signal()
        signals.register("key", "token", signal)

        signals.release("key", "token")
        signals.release("key", "token")

        assertEquals(1, signal.completions.get())
        assertNull(signals.find("key"))
    }

    @Test
    fun releaseWithTheTokenOfAnEarlierHolderShouldLeaveTheSignalOfTheNextHolder() {
        val earlier = Signal()
        val next = Signal()
        signals.register("key", "earlier-token", earlier)
        // The lock of the earlier holder timed out and the next holder took it over
        signals.register("key", "next-token", next)

        signals.release("key", "earlier-token")

        assertSame(next, signals.find("key"))
        assertEquals(0, next.completions.get())
    }

    @Test
    fun registerOverASignalThatWasNeverReleasedShouldCompleteIt() {
        val abandoned = Signal()
        val next = Signal()
        signals.register("key", "abandoned-token", abandoned)

        signals.register("key", "next-token", next)

        assertEquals(1, abandoned.completions.get())
        assertSame(next, signals.find("key"))
    }

    @Test
    fun releaseOfAKeyWithoutASignalShouldDoNothing() {
        signals.release("key", "token")

        assertNull(signals.find("key"))
    }

    @Test
    fun signalsOfDifferentKeysShouldNotAffectEachOther() {
        val first = Signal()
        val second = Signal()
        signals.register("first", "token", first)
        signals.register("second", "token", second)

        signals.release("first", "token")

        assertEquals(1, first.completions.get())
        assertEquals(0, second.completions.get())
        assertSame(second, signals.find("second"))
    }
}
