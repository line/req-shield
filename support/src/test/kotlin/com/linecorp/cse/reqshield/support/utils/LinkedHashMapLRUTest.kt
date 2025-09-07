/*
 * Test to demonstrate LinkedHashMap's automatic LRU behavior
 */
package com.linecorp.cse.reqshield.support.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LinkedHashMapLRUTest {
    @Test
    fun `LinkedHashMap with accessOrder=true automatically maintains LRU order`() {
        // Create LinkedHashMap with accessOrder=true
        val lruMap =
            object : LinkedHashMap<String, Int>(3, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
                    return size > 2 // Keep only 2 items
                }
            }

        // Add items
        lruMap["A"] = 1 // Order: A
        lruMap["B"] = 2 // Order: A -> B

        // Access A (this should move A to the end automatically!)
        val valueA = lruMap["A"] // Order becomes: B -> A
        assertEquals(1, valueA)

        // Add C (should evict B, not A, because A was recently accessed)
        lruMap["C"] = 3 // Order: A -> C (B is evicted!)

        // Verify that B was evicted (LRU), not A
        assertEquals(1, lruMap["A"]) // A still exists (recently accessed)
        assertNull(lruMap["B"]) // B was evicted (least recently used)
        assertEquals(3, lruMap["C"]) // C exists (just added)

        println("Final map contents: ${lruMap.keys}") // Should be [A, C]
    }

    @Test
    fun `Compare accessOrder true vs false`() {
        // accessOrder = false (insertion order)
        val insertionOrder = LinkedHashMap<String, Int>(16, 0.75f, false)

        // accessOrder = true (access order - LRU)
        val accessOrder = LinkedHashMap<String, Int>(16, 0.75f, true)

        // Add same items to both
        listOf(insertionOrder, accessOrder).forEach { map ->
            map["A"] = 1
            map["B"] = 2
            map["C"] = 3
        }

        // Access A in both maps
        insertionOrder["A"]
        accessOrder["A"]

        println("Insertion order keys: ${insertionOrder.keys}") // [A, B, C] - unchanged
        println("Access order keys: ${accessOrder.keys}") // [B, C, A] - A moved to end!
    }
}
