package com.controldmanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtlCacheTest {
    private var now = 0L
    private val cache = TtlCache<String, String>(maxEntries = 2, ttlMs = 1_000) { now }

    @Test
    fun `a stored value is returned within its lifetime`() {
        cache.put("a", "A")
        now = 999
        assertEquals("A", cache.get("a"))
    }

    @Test
    fun `a value is forgotten once its lifetime is over`() {
        cache.put("a", "A")
        now = 1_000
        assertNull(cache.get("a"))
    }

    @Test
    fun `when full, the value used longest ago makes room`() {
        cache.put("a", "A")
        cache.put("b", "B")
        cache.get("a")
        cache.put("c", "C")
        assertNull(cache.get("b"))
        assertEquals("A", cache.get("a"))
        assertEquals("C", cache.get("c"))
    }
}
