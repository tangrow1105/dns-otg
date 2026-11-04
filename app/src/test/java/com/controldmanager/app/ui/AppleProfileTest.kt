package com.controldmanager.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleProfileTest {
    private val profile = appleDnsProfile("Mum's <iPhone> & iPad", "https://dns.controld.com/abc123xyz", "abc123xyz")

    @Test
    fun `points the device at the endpoint's DNS-over-HTTPS address`() {
        assertTrue(profile.contains("<string>HTTPS</string>"))
        assertTrue(profile.contains("<string>https://dns.controld.com/abc123xyz</string>"))
        assertTrue(profile.contains("<string>com.apple.dnsSettings.managed</string>"))
    }

    @Test
    fun `endpoint names can't break the file`() {
        assertTrue(profile.contains("Mum's &lt;iPhone&gt; &amp; iPad"))
        assertFalse(profile.contains("<iPhone>"))
    }

    @Test
    fun `is a plist from the first byte`() {
        assertTrue(profile.startsWith("<?xml"))
    }
}
