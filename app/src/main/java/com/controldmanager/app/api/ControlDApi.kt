package com.controldmanager.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : Exception(message)

/**
 * Thin client for https://api.controld.com (see https://docs.controld.com/reference).
 * Every response is wrapped as {"body": ..., "success": bool, "error": {...}}.
 */
class ControlDApi(private val token: String, private val orgId: String?) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** For calls that can take a while server-side (AI-generated profiles). */
    private val slowClient by lazy { client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

    /**
     * The dashboard's Domain Test: one DNS-over-HTTPS query to the endpoint's own resolver, asking for JSON so
     * the answer includes Control D's verdict. No auth (the resolver id is the address); no_log keeps the test
     * out of the Activity log and statistics.
     */
    suspend fun domainTest(resolverUid: String, domain: String, type: String): DomainTestResult = withContext(Dispatchers.IO) {
        val url = "https://dns.controld.com/$resolverUid".toHttpUrl().newBuilder()
            .addQueryParameter("name", domain).addQueryParameter("type", type)
            .addQueryParameter("controld", "1").addQueryParameter("no_log", "1").build()
        val req = Request.Builder().url(url).header("Accept", "application/dns+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("Domain test failed (HTTP ${r.code})", r.code)
            val o = JSONObject(r.body?.string().orEmpty())
            val answers = o.optJSONArray("answerRRs")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { rr ->
                    val t = rr.optString("TYPEname")
                    DnsAnswer(rr.optString("NAME").removeSuffix("."), rr.optInt("TTL"), t, rr.optString("rdata$t").removeSuffix("."))
                }
            }.orEmpty()
            val v = o.optJSONObject("controld")?.optJSONObject("verdict")
            val verdict = v?.optString("verdictAction")?.takeIf { it.isNotEmpty() }?.let { action ->
                DomainVerdict(
                    v.optString("verdictSource"), action, v.optString("verdictMatch"),
                    v.optString("verdictVia").ifBlank { null }, v.optString("profileID").ifBlank { null },
                )
            }
            DomainTestResult(
                domain = o.optString("QNAME", domain).removeSuffix("."),
                rcode = o.optInt("RCODE"),
                flags = listOf("TC", "RA", "RD", "CD").filter { o.optBoolean(it) },
                answers = answers,
                verdict = verdict,
            )
        }
    }

    /** Newest entry of Control D's public changelog feed (docs.controld.com), no auth. */
    suspend fun latestRelease(): CdRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://docs.controld.com/changelog.rss").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val xml = r.body?.string().orEmpty()
            val item = Regex("<item>([\\s\\S]*?)</item>").find(xml)?.groupValues?.get(1) ?: return@use null
            fun tag(t: String) = Regex("<$t[^>]*>([\\s\\S]*?)</$t>").find(item)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim()
            val version = tag("title")?.takeIf { it.isNotBlank() } ?: return@use null
            val date = tag("pubDate")?.let { d ->
                runCatching { java.time.ZonedDateTime.parse(d, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
            }
            CdRelease(version, tag("link") ?: "https://docs.controld.com/changelog", date)
        }
    }

    private suspend fun call(
        method: String,
        path: String,
        form: List<Pair<String, String>>? = null,
        json: JSONObject? = null,
        slow: Boolean = false,
    ): JSONObject = request(method, BASE + path, form, json, slow)

    private suspend fun request(
        method: String,
        url: String,
        form: List<Pair<String, String>>? = null,
        json: JSONObject? = null,
        slow: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = when {
            json != null -> json.toString().toRequestBody("application/json".toMediaType())
            form != null -> FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            method == "GET" -> null
            else -> FormBody.Builder().build()
        }
        val req = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply { orgId?.takeIf { it.isNotBlank() }?.let { header("X-Force-Org-Id", it) } }
            .build()
        (if (slow) slowClient else client).newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException("HTTP ${resp.code}: ${text.take(160).ifBlank { resp.message }}", resp.code)
            }
            if (!resp.isSuccessful || !obj.optBoolean("success", resp.isSuccessful)) {
                val msg = obj.optJSONObject("error")?.optStringOrNull("message")
                    ?: obj.optStringOrNull("message")
                    ?: "HTTP ${resp.code}"
                throw ApiException(msg, resp.code)
            }
            obj.optJSONObject("body") ?: JSONObject()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------- Account ----------

    suspend fun user(): User = call("GET", "/users").let {
        User(
            pk = it.optString("PK"),
            email = it.optString("email"),
            date = it.optString("date"),
            twofa = it.optInt("twofa") == 1,
            proxyAccess = it.optInt("proxy_access") == 1,
            status = it.optInt("status"),
            statsRegion = it.optJSONObject("org")?.optStringOrNull("stats_endpoint") ?: it.optStringOrNull("stats_endpoint"),
            sso = it.optStringOrNull("sso"),
            safeCountries = it.optJSONArray("safe_countries")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } }.orEmpty(),
            hasPassword = if (it.has("has_usable_password")) it.optBoolean("has_usable_password") else it.optStringOrNull("sso") == null,
            passkeyEnforced = it.optBoolean("passkey_enforced") || it.optInt("passkey_enforced") == 1,
            hasPasskey = it.optJSONObject("auth_methods")?.optBoolean("passkey") == true,
        )
    }

    /** The account's passkeys (as the dashboard's Account page lists them). */
    suspend fun passkeys(): List<Passkey> {
        val b = call("GET", "/passkeys")
        val arr = b.optJSONArray("passkeys") ?: b.optJSONArray("credentials") ?: firstArray(b)
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        return arr.objects().map { o ->
            val ts = listOf("created", "created_at", "date", "ts").firstNotNullOfOrNull { k -> o.opt(k)?.let { parseTimeSec(it).takeIf { s -> s > 0 } } }
            Passkey(
                id = o.optString("PK", o.optString("id")),
                name = listOf("name", "label", "nickname", "device").firstNotNullOfOrNull { k -> o.optStringOrNull(k) } ?: "Passkey",
                created = ts?.let { fmt.format(java.util.Date(it * 1000)) },
            )
        }
    }

    suspend fun setPasskeyEnforced(on: Boolean) = updateUser(JSONObject().put("passkey_enforced", if (on) 1 else 0))

    /** Raw billing data (shape not documented): subscriptions, payments (receipts), and the account body. */
    suspend fun rawBilling(path: String): JSONObject = call("GET", path)

    /** Account options, as the dashboard's Account page saves them (PUT /users with JSON). */
    suspend fun updateUser(fields: JSONObject) {
        call("PUT", "/users", json = fields)
    }

    /** Home Countries: traffic from these counts as home on the Statistics page. */
    suspend fun setHomeCountries(codes: List<String>) = updateUser(JSONObject().put("safe_countries", org.json.JSONArray(codes)))

    /** Moves analytics storage; [clearOld] also deletes what was stored in the old region. */
    suspend fun setStorageRegion(region: String, clearOld: Boolean) =
        updateUser(JSONObject().put("stats_endpoint", region).apply { if (clearOld) put("user_nuke_analytics", 1) })

    /** Permanently deletes all stored analytics (every endpoint) in the current region. */
    suspend fun clearAnalytics() = updateUser(JSONObject().put("user_nuke_analytics", 1))

    /** Current retention per data scope (raw_queries / agg_queries), in days. */
    suspend fun retention(region: String): Map<String, Int> =
        request("GET", "https://$region.analytics.controld.com/v2/retention").optJSONArray("retentions").objects()
            .associate { it.optString("dataScope") to it.optInt("retentionDays") }

    /** Longest retention the plan allows per data scope. */
    suspend fun retentionLimits(region: String): Map<String, Int> =
        request("GET", "https://$region.analytics.controld.com/v2/capabilities").optJSONObject("dataAvailability")
            ?.optJSONArray("retentions").objects().associate { it.optString("dataScope") to it.optInt("retentionDays") }

    suspend fun setRetention(region: String, changes: Map<String, Int>) {
        val list = org.json.JSONArray()
        changes.forEach { (scope, days) -> list.put(JSONObject().put("dataScope", scope).put("retentionDays", days)) }
        request("PATCH", "https://$region.analytics.controld.com/v2/retention", json = JSONObject().put("retentions", list))
    }

    /** Analytics storage regions; their PK is the subdomain of the analytics host. */
    suspend fun analyticsRegions(): List<Pair<String, String>> {
        val b = call("GET", "/analytics/endpoints")
        return (b.optJSONArray("endpoints") ?: firstArray(b)).objects().map {
            it.optString("PK") to it.optString("title", it.optString("name", it.optString("PK")))
        }.filter { it.first.isNotBlank() }
    }

    // ---------- Analytics (https://{region}.analytics.controld.com/v2, as used by the web dashboard) ----------

    private suspend fun analytics(region: String, path: String, params: List<Pair<String, String>>): JSONObject {
        val url = "https://$region.analytics.controld.com/v2$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return try {
            request("GET", url.toString())
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) {
                throw ApiException("Analytics rejected this API token (${e.code}): ${e.message}", e.code)
            }
            throw e
        }
    }

    private fun range(r: TimeRange, endpointId: String?) = buildList {
        add("startTime" to r.startIso)
        add("endTime" to r.endIso)
        endpointId?.let { add("endpointId" to it) }
    }

    private fun scoped(r: TimeRange, s: StatScope) = range(r, s.endpointId) +
        listOfNotNull(s.profileId?.let { "profileId" to it }, s.protocol?.let { "protocol[]" to it }) +
        s.actions.orEmpty().map { "action[]" to "$it" }

    /** Total queries matching [filters] (e.g. protocol[], srcCountry[], trigger/triggerValue[]). */
    suspend fun statCount(region: String, r: TimeRange, s: StatScope, filters: List<Pair<String, String>> = emptyList()): Long =
        analytics(region, "/statistic/count", scoped(r, s) + filters).optLong("count")

    /**
     * Counts grouped by [field]: question, triggerValue, endpointId, srcCountry, dstCountry, srcIsp, srcAsn, dstIsp, dstAsn.
     * Same calls the dashboard's Statistics page makes.
     */
    suspend fun statBy(
        region: String, r: TimeRange, s: StatScope, field: String,
        extra: List<Pair<String, String>> = emptyList(), limit: Int = 10, ascending: Boolean = false,
    ): List<CountItem> =
        analytics(region, "/statistic/count/$field", scoped(r, s) + extra + listOf("limit" to "$limit", "sortOrder" to if (ascending) "asc" else "desc"))
            .optJSONArray("counts").objects().map { CountItem(it.optString("value"), it.optLong("count")) }

    suspend fun statSeries(region: String, r: TimeRange, s: StatScope, buckets: Int = 50): List<SeriesPoint> =
        analytics(region, "/statistic/timeseries/action", scoped(r, s) + ("limit" to buckets.toString()))
            .optJSONArray("queries").objects().map { q ->
                val c = q.optJSONObject("count") ?: JSONObject()
                SeriesPoint(q.optString("time"), c.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to c.optLong(k) } }.toMap())
            }

    suspend fun statTrend(region: String, r: TimeRange, s: StatScope): List<TrendItem> =
        analytics(region, "/statistic/trend/endpointId", scoped(r, s) + listOf("limit" to "500", "sortOrder" to "desc"))
            .optJSONArray("groups").objects().map { TrendItem(it.optString("group"), it.optLong("baseline"), it.optLong("current")) }

    suspend fun timeseries(region: String, r: TimeRange, endpointId: String?, buckets: Int = 30): List<SeriesPoint> =
        analytics(region, "/statistic/timeseries/action", range(r, endpointId) + ("limit" to buckets.toString()))
            .optJSONArray("queries").objects().map { q ->
                val c = q.optJSONObject("count") ?: JSONObject()
                SeriesPoint(q.optString("time"), c.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to c.optLong(k) } }.toMap())
            }

    /** Top domains, optionally limited to one action (0 blocked, 1 bypassed, 3 redirected). */
    suspend fun topDomains(region: String, r: TimeRange, endpointId: String?, action: Int?, limit: Int = 10): List<CountItem> =
        analytics(region, "/statistic/count/question", range(r, endpointId) + buildList {
            action?.let { add("action[]" to it.toString()) }
            add("limit" to limit.toString())
            add("sortOrder" to "desc")
        }).counts()

    /** Top filters / services / custom rules that triggered an action. */
    suspend fun topTriggers(region: String, r: TimeRange, endpointId: String?, trigger: String, action: Int = 0, limit: Int = 10): List<CountItem> =
        analytics(region, "/statistic/count/triggerValue", range(r, endpointId) + listOf(
            "trigger" to trigger, "action" to action.toString(), "limit" to limit.toString(), "sortOrder" to "desc",
        )).counts()

    suspend fun topCountries(region: String, r: TimeRange, endpointId: String?, limit: Int = 10): List<CountItem> =
        analytics(region, "/statistic/count/srcCountry", range(r, endpointId) + listOf("limit" to limit.toString(), "sortOrder" to "desc")).counts()

    suspend fun endpointTrend(region: String, r: TimeRange): List<TrendItem> =
        analytics(region, "/statistic/trend/endpointId", range(r, null) + listOf("limit" to "50", "sortOrder" to "desc"))
            .optJSONArray("groups").objects().map { TrendItem(it.optString("group"), it.optLong("baseline"), it.optLong("current")) }

    /** Raw query log with the dashboard's Activity Log filters (same query parameters it sends). */
    suspend fun activityLog(region: String, r: TimeRange, page: Int, f: LogQuery): List<LogEntry> =
        analytics(region, "/activity-log", range(r, f.endpointId) + buildList {
            add("page" to page.toString())
            f.clientId?.let { add("clientId" to it) }
            f.action?.let { add("action" to it.toString()) }
            f.protocol?.let { add("protocol[]" to it) }
            f.statusCode?.let { add("statusCode" to it.toString()) }
            f.rrType?.let { add("rrType" to it) }
            f.trigger?.let { add("trigger" to it.first); add("triggerValue" to it.second) }
            f.spoofTarget?.let { add("spoofTarget" to it) }
            f.search.takeIf { it.isNotBlank() }?.let { add("searchQuestion" to it.trim()) }
        }).optJSONArray("queries").objects().map(::parseLogEntry)

    /** Last activity and clients per endpoint (device_id), as the dashboard's Endpoints page shows. */
    suspend fun endpointActivity(region: String): Map<String, EndpointActivity> {
        val items = analytics(region, "/client", emptyList()).optJSONObject("items") ?: return emptyMap()
        return items.keys().asSequence().associateWith { id ->
            val o = items.optJSONObject(id) ?: JSONObject()
            val cl = o.optJSONObject("clients")
            EndpointActivity(
                lastActivity = parseTimeSec(o.opt("lastActivityTime")),
                clients = cl?.keys()?.asSequence()?.map { cid ->
                    val c = cl.optJSONObject(cid) ?: JSONObject()
                    EndpointClient(
                        id = cid,
                        host = c.optString("host", cid),
                        ip = c.optString("ip"),
                        mac = c.optString("mac"),
                        alias = c.optString("alias"),
                        lastActivity = parseTimeSec(c.opt("lastActivityTime")),
                    )
                }?.sortedByDescending { it.lastActivity }?.toList().orEmpty(),
            )
        }
    }

    private fun JSONObject.counts() = optJSONArray("counts").objects().map { CountItem(it.optString("value"), it.optLong("count")) }

    suspend fun ip(): IpInfo = call("GET", "/ip").let {
        IpInfo(it.optString("ip"), it.optString("type"), it.optString("org"), it.optString("country"), it.optString("handler"))
    }

    // ---------- Profiles ----------

    suspend fun profiles(): List<Profile> =
        call("GET", "/profiles").optJSONArray("profiles").objects().map(::parseProfile)

    /** Creates a profile; returns the new profile's name (Control D names clones itself). */
    suspend fun createProfile(name: String, cloneFrom: String?): String? =
        call("POST", "/profiles", buildList {
            add("name" to name)
            cloneFrom?.let { add("clone_profile_id" to it) }
        }).optJSONArray("profiles")?.optJSONObject(0)?.optString("name")?.ifBlank { null }

    /** Ready-made templates shown as "Profile Type" in the dashboard (Gaming, Kids, Privacy, …). */
    suspend fun featuredProfiles(): List<ProfileRef> =
        call("GET", "/profiles/featured").optJSONArray("profiles").objects()
            .mapNotNull { o -> o.optStringOrNull("PK")?.let { ProfileRef(it, o.optString("name")) } }

    /** Beta: Control D builds the profile from a plain-language description. */
    suspend fun createProfileFromPrompt(prompt: String) {
        call("POST", "/profiles", listOf("name" to "Custom Profile", "profile_prompt" to prompt), slow = true)
    }

    /** Profile config as JSON, in the same format the dashboard exports and imports. */
    suspend fun exportProfile(id: String): JSONObject = call("GET", "/profiles/$id/export/json")

    suspend fun importProfile(config: JSONObject) {
        call("POST", "/profiles/import", json = JSONObject().put("config", config))
    }

    suspend fun lockProfile(id: String, message: String) {
        call("PUT", "/profiles/$id", listOf("lock_status" to "1", "lock_message" to message))
    }

    /** Unlocking needs the account password (Control D's own safeguard). */
    suspend fun unlockProfile(id: String, password: String) {
        call("PUT", "/profiles/$id", listOf("lock_status" to "0", "password" to password))
    }

    /** Folder with its rules, in the dashboard's export format. */
    suspend fun exportFolder(profileId: String, folder: Int): JSONObject = call("GET", "/profiles/$profileId/groups/$folder/export/json")

    suspend fun importFolder(profileId: String, config: JSONObject) {
        call("POST", "/profiles/$profileId/groups/import", json = JSONObject().put("config", config))
    }

    suspend fun renameProfile(id: String, name: String) {
        call("PUT", "/profiles/$id", listOf("name" to name))
    }

    /** Pause a profile until [untilEpochSec]; pass 0 to resume. */
    suspend fun pauseProfile(id: String, untilEpochSec: Long) {
        call("PUT", "/profiles/$id", listOf("disable_ttl" to untilEpochSec.toString()))
    }

    suspend fun deleteProfile(id: String) {
        call("DELETE", "/profiles/$id")
    }

    suspend fun profileOptions(): List<ProfileOption> =
        call("GET", "/profiles/options").optJSONArray("options").objects().map {
            ProfileOption(
                pk = it.optString("PK"),
                title = it.optString("title", it.optString("PK")),
                description = it.optString("description"),
                type = it.optString("type"),
                defaultValue = it.opt("default_value"),
                infoUrl = it.optStringOrNull("info_url"),
            )
        }

    suspend fun setOption(profileId: String, option: String, enabled: Boolean, value: String? = null) {
        call("PUT", "/profiles/$profileId/options/$option", buildList {
            add("status" to if (enabled) "1" else "0")
            value?.let { add("value" to it) }
        })
    }

    suspend fun availableRestrictions(): List<Restriction> =
        call("GET", "/profiles/restrictions").optJSONArray("restrictions").objects().map {
            Restriction(it.optString("PK"), it.optString("name", it.optString("PK")), it.optString("description"))
        }

    suspend fun enabledRestrictions(profileId: String): Set<String> =
        call("GET", "/profiles/$profileId/restrictions").optJSONArray("restrictions").strings().toSet()

    suspend fun setRestriction(profileId: String, name: String, enabled: Boolean) {
        call("PUT", "/profiles/$profileId/restrictions/$name", listOf("status" to if (enabled) "1" else "0"))
    }

    // ---------- Filters ----------

    suspend fun nativeFilters(profileId: String): List<Filter> =
        call("GET", "/profiles/$profileId/filters").optJSONArray("filters").objects().map(::parseFilter)

    suspend fun externalFilters(profileId: String): List<Filter> =
        call("GET", "/profiles/$profileId/filters/external").optJSONArray("filters").objects().map(::parseFilter)

    suspend fun setFilter(profileId: String, filter: String, enabled: Boolean) {
        call("PUT", "/profiles/$profileId/filters/filter/$filter", listOf("status" to if (enabled) "1" else "0"))
    }

    // ---------- Services ----------

    suspend fun profileServices(profileId: String): List<Service> =
        call("GET", "/profiles/$profileId/services").optJSONArray("services").objects().map(::parseService)

    suspend fun setService(profileId: String, service: String, action: Action) {
        call("PUT", "/profiles/$profileId/services/$service", actionForm(action))
    }

    suspend fun serviceCategories(): List<ServiceCategory> =
        call("GET", "/services/categories").optJSONArray("categories").objects().map {
            ServiceCategory(it.optString("PK"), it.optString("name"), it.optString("description"), it.optInt("count"))
        }

    suspend fun categoryServices(category: String): List<Service> =
        call("GET", "/services/categories/$category").optJSONArray("services").objects().map(::parseService)

    // ---------- Rule folders ----------

    suspend fun folders(profileId: String): List<RuleFolder> =
        call("GET", "/profiles/$profileId/groups").optJSONArray("groups").objects().map {
            RuleFolder(it.optInt("PK"), it.optString("group"), Action.parse(it.optJSONObject("action")), it.optInt("count"))
        }

    suspend fun createFolder(profileId: String, name: String, action: Action) {
        call("POST", "/profiles/$profileId/groups", listOf("name" to name) + actionForm(action))
    }

    suspend fun modifyFolder(profileId: String, folder: Int, name: String, action: Action) {
        call("PUT", "/profiles/$profileId/groups/$folder", listOf("name" to name) + actionForm(action))
    }

    suspend fun deleteFolder(profileId: String, folder: Int) {
        call("DELETE", "/profiles/$profileId/groups/$folder")
    }

    // ---------- Custom rules ----------

    suspend fun rules(profileId: String, folder: Int = 0): List<Rule> {
        val path = if (folder == 0) "/profiles/$profileId/rules" else "/profiles/$profileId/rules/$folder"
        return call("GET", path).optJSONArray("rules").objects().map(::parseRule)
    }

    suspend fun createRules(profileId: String, hostnames: List<String>, action: Action, folder: Int, comment: String?) {
        call("POST", "/profiles/$profileId/rules", ruleForm(hostnames, action, folder, comment))
    }

    suspend fun modifyRules(profileId: String, hostnames: List<String>, action: Action, folder: Int, comment: String?) {
        call("PUT", "/profiles/$profileId/rules", ruleForm(hostnames, action, folder, comment))
    }

    suspend fun deleteRule(profileId: String, hostname: String) {
        call("DELETE", "/profiles/$profileId/rules/${enc(hostname)}")
    }

    private fun ruleForm(hostnames: List<String>, action: Action, folder: Int, comment: String?) = buildList {
        addAll(actionForm(action))
        add("group" to folder.toString())
        if (comment != null) add("comment" to comment)
        action.ttl?.let { add("ttl" to it.toString()) }
        hostnames.forEach { add("hostnames[]" to it) }
    }

    // ---------- Default rule ----------

    suspend fun defaultRule(profileId: String): Action? =
        Action.parse(call("GET", "/profiles/$profileId/default").optJSONObject("default"))

    suspend fun setDefaultRule(profileId: String, action: Action) {
        call("PUT", "/profiles/$profileId/default", actionForm(action))
    }

    private fun actionForm(a: Action) = buildList {
        if (a.doType >= 0) add("do" to a.doType.toString())
        add("status" to a.status.toString())
        if (a.doType == Do.SPOOF || a.doType == Do.REDIRECT) {
            a.via?.takeIf { it.isNotBlank() }?.let { add("via" to it) }
        }
        if (a.doType == Do.SPOOF) a.viaV6?.takeIf { it.isNotBlank() }?.let { add("via_v6" to it) }
    }

    // ---------- Proxies ----------

    suspend fun proxies(): List<Proxy> =
        call("GET", "/proxies").let { it.optJSONArray("proxies") ?: firstArray(it) }.objects().map(::parseProxy)

    // ---------- Endpoints (devices) ----------

    suspend fun devices(): List<Device> =
        call("GET", "/devices").optJSONArray("devices").objects().map(::parseDevice)

    suspend fun deviceTypes(): List<DeviceTypeGroup> {
        val types = call("GET", "/devices/types").optJSONObject("types") ?: return emptyList()
        return types.keys().asSequence().mapNotNull { key ->
            val g = types.optJSONObject(key) ?: return@mapNotNull null
            val icons = g.optJSONObject("icons") ?: JSONObject()
            DeviceTypeGroup(key, g.optString("name", key), icons.keys().asSequence().map { k ->
                // Entries are either a plain label or an object like {"name":"Android","highlight":[…]}.
                k to (icons.optJSONObject(k)?.optString("name")?.takeIf { it.isNotBlank() } ?: icons.optString(k))
            }.toList())
        }.toList()
    }

    /** Fields per https://docs.controld.com/reference/post_devices (name, icon, profile_id, stats, …). */
    suspend fun createDevice(fields: List<Pair<String, String>>): Device =
        parseDevice(call("POST", "/devices", listOf("client_count" to "1") + fields))

    /** Fields per https://docs.controld.com/reference/put_devices-device-id */
    suspend fun modifyDevice(deviceId: String, fields: List<Pair<String, String>>) {
        call("PUT", "/devices/$deviceId", fields)
    }

    suspend fun deleteDevice(deviceId: String) {
        call("DELETE", "/devices/$deviceId")
    }

    suspend fun analyticsLevels(): List<Level> {
        val b = call("GET", "/analytics/levels")
        val arr = b.optJSONArray("levels") ?: firstArray(b)
        return arr.objects().map { Level(it.optInt("PK"), it.optString("title", it.optString("name"))) }
    }

    // ---------- Access (known IPs) ----------

    suspend fun knownIps(deviceId: String): List<KnownIp> {
        val b = call("GET", "/access?device_id=${enc(deviceId)}")
        return (b.optJSONArray("ips") ?: firstArray(b)).objects().map(::parseKnownIp)
    }

    suspend fun learnIps(deviceId: String, ips: List<String>) {
        call("POST", "/access", listOf("device_id" to deviceId) + ips.map { "ips[]" to it })
    }

    suspend fun forgetIps(deviceId: String, ips: List<String>) {
        call("DELETE", "/access", listOf("device_id" to deviceId) + ips.map { "ips[]" to it })
    }

    /**
     * Asks Control D which resolver answered this device's DNS lookup (the same check the dashboard
     * uses for "Current Device"). A random subdomain defeats DNS caching. No auth header is sent.
     */
    suspend fun currentResolverUid(): String? = withContext(Dispatchers.IO) {
        val host = "cd" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) + ".verify.controld.com"
        val req = Request.Builder().url("https://$host/detect").header("Accept", "application/json").build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optJSONObject("body")?.optStringOrNull("uid")
            }
        }.getOrNull()
    }

    private val ipClient by lazy { client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build() }

    /**
     * The IPv4/IPv6 address websites currently see from this device, using the same check as the
     * dashboard's Default Location panel. Random subdomains force a fresh DNS lookup through the
     * active profile, so a redirect shows the proxy's exit IP. No auth header is sent.
     */
    suspend fun visibleIps(): Pair<IpInfo?, IpInfo?> = withContext(Dispatchers.IO) {
        val tag = java.util.UUID.randomUUID().toString().take(8)
        fun check(host: String): IpInfo? = runCatching {
            val req = Request.Builder().url("https://$tag.$host/ip").header("Accept", "application/json").build()
            ipClient.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optJSONObject("body")?.let {
                    IpInfo(it.optString("ip"), it.optString("type"), it.optString("org"), it.optString("country"), it.optString("pop", it.optString("handler")))
                }
            }
        }.getOrNull()
        kotlinx.coroutines.coroutineScope {
            val v4 = async { check("ipv4.controld.io") }
            val v6 = async { check("ipv6.controld.io") }
            v4.await() to v6.await()
        }
    }

    companion object {
        const val BASE = "https://api.controld.com"

        /** Default-rule redirect target meaning "Auto" (nearest location) in the dashboard. */
        const val AUTO_LOCATION = "LOCAL"
    }
}
