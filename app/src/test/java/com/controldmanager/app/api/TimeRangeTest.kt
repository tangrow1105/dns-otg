package com.controldmanager.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRangeTest {
    private val hour = 3_600_000L

    @Test
    fun `a preset ends when it is used, not when the app started`() {
        val before = System.currentTimeMillis()
        val day = TimeRange.presets[1]
        assertTrue(day.end >= before)
    }

    @Test
    fun `a picked day that is today grows up to now and keeps its start`() {
        val now = System.currentTimeMillis()
        val start = now - 9 * hour
        val picked = TimeRange("today", 1 * 3600, start + hour, custom = true, until = now + 10 * hour)
        val refreshed = picked.refreshed()
        assertTrue(refreshed.end > now - 1000)
        assertEquals(start, refreshed.end - refreshed.seconds * 1000)
        val again = refreshed.refreshed()
        assertEquals(start, again.end - again.seconds * 1000)
    }

    @Test
    fun `a picked range in the past stays as it was`() {
        val end = System.currentTimeMillis() - 48 * hour
        val picked = TimeRange("2 days ago", 24 * 3600, end, custom = true, until = end)
        val refreshed = picked.refreshed()
        assertEquals(end, refreshed.end)
        assertEquals(24 * 3600L, refreshed.seconds)
        assertEquals(picked.tick + 1, refreshed.tick)
    }
}
