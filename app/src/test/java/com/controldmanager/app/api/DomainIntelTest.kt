package com.controldmanager.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainIntelTest {

    /** Dragonfly's report on play.googleapis.com, as the dashboard's "More info" received it. */
    private val playGoogleapis = """
        {"body":{"features":{
          "classification":{"categories":[
            {"confidence":1,"confidenceLabel":"high","dangerous":false,"id":1018,"name":"Infrastructure and CDNs",
             "reasoning":"Backend API endpoint for Google Play services, serving non-HTML data for app management and authentication.","sensitive":false},
            {"confidence":0.8,"confidenceLabel":"high","dangerous":false,"id":1080,"name":"SaaS and B2B",
             "reasoning":"Provides API services for Google Play developer tools and app management.","sensitive":false},
            {"confidence":0.6,"confidenceLabel":"medium","dangerous":false,"id":1003,"name":"Computers and Internet",
             "reasoning":"API for software and internet services related to Android app ecosystem.","sensitive":false}],
            "collectedAt":"2026-03-09T13:35:11.527769231Z"},
          "dns":{"collectedAt":"2026-03-09T13:35:00.554866209Z","records":{"a":[{"ttl":300,"value":"216.239.38.223"}],"aaaa":[{"ttl":164,"value":"2001:4860:4802:34::223"}]}},
          "geoip":{"collectedAt":"2026-03-09T13:35:01.179758981Z","ipLocations":{"216.239.38.223":{"asn":"15169","country":"United States","latitude":37.751,"longitude":-97.822,"organization":"Google LLC"}}},
          "http":{"collectedAt":"2026-03-09T13:35:00.732048139Z","contentLength":1561,"contentType":"text/html; charset=UTF-8","finalUrl":"http://play.googleapis.com","statusCode":404},
          "netlog":{},
          "screenshot":{},
          "tls":{"collectedAt":"2026-03-09T13:35:01.433956744Z","issuer":"O=Google Trust Services, C=US","supportedProtocols":"TLS 1.3","validFrom":"2026-02-02T08:38:01Z","validUntil":"2026-04-27T08:38:00Z"},
          "whois":{"collectedAt":"2026-03-09T13:35:00.710841331Z","parsed":{}}},
         "status":"complete"},
         "success":true}
    """.trimIndent()

    /** The sections popular sites also have (shapes as Dragonfly sends them for netflix.com). */
    private val withWhoisNetlogScreenshot = """
        {"body":{"features":{
          "classification":{"categories":[{"confidence":0.95,"confidenceLabel":"high","dangerous":true,"id":2001,"name":"Phishing",
             "reasoning":"Imitates a bank sign-in page.","sensitive":false}]},
          "netlog":{"collectedAt":"2026-10-09T13:55:44.216240317Z","topDomain":"www.google.com","total":117,"types":["API","Document"]},
          "screenshot":{"collectedAt":"2026-10-09T13:55:39.561865383Z","screenshotUrl":"https://api.dragonfly.controld.com/screenshots/netflix.com.png"},
          "tls":{"issuer":"CN=DigiCert Global G3 TLS ECC SHA384 2020 CA1, O=DigiCert Inc, C=US","supportedProtocols":"TLS 1.3","validUntil":"2027-02-18T21:41:48Z"},
          "whois":{"collectedAt":"2026-10-09T13:55:38.628254224Z","parsed":{"creationDate":"1997-11-11T05:00:00Z","expirationDate":"2025-11-10T05:00:00Z",
             "lastUpdated":"2023-10-09T11:01:36Z","registrant":{"email":"select request email form","name":"","organization":"Netflix, Inc."},"registrar":"MarkMonitor Inc."}}},
         "status":"complete"},
         "success":true}
    """.trimIndent()

    private fun report(json: String) = parseDomainIntel(JSONObject(json))

    @Test
    fun `a classified domain lists its categories with confidence and reasoning`() {
        val categories = report(playGoogleapis)!!.categories
        assertEquals(listOf("Infrastructure and CDNs", "SaaS and B2B", "Computers and Internet"), categories.map { it.name })
        assertEquals(listOf(1.0, 0.8, 0.6), categories.map { it.confidence })
        assertEquals(listOf("high", "high", "medium"), categories.map { it.label })
        assertEquals("Provides API services for Google Play developer tools and app management.", categories[1].reasoning)
    }

    @Test
    fun `a dangerous category is flagged`() {
        assertTrue(report(withWhoisNetlogScreenshot)!!.categories.single().dangerous)
    }

    @Test
    fun `website and TLS details are read`() {
        val intel = report(playGoogleapis)!!
        assertEquals(IntelHttp(404, "http://play.googleapis.com", "text/html; charset=UTF-8"), intel.http)
        assertEquals("TLS 1.3", intel.tls!!.protocols)
        assertEquals("2026-04-27T08:38:00Z", intel.tls!!.validUntil)
    }

    @Test
    fun `the certificate issuer is its organization`() {
        assertEquals("Google Trust Services", report(playGoogleapis)!!.tls!!.issuerName)
        assertEquals("DigiCert Inc", report(withWhoisNetlogScreenshot)!!.tls!!.issuerName)
        assertEquals("Example CA", IntelTls("TLS 1.2", "CN=Example CA", "").issuerName)
    }

    @Test
    fun `sections Dragonfly has no data for are left out`() {
        val intel = report(playGoogleapis)!!
        assertNull(intel.whois)
        assertNull(intel.netlog)
        assertNull(intel.screenshotUrl)
    }

    @Test
    fun `whois, network log and screenshot are read when present`() {
        val intel = report(withWhoisNetlogScreenshot)!!
        assertEquals(IntelWhois("MarkMonitor Inc.", "Netflix, Inc.", "1997-11-11T05:00:00Z", "2025-11-10T05:00:00Z"), intel.whois)
        assertEquals(IntelNetlog(117, "www.google.com"), intel.netlog)
        assertEquals("https://api.dragonfly.controld.com/screenshots/netflix.com.png", intel.screenshotUrl)
    }

    @Test
    fun `a domain Dragonfly hasn't classified has no report`() {
        assertNull(report("""{"error":{"code":404,"message":"domain has not been classified"},"success":false}"""))
    }

    @Test
    fun `a name Dragonfly won't look at has no report`() {
        // Its answer for names such as _dmarc.google.com or _dns-sd._udp.local, common in a DNS log.
        assertNull(report("""{"success":false,"error":{"code":400,"message":"invalid domain"}}"""))
    }

    @Test
    fun `any other Dragonfly error is an error, not an unclassified domain`() {
        val e = assertThrows(ApiException::class.java) { report("""{"error":{"code":500,"message":"internal error"},"success":false}""") }
        assertEquals(500, e.code)
    }
}
