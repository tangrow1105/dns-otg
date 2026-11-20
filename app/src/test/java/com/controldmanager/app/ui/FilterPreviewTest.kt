package com.controldmanager.app.ui

import com.controldmanager.app.api.Filter
import com.controldmanager.app.api.FilterChange
import com.controldmanager.app.api.FilterLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterPreviewTest {
    private val levels = listOf(FilterLevel("Relaxed", "ads_small", 0), FilterLevel("Balanced", "ads_medium", 1), FilterLevel("Strict", "ads", 0))
    private val ads = Filter("ads", "Ads & Trackers", "", 1, levels)

    @Test
    fun `the changes the dashboard previews`() {
        assertEquals(FilterChange(emptyList(), listOf("ads_medium"), false), filterChange(ads, null, targetOn = false))
        assertEquals(FilterChange(listOf("ads"), listOf("ads_medium"), true), filterChange(ads, levels[2], targetOn = true))
        assertEquals(FilterChange(listOf("ads_small"), listOf("ads_medium"), false), filterChange(ads, levels[0], targetOn = true))
        assertNull(filterChange(ads, levels[1], targetOn = true))
        val gambling = Filter("gambling", "Gambling", "", 0, emptyList())
        assertEquals(FilterChange(listOf("gambling"), emptyList(), true), filterChange(gambling, null, targetOn = true))
    }

    @Test
    fun `only filters whose every level can be simulated get a preview, never Malware`() {
        assertTrue(canPreview(ads, setOf("ads_small", "ads_medium", "ads")))
        assertFalse(canPreview(ads, setOf("ads_small", "ads_medium")))
        assertFalse(canPreview(Filter("malware", "Malware", "", 1, emptyList()), setOf("malware")))
        assertFalse(canPreview(ads, emptySet()))
    }
}
