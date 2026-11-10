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

package com.linecorp.cse.reqshield.support.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CacheKeyUtilsTest {
    private class NoToString(
        val text: String,
    ) {
        override fun equals(other: Any?) = other is NoToString && other.text == text

        override fun hashCode() = text.hashCode()
    }

    private enum class Color { RED }

    private class EmptyIterableValue(
        val text: String,
    ) : Iterable<Any> {
        override fun iterator() = emptyList<Any>().iterator()

        override fun toString() = text
    }

    private fun keyOf(vararg args: Any?) = cacheKeyOfArguments(args)

    @Test
    fun ordinaryValuesShouldKeepTheSimpleKeyLayout() {
        assertEquals("SimpleKey []", keyOf())
        assertEquals("product-1", keyOf("product-1"))
        assertEquals("a,b [c]", keyOf("a,b [c]"))
        assertEquals("42", keyOf(42L))
        assertEquals("RED", keyOf(Color.RED))
        assertEquals("SimpleKey [tenant, 7, true]", keyOf("tenant", 7, true))
    }

    @Test
    fun separatorInsideAValueShouldNotMergeTwoArgumentLists() {
        assertNotEquals(keyOf("a, b", "c"), keyOf("a", "b, c"))
        assertEquals("SimpleKey [a\\, b, c]", keyOf("a, b", "c"))
    }

    @Test
    fun nullShouldDifferFromTheTextNull() {
        assertNotEquals(keyOf(null, "x"), keyOf("null", "x"))
        assertNotEquals(keyOf(null as Any?), keyOf("null"))
        // A text that spells the null marker is escaped, so it cannot reach the entry of a null argument
        assertNotEquals(keyOf(null as Any?), keyOf("\\0"))
        assertNotEquals(keyOf(null, "x"), keyOf("\\0", "x"))
    }

    @Test
    fun arraysAndCollectionsShouldBeWrittenByContent() {
        assertEquals("[a, b]", keyOf(arrayOf("a", "b")))
        assertEquals("[1, 2]", keyOf(intArrayOf(1, 2)))
        assertEquals(keyOf(listOf("a", "b")), keyOf(arrayOf("a", "b")))
        assertNotEquals(keyOf(listOf("a, b")), keyOf(listOf("a", "b")))
        assertNotEquals(keyOf(listOf("[a", "b]")), keyOf(listOf(listOf("a", "b"))))
        assertNotEquals(keyOf(listOf("a"), "b"), keyOf(listOf("a", "b"), null))
    }

    @Test
    fun iterableValueThatIsNotACollectionShouldKeepItsOwnText() {
        // A Path iterates over its own name elements, and a value type can be Iterable without being a list of parts
        assertEquals("/tmp/a", keyOf(Paths.get("/tmp/a")))
        assertEquals("SimpleKey [/tmp/a, x]", keyOf(Paths.get("/tmp/a"), "x"))
        assertEquals("1..3", keyOf(1..3))
        assertNotEquals(keyOf(EmptyIterableValue("a")), keyOf(EmptyIterableValue("b")))
    }

    @Test
    fun mapsShouldBeWrittenByContent() {
        assertEquals("{a=1, b=\\0}", keyOf(linkedMapOf("a" to 1, "b" to null)))
        assertNotEquals(keyOf(mapOf("a" to "1, b=2")), keyOf(linkedMapOf("a" to "1", "b" to "2")))
    }

    @Test
    fun valueWithoutItsOwnToStringShouldBeRejectedAtEveryDepth() {
        val messages =
            listOf<Array<out Any?>>(
                arrayOf(NoToString("Aa")),
                arrayOf("tenant", NoToString("Aa")),
                arrayOf(listOf(NoToString("Aa"))),
                arrayOf(mapOf("k" to NoToString("Aa"))),
            ).map { args -> assertThrows(IllegalArgumentException::class.java) { cacheKeyOfArguments(args) }.message!! }

        assertTrue(messages.all { it.contains("NoToString") && it.contains("toString()") }, messages.toString())
    }

    @Test
    fun requireOwnToStringShouldAcceptValuesThatDescribeThemselves() {
        requireOwnToString("text")
        requireOwnToString(1)
        requireOwnToString(Color.RED)
        requireOwnToString(listOf(1))
        assertThrows(IllegalArgumentException::class.java) { requireOwnToString(Any()) }
        assertThrows(IllegalArgumentException::class.java) { requireOwnToString(arrayOf("a")) }
    }
}
