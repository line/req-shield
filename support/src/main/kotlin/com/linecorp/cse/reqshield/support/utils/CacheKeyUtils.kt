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

private const val ESCAPE = '\\'
private const val NULL_MARKER = "\\0"
private const val NESTED_SPECIAL_CHARS = ",[]{}="

/**
 * Builds the cache key of a method call from its arguments when the annotation names neither a key nor a key generator.
 *
 * The layout follows Spring's `SimpleKeyGenerator` for ordinary values, so existing entries keep their key:
 * no argument gives `SimpleKey []`, one argument gives its `toString()`, and several give `SimpleKey [a, b]`.
 * Unlike a plain `toString()`, different argument lists of one method never give the same key:
 * the separator characters inside a value are escaped with a backslash, `null` is written as `\0`,
 * and arrays, collections and maps are written by content.
 *
 * @throws IllegalArgumentException when a value's class does not override `toString()`.
 */
fun cacheKeyOfArguments(args: Array<out Any?>): String =
    when (args.size) {
        0 -> "SimpleKey []"
        1 -> encodeKeyPart(args[0], nested = false)
        else -> args.joinToString(prefix = "SimpleKey [", postfix = "]") { encodeKeyPart(it, nested = true) }
    }

/**
 * Rejects a key object whose `toString()` is the one of `Object`. That text holds only the class name and the hash code,
 * so two different keys can share it and two equal keys can differ.
 */
fun requireOwnToString(keyPart: Any) {
    require(keyPart.javaClass.getMethod("toString").declaringClass != Any::class.java) {
        "${keyPart.javaClass.name} does not override toString(), so it cannot be part of a cache key. " +
            "Set the key or keyGenerator attribute, or override toString()."
    }
}

private fun encodeKeyPart(
    part: Any?,
    nested: Boolean,
): String =
    when (part) {
        null -> NULL_MARKER
        is Array<*> -> encodeElements(part.asIterable())
        is BooleanArray -> encodeElements(part.asIterable())
        is ByteArray -> encodeElements(part.asIterable())
        is CharArray -> encodeElements(part.asIterable())
        is ShortArray -> encodeElements(part.asIterable())
        is IntArray -> encodeElements(part.asIterable())
        is LongArray -> encodeElements(part.asIterable())
        is FloatArray -> encodeElements(part.asIterable())
        is DoubleArray -> encodeElements(part.asIterable())
        is Iterable<*> -> encodeElements(part)
        is Map<*, *> ->
            part.entries.joinToString(prefix = "{", postfix = "}") {
                "${encodeKeyPart(it.key, nested = true)}=${encodeKeyPart(it.value, nested = true)}"
            }
        else -> {
            requireOwnToString(part)
            escape(part.toString(), nested)
        }
    }

private fun encodeElements(elements: Iterable<*>): String =
    elements.joinToString(prefix = "[", postfix = "]") { encodeKeyPart(it, nested = true) }

/** A single argument keeps its text except for the backslash; a nested value also escapes the separators. */
private fun escape(
    text: String,
    nested: Boolean,
): String =
    buildString(text.length) {
        for (char in text) {
            if (char == ESCAPE || (nested && char in NESTED_SPECIAL_CHARS)) append(ESCAPE)
            append(char)
        }
    }
