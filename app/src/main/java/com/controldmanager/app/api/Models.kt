package com.controldmanager.app.api

import org.json.JSONArray
import org.json.JSONObject

/** Rule action types used across custom rules, services, folders and the default rule. */
object Do {
    const val BLOCK = 0
    const val BYPASS = 1
    const val SPOOF = 2
    const val REDIRECT = 3

    fun label(d: Int) = when (d) {
        BLOCK -> "Block"
        BYPASS -> "Bypass"
        SPOOF -> "Spoof"
        REDIRECT -> "Redirect"
        else -> "None"
    }
}

data class Action(
    val doType: Int, val via: String? = null, val viaV6: String? = null, val status: Int = 1,
    /** Custom-rule expiry, unix seconds (dashboard's timer); -1 when saving means "remove the expiry". */
    val ttl: Long? = null,
) {
    val enabled get() = status == 1

    companion object {
        fun parse(o: JSONObject?): Action? {
            if (o == null || !o.has("do")) return null
            return Action(
                doType = o.optInt("do", -1),
                via = o.optStringOrNull("via"),
                viaV6 = o.optStringOrNull("via_v6"),
                status = o.optInt("status", 1),
                ttl = o.optLong("ttl", 0).takeIf { it > 0 },
            )
        }
    }
}

data class User(
    val safeCountries: List<String> = emptyList(),
    /** Sign-in provider when the account uses SSO, e.g. "google". */
    val sso: String? = null,
    val pk: String,
    val email: String,
    val date: String,
    val twofa: Boolean,
    val proxyAccess: Boolean,
    val status: Int,
    val statsRegion: String?,
    /** False for SSO-only accounts (no password set). */
    val hasPassword: Boolean = true,
    val passkeyEnforced: Boolean = false,
    /** The account has at least one passkey (auth_methods.passkey). */
    val hasPasskey: Boolean = false,
)

data class Passkey(val id: String, val name: String, val created: String?)

data class IpInfo(val ip: String, val type: String, val org: String, val country: String, val handler: String)

data class Profile(
    val pk: String,
    val name: String,
    val updated: Long,
    val filters: Int,
    val externalFilters: Int,
    val services: Int,
    val rules: Int,
    val folders: Int,
    val options: Int,
    val optionValues: Map<String, String>,
    val defaultAction: Action?,
    val disabledUntil: Long,
    val locked: Boolean = false,
    val lockMessage: String = "",
) {
    val isPaused get() = disabledUntil > System.currentTimeMillis() / 1000
}

data class FilterLevel(val title: String, val name: String, val status: Int)

data class Filter(
    val pk: String,
    val name: String,
    val description: String,
    val status: Int,
    val levels: List<FilterLevel>,
) {
    val enabled get() = status == 1 || levels.any { it.status == 1 }
    val activeLevel get() = levels.firstOrNull { it.status == 1 }
}

data class ServiceCategory(val pk: String, val name: String, val description: String, val count: Int)

data class Service(
    val pk: String,
    val name: String,
    val category: String,
    val unlockLocation: String?,
    val warning: String?,
    val action: Action?,
)

data class RuleFolder(val pk: Int, val name: String, val action: Action?, val count: Int)

data class Rule(val hostname: String, val group: Int, val order: Int, val action: Action?, val comment: String?, val updated: Long = 0)

data class ProfileOption(
    val pk: String,
    val title: String,
    val description: String,
    val type: String,
    val defaultValue: Any?,
    val infoUrl: String?,
) {
    /** For dropdown-like options the API sends the choices as an object of value -> label. */
    val choices: List<Pair<String, String>>
        get() = when (val d = defaultValue) {
            is JSONObject -> d.keys().asSequence().map { it to d.optString(it, it) }.toList()
            is JSONArray -> (0 until d.length()).map { d.opt(it) }.map {
                if (it is JSONObject) (it.optString("value", it.optString("PK")) to it.optString("title", it.optString("name", it.optString("value"))))
                else it.toString() to it.toString()
            }
            else -> emptyList()
        }
}

data class Restriction(val pk: String, val name: String, val description: String)

data class ProfileRef(val pk: String, val name: String)

data class Resolvers(
    val uid: String,
    val doh: String?,
    val dot: String?,
    val v4: List<String>,
    val v6: List<String>,
)

data class Device(
    val pk: String,
    val name: String,
    val icon: String,
    val status: Int,
    val stats: Int,
    val desc: String,
    val learnIp: Boolean,
    val restricted: Boolean,
    val legacyIpv4: String?,
    val legacyIpv4Enabled: Boolean,
    val profile: ProfileRef?,
    val profile2: ProfileRef?,
    val resolvers: Resolvers?,
    val ctrldVersion: String?,
    val clientCount: Int,
    /** Unix seconds of the last query seen on this endpoint (0 if unknown). */
    val lastActivity: Long,
    /** Devices behind this endpoint reported by ctrld. */
    val clients: Int,
    val clientList: List<EndpointClient> = emptyList(),
    /** True once analytics activity was merged in (so lastActivity == 0 really means "never seen"). */
    val activityLoaded: Boolean = false,
    /** "Expose IP via DNS": publishes the latest source IP at a hostname. */
    val ddnsStatus: Boolean = false,
    val ddnsSubdomain: String = "",
    val ddnsHostname: String = "",
    /** "Authorize by Dynamic DNS": learns the IP of a hostname you control. */
    val ddnsExtStatus: Boolean = false,
    val ddnsExtHost: String = "",
    /** "Prevent Deactivation" PIN, if set. */
    val deactivationPin: String? = null,
    /** Number of authorized (known) IPs. */
    val ipCount: Int = 0,
) {
    /** Matches the dashboard: pending endpoints, or ones with no queries ever received. */
    val notConfigured get() = status == 0 || (activityLoaded && lastActivity <= 0L)
}

/** A device seen behind an endpoint (from ctrld), as listed in the dashboard. */
data class EndpointClient(val id: String, val host: String, val ip: String, val mac: String, val alias: String, val lastActivity: Long) {
    val label get() = alias.ifBlank { host.ifBlank { ip.ifBlank { id } } }
}

/** Per-endpoint activity from the analytics service. */
data class EndpointActivity(val lastActivity: Long, val clients: List<EndpointClient>)

/** Accepts unix seconds, unix millis or an ISO-8601 string; returns unix seconds (0 if unknown). */
fun parseTimeSec(v: Any?): Long = when (v) {
    is Number -> v.toLong().let { if (it > 100_000_000_000L) it / 1000 else it }
    is String -> v.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000 else it }
        ?: runCatching { java.time.Instant.parse(v).epochSecond }.getOrDefault(0L)
    else -> 0L
}

data class Proxy(val pk: String, val city: String, val country: String, val countryName: String) {
    val label get() = listOf(city, countryName.ifBlank { country }).filter { it.isNotBlank() }.joinToString(", ").ifBlank { pk }
}

data class KnownIp(val ip: String, val ts: Long, val country: String, val city: String, val isp: String)

data class DeviceTypeGroup(val key: String, val name: String, val icons: List<Pair<String, String>>)

data class Level(val pk: Int, val title: String)

// ---------- JSON helpers ----------

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** Returns the first array found in [o], for endpoints whose list key isn't documented. */
fun firstArray(o: JSONObject): JSONArray? =
    o.keys().asSequence().map { o.opt(it) }.filterIsInstance<JSONArray>().firstOrNull()

private fun count(o: JSONObject?, key: String) = o?.optJSONObject(key)?.optInt("count") ?: 0

fun parseProfile(o: JSONObject): Profile {
    val p = o.optJSONObject("profile")
    val opt = p?.optJSONObject("opt")?.optJSONArray("data").objects()
    return Profile(
        pk = o.optString("PK"),
        name = o.optString("name"),
        updated = o.optLong("updated"),
        filters = count(p, "flt"),
        externalFilters = count(p, "cflt"),
        services = count(p, "svc"),
        rules = count(p, "rule"),
        folders = count(p, "grp"),
        options = count(p, "opt"),
        optionValues = opt.associate { it.optString("PK") to it.optString("value") },
        defaultAction = Action.parse(p?.optJSONObject("da")),
        disabledUntil = when (val d = o.opt("disable")) {
            is JSONObject -> parseTimeSec(d.opt("ttl") ?: d.opt("until"))
            is Number, is String -> parseTimeSec(d)
            else -> 0L
        }.takeIf { it > 0 } ?: o.optLong("disable_ttl", p?.optLong("disable_ttl") ?: 0L),
        locked = when (val l = o.opt("lock")) {
            is JSONObject -> l.optInt("status") == 1
            is Number -> l.toInt() == 1
            is Boolean -> l
            else -> false
        },
        lockMessage = (o.opt("lock") as? JSONObject)?.optString("message").orEmpty(),
    )
}

fun parseFilter(o: JSONObject) = Filter(
    pk = o.optString("PK"),
    name = o.optString("name", o.optString("PK")),
    description = o.optString("description"),
    status = o.optInt("status"),
    levels = o.optJSONArray("levels").objects().map {
        FilterLevel(it.optString("title", it.optString("name")), it.optString("name"), it.optInt("status"))
    },
)

fun parseService(o: JSONObject) = Service(
    pk = o.optString("PK"),
    name = o.optString("name", o.optString("PK")),
    category = o.optString("category"),
    unlockLocation = o.optStringOrNull("unlock_location"),
    warning = o.optStringOrNull("warning"),
    action = Action.parse(o.optJSONObject("action")),
)

fun parseRule(o: JSONObject) = Rule(
    hostname = o.optString("PK"),
    group = o.optInt("group"),
    order = o.optInt("order"),
    action = Action.parse(o.optJSONObject("action")),
    comment = o.optStringOrNull("comment"),
    updated = parseTimeSec(o.opt("ts") ?: o.opt("updated")),
)

private fun parseRef(o: JSONObject?): ProfileRef? =
    o?.optStringOrNull("PK")?.let { ProfileRef(it, o.optString("name")) }

fun parseDevice(o: JSONObject): Device {
    val r = o.optJSONObject("resolvers")
    val legacy = o.optJSONObject("legacy_ipv4")
    return Device(
        pk = o.optString("PK", o.optString("device_id")),
        name = o.optString("name"),
        icon = o.optString("icon"),
        status = o.optInt("status"),
        stats = o.optInt("stats"),
        desc = o.optString("desc"),
        learnIp = o.optInt("learn_ip") == 1,
        restricted = o.optInt("restricted") == 1,
        legacyIpv4 = legacy?.optStringOrNull("resolver"),
        legacyIpv4Enabled = legacy?.optInt("status") == 1,
        profile = parseRef(o.optJSONObject("profile")),
        profile2 = parseRef(o.optJSONObject("profile2")),
        resolvers = r?.let {
            Resolvers(
                uid = it.optString("uid"),
                doh = it.optStringOrNull("doh"),
                dot = it.optStringOrNull("dot"),
                v4 = it.optJSONArray("v4").strings(),
                v6 = it.optJSONArray("v6").strings(),
            )
        },
        ctrldVersion = o.optJSONObject("ctrld")?.optStringOrNull("version"),
        clientCount = o.optInt("client_count", 0),
        lastActivity = o.optLong("last_activity", 0L).let { if (it > 100_000_000_000L) it / 1000 else it },
        ipCount = o.optInt("ip_count", 0),
        ddnsStatus = o.optJSONObject("ddns")?.optInt("status") == 1,
        ddnsSubdomain = o.optJSONObject("ddns")?.optString("subdomain").orEmpty(),
        ddnsHostname = o.optJSONObject("ddns")?.optString("hostname").orEmpty(),
        ddnsExtStatus = o.optJSONObject("ddns_ext")?.optInt("status") == 1,
        ddnsExtHost = o.optJSONObject("ddns_ext")?.optString("host").orEmpty(),
        deactivationPin = when (val p = o.opt("deactivation_pin")) {
            is JSONObject -> p.optStringOrNull("pin")?.takeIf { p.optInt("status", 1) == 1 }
            null, JSONObject.NULL -> null
            else -> p.toString().takeIf { it.isNotBlank() && it != "-1" && it != "0" }
        },
        clients = when (val c = o.opt("clients")) {
            is JSONObject -> if (c.has("count")) c.optInt("count") else c.length()
            is JSONArray -> c.length()
            is Number -> c.toInt()
            else -> 0
        },
    )
}

fun parseProxy(o: JSONObject) = Proxy(
    pk = o.optString("PK"),
    city = o.optString("city"),
    country = o.optString("country"),
    countryName = o.optString("country_name"),
)

fun parseKnownIp(o: JSONObject) = KnownIp(
    ip = o.optString("ip"),
    ts = o.optLong("ts"),
    country = o.optString("country"),
    city = o.optString("city"),
    isp = o.optString("isp", o.optString("org")),
)

// ---------- Analytics ----------

data class TimeRange(
    val label: String,
    val seconds: Long,
    val end: Long = System.currentTimeMillis(),
    /** A user-picked date range: refreshing re-fetches it instead of moving it to "now". */
    val custom: Boolean = false,
    val tick: Int = 0,
) {
    val startIso: String get() = iso(end - seconds * 1000)
    val endIso: String get() = iso(end)

    /** Same window ending now (so a refresh picks up new data). */
    fun refreshed() = if (custom) copy(tick = tick + 1) else copy(end = System.currentTimeMillis())

    companion object {
        private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
        val presets = listOf(
            TimeRange("1h", 3600),
            TimeRange("24h", 86_400),
            TimeRange("7d", 7 * 86_400),
            TimeRange("30d", 30 * 86_400),
        )
    }
}

/** One time bucket; counts are keyed by action (0 blocked, 1 bypassed, 2 spoofed, 3 redirected, -1 other). */
data class SeriesPoint(val time: String, val counts: Map<Int, Long>) {
    val epochMs: Long get() = runCatching { java.time.Instant.parse(time).toEpochMilli() }.getOrDefault(0L)
}

data class CountItem(val value: String, val count: Long)

/** Which slice of analytics to show: everything, one endpoint, and/or one profile. */
data class StatScope(
    val endpointId: String? = null,
    val profileId: String? = null,
    /** DNS protocol refinement: legacy, doh, dot, doq or doh3 (as the dashboard's Encrypted DNS menu). */
    val protocol: String? = null,
    /** Action refinement (dashboard's Blocked/Bypassed/Redirected tiles): 0 blocked, 1 bypassed, 2+3 redirected. */
    val actions: List<Int>? = null,
)

data class TrendItem(val endpointId: String, val baseline: Long, val current: Long)

data class LogEntry(
    val timestamp: String,
    val endpointId: String,
    val endpointName: String,
    val profileId: String,
    val domain: String,
    val rrType: String,
    val sourceIp: String,
    val statusCode: Int,
    val protocol: String,
    val answers: List<String>,
    val action: Int,
    val trigger: String,
    val country: String,
    val city: String,
    val isp: String,
    val clientId: String = "",
    val triggerValue: String = "",
    val asn: Long = 0,
    /** Where the answer IPs are (the dashboard's Destinations). */
    val dstCountry: String = "",
    val dstIsp: String = "",
    val dstAsn: Long = 0,
    /** Redirect location code (a proxy pk such as "LHR") for redirected queries. */
    val spoofTarget: String = "",
) {
    val epochMs: Long get() = runCatching { java.time.Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)
}

fun parseLogEntry(o: JSONObject): LogEntry {
    val geo = o.optJSONObject("sourceGeoip")
    val answers = parseAnswers(o.opt("answers"))
    return LogEntry(
        timestamp = o.optString("timestamp"),
        endpointId = o.optString("endpointId"),
        endpointName = o.optString("endpointName"),
        profileId = o.optString("profileId"),
        domain = o.optString("question").trimEnd('.'),
        rrType = o.optString("rrType"),
        sourceIp = o.optString("sourceIp"),
        statusCode = o.optInt("statusCode"),
        protocol = o.optString("protocol"),
        answers = answers.first,
        dstCountry = answers.second?.optString("countryCode").orEmpty(),
        dstIsp = answers.second?.optString("isp").orEmpty(),
        dstAsn = answers.second?.optLong("asn") ?: 0,
        action = o.optInt("action", -1),
        trigger = o.optString("trigger"),
        country = geo?.optString("countryCode").orEmpty(),
        city = geo?.optString("city").orEmpty(),
        isp = geo?.optString("isp").orEmpty(),
        clientId = o.optString("clientId"),
        triggerValue = o.optString("triggerValue"),
        asn = geo?.optLong("asn") ?: 0,
        spoofTarget = o.optString("spoofTarget"),
    )
}

/** Activity Log filters, as the dashboard's filter bar. [trigger] is (filter|service, id); one at a time, as there. */
data class LogQuery(
    val endpointId: String? = null,
    val clientId: String? = null,
    val action: Int? = null,
    val protocol: String? = null,
    val statusCode: Int? = null,
    val rrType: String? = null,
    val trigger: Pair<String, String>? = null,
    val spoofTarget: String? = null,
    val search: String = "",
) {
    val anyFilter get() = copy(search = "") != LogQuery()
}

/**
 * DNS answers. The analytics API sends [{"ips": [...], "cname": [...], "geoip": {...}}] (sometimes a bare
 * object or plain strings); flattened to one record per line, IPs as-is and other kinds prefixed (CNAME: host).
 * The geoip block (where the answer IPs are) is returned separately.
 */
private fun parseAnswers(v: Any?): Pair<List<String>, JSONObject?> {
    val out = mutableListOf<String>()
    var geo: JSONObject? = null
    fun visit(x: Any?) {
        when (x) {
            is org.json.JSONArray -> for (i in 0 until x.length()) visit(x.opt(i))
            is JSONObject -> {
                if (x.has("data") || x.has("value")) { out += x.optString("data", x.optString("value")); return }
                x.keys().forEach { k ->
                    if (k == "geoip") { geo = x.optJSONObject(k); return@forEach }
                    val items = x.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(x.optString(k))
                    items.filter { it.isNotBlank() }.forEach { out += if (k == "ips" || k == "ip") it else "${k.uppercase()}: $it" }
                }
            }
            is String -> if (x.isNotBlank()) out += x
        }
    }
    visit(v)
    return out to geo
}

/** One DNS answer from a Domain Test (A, AAAA, CNAME…); [data] is the record's value. */
data class DnsAnswer(val name: String, val ttl: Int, val type: String, val data: String)

/**
 * Why Control D answered the way it did. [action]: "0" blocked, "1" bypassed, "2"/"3" redirected.
 * [source]: filter / bl / svc / rules / default / rebind / grules. [match]: the matched key (a service key
 * like "disney", a filter key, or the custom rule). [via]: redirect location code (proxy PK), if any.
 */
data class DomainVerdict(val source: String, val action: String, val match: String, val via: String?)

data class DomainTestResult(
    val domain: String,
    val rcode: Int,
    val flags: List<String>,
    val answers: List<DnsAnswer>,
    /** Null when no rule matched (normal resolution). */
    val verdict: DomainVerdict?,
)
