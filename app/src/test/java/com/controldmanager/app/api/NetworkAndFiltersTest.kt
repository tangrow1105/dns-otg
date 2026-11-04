package com.controldmanager.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAndFiltersTest {

    /** Trimmed from a real GET /network body. */
    private val network = parseNetwork(JSONObject("""{
        "network":[
          {"iata_code":"ACC","city_name":"Accra","country_name":"GH","location":{"lat":5.55,"long":-0.2},"status":{"api":1,"dns":1,"pxy":-1}},
          {"iata_code":"AMS","city_name":"Amsterdam","country_name":"NL","location":{"lat":52.37,"long":4.89},"status":{"api":1,"dns":0,"pxy":1}},
          {"iata_code":"XSP","city_name":"Singapore","country_name":"SG","location":{"lat":1.4,"long":103.8},"status":{"api":1,"dns":1}}],
        "time":1793795554,"current_pop":"XSP"}"""))

    @Test
    fun `no proxy at a location isn't an outage, a service that's down is`() {
        assertTrue(network.locations.first { it.code == "ACC" }.healthy)
        assertFalse(network.locations.first { it.code == "AMS" }.healthy)
        assertEquals(listOf("AMS"), network.outages.map { it.code })
    }

    @Test
    fun `a missing service counts as not offered, and the serving location is known`() {
        assertEquals(-1, network.locations.first { it.code == "XSP" }.proxy)
        assertEquals("XSP", network.currentPop)
    }

    @Test
    fun `malware's stacked levels keep their type and the AI value, and the highest one on is the level`() {
        val f = parseFilter(JSONObject("""{"PK":"malware","name":"Malware","status":1,"levels":[
            {"title":"Relaxed","type":"filter","name":"malware","status":1},
            {"title":"Balanced","type":"ipfilter","name":"ip_malware","status":1},
            {"title":"Strict","type":"option","name":"ai_malware","status":0,"opt":[{"PK":"ai_malware","value":0.5}]}]}"""))
        assertEquals("ipfilter", f.levels[1].type)
        assertEquals("0.5", f.levels[2].optValue)
        assertEquals("Balanced", f.activeLevel?.title)
    }
}
