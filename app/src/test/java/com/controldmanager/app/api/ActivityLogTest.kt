package com.controldmanager.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogTest {

    /** One query as the analytics API sends it (same fields in the log and the live stream). */
    private fun query(timestamp: String, domain: String = "ms4.applovin.com", rrType: String = "AAAA", extra: String = "") =
        parseLogEntry(
            JSONObject(
                """
                {"timestamp":"$timestamp","userId":"159932abcdef","endpointId":"xqois2abcd","endpointName":"Car",
                 "profileId":"123456abcdef","question":"$domain.","rrType":"$rrType","sourceIp":"203.0.113.7",
                 "statusCode":0,"protocol":"doh","action":0,"trigger":"filter","triggerValue":"ads_medium"$extra,
                 "sourceGeoip":{"countryCode":"HR","city":"Zagreb","isp":"Example ISP","asn":64500},
                 "categories":[{"categoryId":1,"categoryName":"Advertising","confidence":0.9}]}
                """.trimIndent()
            )
        )

    @Test
    fun `a streamed query's local-time stamp is stored in UTC`() {
        assertEquals("2026-10-12T18:53:21.850Z", query("2026-10-12T14:53:21.85-04:00").timestamp)
    }

    @Test
    fun `a logged query's UTC stamp is stored in the same form as a streamed one`() {
        assertEquals("2026-10-12T18:53:21.850Z", query("2026-10-12T18:53:21.85Z").timestamp)
    }

    @Test
    fun `an unreadable stamp is kept as it came`() {
        assertEquals("yesterday", query("yesterday").timestamp)
    }

    @Test
    fun `streamed queries already on the loaded page are dropped`() {
        // The screen numbers its rows, so a listed row never equals a fresh one field for field.
        val listed = listOf(
            query("2026-10-12T18:53:21.85Z").copy(seq = 7),
            query("2026-10-12T18:53:20.1Z", "reddit.com", "A").copy(seq = 8),
        )
        val arrived = listOf(query("2026-10-12T14:53:21.85-04:00"), query("2026-10-12T14:53:22.5-04:00", "netflix.com", "A"))
        assertEquals(listOf("netflix.com"), notYetListed(listed, arrived).map { it.domain })
    }

    @Test
    fun `the same query twice in one millisecond keeps the copy the page lacks`() {
        val listed = listOf(query("2026-10-12T18:53:21.85Z"))
        val arrived = listOf(query("2026-10-12T14:53:21.85-04:00"), query("2026-10-12T14:53:21.85-04:00"))
        assertEquals(1, notYetListed(listed, arrived).size)
    }

    @Test
    fun `a listed query with answers matches the streamed one without them`() {
        val listed = listOf(query("2026-10-12T18:53:21.85Z", extra = ""","answers":[{"ips":["192.0.2.1"]}]"""))
        assertEquals(emptyList<LogEntry>(), notYetListed(listed, listOf(query("2026-10-12T14:53:21.85-04:00"))))
    }

    @Test
    fun `every Activity log filter becomes a query parameter`() {
        val filters = LogQuery(
            endpointId = "e1", clientId = "c1", action = 1, protocol = "doh", statusCode = 3, rrType = "AAAA",
            trigger = "filter" to "ads_medium", spoofTarget = "LHR", search = " netflix ",
        )
        assertEquals(
            mapOf(
                "endpointId" to "e1", "clientId" to "c1", "action" to "1", "protocol[]" to "doh", "statusCode" to "3",
                "rrType" to "AAAA", "trigger" to "filter", "triggerValue" to "ads_medium", "spoofTarget" to "LHR",
                "searchQuestion" to "netflix",
            ),
            filters.params().toMap(),
        )
    }

    @Test
    fun `no filters means no parameters`() {
        assertEquals(emptyList<Pair<String, String>>(), LogQuery(search = "  ").params())
    }
}
