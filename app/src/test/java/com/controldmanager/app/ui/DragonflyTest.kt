package com.controldmanager.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragonflyTest {

    @Test
    fun `a complete report has no note`() {
        assertNull(intelStatusNote("complete"))
    }

    @Test
    fun `a report still being collected says so`() {
        assertTrue(intelStatusNote("in_progress")!!.contains("still"))
    }

    @Test
    fun `a report whose checks failed doesn't claim Dragonfly is still looking`() {
        // Dragonfly answers "error" when one of its checks failed; what it did collect is still shown.
        val note = intelStatusNote("error")!!
        assertFalse(note.contains("still"))
        assertTrue(note.contains("missing"))
    }

    @Test
    fun `a status Dragonfly might add later gets no note`() {
        assertNull(intelStatusNote(""))
        assertNull(intelStatusNote("queued"))
    }

    @Test
    fun `a report still being collected isn't kept, so the finished one shows next time`() {
        assertFalse(keepInCache(intel("in_progress")))
        assertTrue(keepInCache(intel("complete")))
        assertTrue(keepInCache(intel("error")))
    }

    @Test
    fun `no report is kept too, so the same name isn't asked about again`() {
        assertTrue(keepInCache(null))
    }

    private fun intel(status: String) = com.controldmanager.app.api.DomainIntel(status, emptyList(), null, null, null, null, null)
}
