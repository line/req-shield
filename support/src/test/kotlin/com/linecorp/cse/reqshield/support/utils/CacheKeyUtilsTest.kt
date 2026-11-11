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

    @Test
    fun emptyTextShouldDifferFromNoElement() {
        assertNotEquals(keyOf(emptyList<String>()), keyOf(listOf("")))
        assertNotEquals(keyOf("t", emptyList<String>()), keyOf("t", listOf("")))
        assertEquals("SimpleKey [t, \\e]", keyOf("t", ""))
        assertEquals("[\\e, \\e]", keyOf(listOf("", "")))
    }

    @Test
    fun everyPrimitiveArrayShouldBeWrittenByContent() {
        assertEquals("[true, false]", keyOf(booleanArrayOf(true, false)))
        assertEquals("[1, 2]", keyOf(byteArrayOf(1, 2)))
        assertEquals("[a, b]", keyOf(charArrayOf('a', 'b')))
        assertEquals("[1, 2]", keyOf(shortArrayOf(1, 2)))
        assertEquals("[1, 2]", keyOf(longArrayOf(1, 2)))
        assertEquals("[1.5, 2.5]", keyOf(floatArrayOf(1.5f, 2.5f)))
        assertEquals("[1.5, 2.5]", keyOf(doubleArrayOf(1.5, 2.5)))
    }

    @Test
    fun everySeparatorInsideANestedValueShouldBeEscaped() {
        assertEquals("SimpleKey [\\,\\[\\]\\{\\}\\=\\\\, x]", keyOf(",[]{}=\\", "x"))
        // A map entry whose key holds the '=' must differ from the entry it would otherwise spell
        assertNotEquals(keyOf(mapOf("a=b" to "c")), keyOf(mapOf("a" to "b=c")))
        assertNotEquals(keyOf(mapOf("a" to "{b}")), keyOf(mapOf("a" to mapOf<String, String>())))
    }

    @Test
    fun containerThatHoldsItselfShouldBeRejectedInsteadOfOverflowingTheStack() {
        val self = ArrayList<Any>()
        self.add(self)

        val error = assertThrows(IllegalArgumentException::class.java) { keyOf(self) }

        assertTrue(error.message!!.contains("nested"), error.message)
    }

    /**
     * Every argument list of one signature over a small alphabet of awkward texts. The alphabet holds each character
     * and marker the encoder gives a meaning, so a missing escape shows up as two lists with one key.
     */
    @Test
    fun argumentListsOfOneSignatureShouldNeverShareAKey() {
        val texts: List<String?> =
            listOf(null, "", "a", " ", ",", ", ", "[", "]", "[]", "{", "}", "=", "\\", "\\0", "\\e", "0", "e", "null", "a, b", "a=b")
        val lists: List<List<String?>?> =
            listOf<List<String?>?>(null, emptyList()) + texts.map { listOf(it) } + texts.flatMap { a -> texts.map { b -> listOf(a, b) } }
        val fewTexts = listOf(null, "", "a", ",", "[", "]", "\\", "\\e")
        val nestedLists: List<List<List<String?>?>> =
            listOf<List<List<String?>?>>(emptyList()) +
                listOf(null, emptyList(), listOf("a"), listOf(""), listOf("a", "a"), listOf("["), listOf("]"), listOf(","))
                    .let { inner -> inner.map { listOf(it) } + inner.flatMap { a -> inner.map { b -> listOf(a, b) } } }
        val maps: List<Map<String?, String?>> =
            listOf<Map<String?, String?>>(emptyMap()) +
                fewTexts.flatMap { k -> fewTexts.map { v -> mapOf(k to v) } } +
                listOf(linkedMapOf("a" to "b", "c" to "d"), mapOf("a" to "b, c=d"), mapOf("a, c" to "d"))

        fun assertDistinct(
            signature: String,
            argumentLists: List<Array<out Any?>>,
        ) {
            val byKey = argumentLists.groupBy { cacheKeyOfArguments(it) }
            val shared = byKey.entries.firstOrNull { it.value.size > 1 }
            assertTrue(shared == null) { "$signature: ${shared!!.value.map { it.contentDeepToString() }} share '${shared.key}'" }
        }

        assertDistinct("(text)", texts.map { arrayOf(it) })
        assertDistinct("(list)", lists.map { arrayOf(it) })
        assertDistinct("(list of lists)", nestedLists.map { arrayOf(it) })
        assertDistinct("(map)", maps.map { arrayOf(it) })
        assertDistinct("(text, text)", texts.flatMap { a -> texts.map { b -> arrayOf(a, b) } })
        assertDistinct("(text, list)", texts.flatMap { a -> lists.map { b -> arrayOf(a, b) } })
        assertDistinct("(list, text)", lists.flatMap { a -> texts.map { b -> arrayOf(a, b) } })
        assertDistinct("(list of lists, text)", nestedLists.flatMap { a -> fewTexts.map { b -> arrayOf(a, b) } })
        assertDistinct("(map, text)", maps.flatMap { a -> fewTexts.map { b -> arrayOf(a, b) } })
        assertDistinct("(text, text, text)", fewTexts.flatMap { a -> fewTexts.flatMap { b -> fewTexts.map { c -> arrayOf(a, b, c) } } })
    }
}
