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
private const val EMPTY_TEXT_MARKER = "\\e"
private const val NESTED_SPECIAL_CHARS = ",[]{}="

/** Deeper than any real argument; reached only by a container that holds itself. */
private const val MAX_NESTING_DEPTH = 32

/**
 * Builds the cache key of a method call from its arguments when the annotation names neither a key nor a key generator.
 *
 * The layout is the one of Spring's `SimpleKeyGenerator`: no argument gives `SimpleKey []`, one argument gives its
 * `toString()`, and several give `SimpleKey [a, b]`. The text of a value is changed where a plain `toString()` would
 * let two argument lists share a key:
 * - inside `SimpleKey [...]`, an array, a collection or a map, the characters `,` `[` `]` `{` `}` `=` and the backslash
 *   are escaped with a backslash, and an empty text is written as `\e`;
 * - a single argument escapes only the backslash;
 * - `null` is written as `\0`;
 * - arrays, collections and maps are written by content.
 *
 * Two argument lists of the same parameter types then give different keys, as long as the `toString()` of each value
 * tells its values apart. A class whose `toString()` joins free text (a data class with two text fields) does not,
 * and neither does a parameter typed `Any` that takes the text `[a, b]` in one call and a list in another.
 *
 * @throws IllegalArgumentException when a value's class does not override `toString()`, or a container holds itself.
 */
fun cacheKeyOfArguments(args: Array<out Any?>): String =
    when (args.size) {
        0 -> "SimpleKey []"
        1 -> cacheKeyOfValue(args[0])
        else -> args.joinToString(prefix = "SimpleKey [", postfix = "]") { encodeKeyPart(it, nested = true, depth = 1) }
    }

/**
 * The key text of one value, with the rules of [cacheKeyOfArguments] for a single argument: an array, a collection
 * or a map is written by content, and any other value is its `toString()`.
 */
fun cacheKeyOfValue(value: Any?): String = encodeKeyPart(value, nested = false, depth = 0)

/**
 * Rejects a key object whose `toString()` is the one of `Object`. That text holds only the class name and the hash code,
 * so two different keys can share it and two equal keys can differ.
 *
 * Only the class of [keyPart] itself is checked. An object that prints another object inside its own `toString()`
 * (a `Pair` of one, a data class with such a field) passes, and its key then holds that hash code.
 */
fun requireOwnToString(keyPart: Any) {
    require(keyPart.javaClass.getMethod("toString").declaringClass != Any::class.java) {
        "${keyPart.javaClass.name} does not override toString(), so it cannot be part of a cache key. " +
            "Override toString(), or set a key or keyGenerator that gives a text."
    }
}

private fun encodeKeyPart(
    part: Any?,
    nested: Boolean,
    depth: Int,
): String =
    when (part) {
        null -> NULL_MARKER
        is Array<*> -> encodeElements(part.asIterable(), depth)
        is BooleanArray -> encodeElements(part.asIterable(), depth)
        is ByteArray -> encodeElements(part.asIterable(), depth)
        is CharArray -> encodeElements(part.asIterable(), depth)
        is ShortArray -> encodeElements(part.asIterable(), depth)
        is IntArray -> encodeElements(part.asIterable(), depth)
        is LongArray -> encodeElements(part.asIterable(), depth)
        is FloatArray -> encodeElements(part.asIterable(), depth)
        is DoubleArray -> encodeElements(part.asIterable(), depth)
        // Only a Collection: another Iterable (a Path, a range) is a value with its own text
        is Collection<*> -> encodeElements(part, depth)
        is Map<*, *> -> {
            requireNestingWithinLimit(depth)
            part.entries.joinToString(prefix = "{", postfix = "}") {
                "${encodeKeyPart(it.key, nested = true, depth + 1)}=${encodeKeyPart(it.value, nested = true, depth + 1)}"
            }
        }
        else -> {
            requireOwnToString(part)
            escape(part.toString(), nested)
        }
    }

private fun encodeElements(
    elements: Iterable<*>,
    depth: Int,
): String {
    requireNestingWithinLimit(depth)
    return elements.joinToString(prefix = "[", postfix = "]") { encodeKeyPart(it, nested = true, depth + 1) }
}

private fun requireNestingWithinLimit(depth: Int) {
    require(depth < MAX_NESTING_DEPTH) {
        "A cache key part is nested more than $MAX_NESTING_DEPTH levels deep. A collection or map that holds itself " +
            "cannot be part of a cache key."
    }
}

/**
 * A single argument keeps its text except for the backslash. A nested value also escapes the separators, and writes
 * an empty text as a marker so that a list of one empty text differs from an empty list.
 */
private fun escape(
    text: String,
    nested: Boolean,
): String {
    if (nested && text.isEmpty()) return EMPTY_TEXT_MARKER

    return buildString(text.length) {
        for (char in text) {
            if (char == ESCAPE || (nested && char in NESTED_SPECIAL_CHARS)) append(ESCAPE)
            append(char)
        }
    }
}
