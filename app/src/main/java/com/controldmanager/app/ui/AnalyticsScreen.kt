@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Same colours the dashboard uses for each action on its Statistics page. */
private val StatBlocked get() = Color(0xFFE93349)
private val StatBypassed get() = Palette.mix(Color(0xFF1BE3AD), Color(0xFF0FA678))
private val StatRedirected get() = Palette.mix(Color(0xFFE69201), Color(0xFFD08400))
private val StatTotal get() = Palette.mix(Color(0xFFAAB8D9), Color(0xFF5C6D96))

private data class SeriesDef(val action: Int, val label: String, val color: Color)

private val chartSeries get() = listOf(
    SeriesDef(Do.BLOCK, "Blocked", StatBlocked),
    SeriesDef(Do.REDIRECT, "Redirected", StatRedirected),
    SeriesDef(Do.BYPASS, "Bypassed", StatBypassed),
)

private fun statColor(action: Int) = when (action) {
    Do.BLOCK -> StatBlocked
    Do.BYPASS -> StatBypassed
    else -> StatRedirected
}

/** Protocols in the Encrypted DNS menu: API value, label, icon asset. */
private val protocols = listOf(
    Triple("legacy", "Legacy DNS", "proto-legacy"),
    Triple("doh", "DNS-over-HTTPS", "proto-doh"),
    Triple("dot", "DNS-over-TLS", "proto-dot"),
    Triple("doq", "DNS-over-QUIC", "proto-doq"),
    Triple("doh3", "DNS-over-HTTPS/3", "proto-doh3"),
)

private const val PREF_REGION = "analytics_region"

private fun prefs(ctx: Context) = ctx.getSharedPreferences("controldmanager_ui", Context.MODE_PRIVATE)

/** The user's analytics region override, if they picked one. */
fun regionOverride(ctx: Context): String? = null

@Composable
fun AnalyticsScreen(nav: NavHostController) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    // Region comes from the account automatically; drop any old manual choice from earlier versions.
    remember { prefs(ctx).edit().remove(PREF_REGION).apply() }
    val region = rememberLoader { session.analyticsRegion(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rangeIdx by rememberSaveable { mutableIntStateOf(1) }
    var range by remember { mutableStateOf(TimeRange.presets[1]) }
    var pickDates by remember { mutableStateOf(false) }
    val refresh = { range = range.refreshed() }
    // The Activity log puts its search / export / copy buttons in this header.
    var headerActions by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { nav.navigate("domain-test") }) { Icon(Solar.ShieldCheck, "Domain test") }
                    if (tab == 1) headerActions?.invoke()
                },
                colors = cdTopBarColors(),
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.padding(pad)) {
            SlideSelector(
                listOf(SlideOption("Statistics", Solar.Insights), SlideOption("Activity log", Solar.ListAlt)),
                tab, { tab = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.horizontalScrollFaded().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimeRange.presets.forEachIndexed { i, r ->
                    CdChip(rangeIdx == i, { rangeIdx = i; range = r.refreshed() }, { Text(r.label) })
                }
                CdChip(
                    rangeIdx == -1, { pickDates = true },
                    { Text(if (rangeIdx == -1) range.label else "Custom") },
                    leadingIcon = { Icon(Solar.CalendarMonth, null, Modifier.size(18.dp)) },
                )
            }
            val r = region.data
            when {
                r != null && tab == 0 -> StatisticsView(r, range, refresh)
                r != null -> ActivityLogView(nav, r, range, refresh) { headerActions = it }
                region.error != null -> ErrorState(region.error!!) { region.reload() }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    if (pickDates) {
        CustomRangeDialog(onDismiss = { pickDates = false }) { picked ->
            range = picked
            rangeIdx = -1
        }
    }
}

/** Date range picker for a custom analytics window (whole days, local time). */
@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onPick: (TimeRange) -> Unit) {
    val today = java.time.LocalDate.now()
    var startDay by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var endDay by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy") }
    CdDialog(
        onDismissRequest = onDismiss,
        edgeToEdge = true,
        text = {
            Column {
                CdCalendar(
                    start = startDay, end = endDay,
                    // First tap picks the start, second the end; tapping before the start starts over.
                    onDayClick = { d ->
                        val s0 = startDay
                        if (s0 == null || endDay != null || d.isBefore(s0)) { startDay = d; endDay = null } else endDay = d
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    isSelectable = { !it.isAfter(today) },
                    minYear = today.year - 3, maxYear = today.year, wheelPicksDay = false, maxDate = today,
                )
                Text(
                    when {
                        startDay == null -> "Choose the first day"
                        endDay == null -> "${startDay!!.format(fmt)} · choose the last day"
                        else -> "${startDay!!.format(fmt)} – ${endDay!!.format(fmt)}"
                    },
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = startDay != null,
                onClick = {
                    val zone = java.time.ZoneId.systemDefault()
                    val sd = startDay!!
                    val ed = endDay ?: sd
                    val start = sd.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = minOf(ed.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
                    val short = java.time.format.DateTimeFormatter.ofPattern("d MMM")
                    val label = if (sd == ed) sd.format(short) else "${sd.format(short)} – ${ed.format(short)}"
                    onPick(TimeRange(label, (end - start) / 1000, end, custom = true))
                    onDismiss()
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Statistics (mirrors dashboard/statistics) ----------

private data class Overview(
    val series: List<SeriesPoint>,
    val encrypted: Long,
    val malware: Long,
    val home: Long,
    val homeCountry: String?,
)

private val actionTabs = listOf(Do.BLOCK to "Blocked", Do.BYPASS to "Bypassed", Do.REDIRECT to "Redirected")
private val geoTabs = listOf("Sources", "Destinations", "Network Sources", "Network Destinations")

@Composable
private fun StatisticsView(region: String, range: TimeRange, onRefresh: () -> Unit) {
    val session = LocalSession.current
    var endpoint by remember { mutableStateOf<Device?>(null) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    var bars by rememberSaveable { mutableStateOf(false) }
    var endpointMode by rememberSaveable { mutableIntStateOf(0) }
    var actionTab by rememberSaveable { mutableIntStateOf(0) }
    var geoTab by rememberSaveable { mutableIntStateOf(0) }
    var protocol by rememberSaveable { mutableStateOf<String?>(null) }
    var refine by rememberSaveable { mutableStateOf<Int?>(null) }
    val devices = rememberLoader { runCatching { session.api.devices() }.getOrDefault(emptyList()) }
    val profiles = rememberLoader { runCatching { session.api.profiles() }.getOrDefault(emptyList()) }
    val services = rememberLoader { runCatching { session.allServices() }.getOrDefault(emptyMap()) }
    val filterNames = rememberLoader { runCatching { session.filterNames() }.getOrDefault(emptyMap()) }
    val ctx = LocalContext.current
    val activity = rememberLoader { runCatching { session.devicesWithActivity(regionOverride(ctx)) }.getOrDefault(emptyList()) }
    LiveRefresh(activity)
    val refineActions = refine?.let { if (it == Do.REDIRECT) listOf(Do.SPOOF, Do.REDIRECT) else listOf(it) }
    val scope = StatScope(endpoint?.pk, profile?.pk, protocol, refineActions)
    val api = session.api

    val overview = rememberLoader(region, range, scope) {
        coroutineScope {
            // The time series must succeed (it surfaces auth/region errors); the rest degrade to 0.
            val series = async { api.statSeries(region, range, scope) }
            fun soft(block: suspend () -> Long) = async { runCatching { block() }.getOrDefault(0L) }
            // protocol[] values are OR'd, so when refined to one protocol the answer is simply all or nothing.
            val enc = soft {
                when (scope.protocol) {
                    null -> api.statCount(region, range, scope, listOf("doh", "doq", "dot", "doh3").map { "protocol[]" to it })
                    "legacy" -> 0L
                    else -> api.statCount(region, range, scope)
                }
            }
            val mal = soft { api.statCount(region, range, scope, listOf("trigger" to "filter", "triggerValue[]" to "malware")) }
            val cc = runCatching { api.ip().country }.getOrNull()?.takeIf { it.length == 2 }
            val home = soft { if (cc == null) 0L else api.statCount(region, range, scope, listOf("srcCountry[]" to cc)) }
            Overview(series.await(), enc.await(), mal.await(), home.await(), cc)
        }
    }
    // The panel lists every endpoint (only the profile filter applies), so a refined endpoint stays in context.
    val panelScope = StatScope(profileId = profile?.pk, protocol = protocol, actions = refineActions)
    val endpointsData = rememberLoader(region, range, panelScope, endpointMode) {
        val base: List<Pair<CountItem, TrendItem?>> = when (endpointMode) {
            0 -> api.statTrend(region, range, panelScope).map { CountItem(it.endpointId, it.current) to it }
            else -> api.statBy(region, range, panelScope, "endpointId", limit = 500, ascending = endpointMode == 2).map { it to null }
        }
        // Top blocking filter per endpoint, as the dashboard's eye/shield icon (one small request each).
        coroutineScope {
            base.map { (c, t) ->
                async {
                    val top = runCatching {
                        api.statBy(region, range, StatScope(c.value, profile?.pk, protocol, refineActions), "triggerValue", listOf("trigger" to "filter"), limit = 1)
                            .firstOrNull()?.value
                    }.getOrNull()
                    top?.let { id -> session.filterParent(id)?.let { filterParentCache[id] = it } }
                    EndpointStat(c.value, c.count, t, top)
                }
            }.map { it.await() }
        }
    }
    val actionData = rememberLoader(region, range, scope, actionTab) {
        val act = actionTabs[actionTab].first
        coroutineScope {
            fun list(block: suspend () -> List<CountItem>) = async { runCatching { block() }.getOrDefault(emptyList()) }
            val filters = list { if (act == Do.BLOCK) api.statBy(region, range, scope, "triggerValue", listOf("action" to "$act", "trigger" to "filter")) else emptyList() }
            val svc = list { api.statBy(region, range, scope, "triggerValue", listOf("action" to "$act", "trigger" to "service")) }
            // Redirected domains include spoofed ones (action 2), as on the dashboard.
            val actions = if (act == Do.REDIRECT) listOf(Do.SPOOF, Do.REDIRECT) else listOf(act)
            val domains = list { api.statBy(region, range, scope, "question", actions.map { "action[]" to "$it" }) }
            val rules = list { api.statBy(region, range, scope, "triggerValue", listOf("action" to "$act", "trigger" to "custom")) }
            listOf(filters.await(), svc.await(), domains.await(), rules.await())
        }
    }
    val geoData = rememberLoader(region, range, scope, geoTab) {
        val fields = when (geoTab) {
            0 -> listOf("srcCountry"); 1 -> listOf("dstCountry")
            2 -> listOf("srcIsp", "srcAsn"); else -> listOf("dstIsp", "dstAsn")
        }
        coroutineScope { fields.map { f -> async { runCatching { api.statBy(region, range, scope, f) }.getOrDefault(emptyList()) } }.map { it.await() } }
    }

    // Profile / endpoint filters stay pinned above the scrolling stats, like the Activity log's filters.
    Column {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CdChip(
                onClick = { pick = "profile" },
                label = { Text(profile?.name ?: "All Profiles", maxLines = 1) },
                leadingIcon = { Icon(Solar.Tuning, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Solar.ExpandMore, null, Modifier.size(18.dp)) },
            )
            CdChip(
                onClick = { pick = "endpoint" },
                label = { Text(endpoint?.name ?: "All Endpoints", maxLines = 1) },
                leadingIcon = {
                    val e = endpoint
                    if (e != null) DeviceGlyph(e.icon, 18) else Icon(Solar.Devices, null, Modifier.size(18.dp))
                },
                trailingIcon = { Icon(Solar.ExpandMore, null, Modifier.size(18.dp)) },
            )
        }
        actionTabs.firstOrNull { it.first == refine }?.let { (act, label) ->
            Spacer(Modifier.height(6.dp))
            val c = statColor(act)
            CdChip(
                selected = true, onClick = { refine = null }, color = c,
                label = { Text(label) },
                leadingIcon = { StatGlyph("stat-" + label.lowercase(), 16, c) },
                trailingIcon = { Icon(Solar.Close, "Remove refinement", Modifier.size(16.dp)) },
            )
        }
        protocols.firstOrNull { it.first == protocol }?.let { (_, label, icon) ->
            Spacer(Modifier.height(6.dp))
            CdChip(
                selected = true, onClick = { protocol = null },
                label = { Text(label) },
                leadingIcon = { StatGlyph(icon, 16, Palette.Teal) },
                trailingIcon = { Icon(Solar.Close, "Remove refinement", Modifier.size(16.dp)) },
            )
        }
    }
    val statsList = androidx.compose.foundation.lazy.rememberLazyListState()
    LoaderBox(overview, onRefresh = onRefresh, isScrolled = { statsList.isScrolledDown() }) { o ->
        val totals = remember(o.series) {
            val t = mutableMapOf<Int, Long>()
            o.series.forEach { p -> p.counts.forEach { (a, c) -> t[a] = (t[a] ?: 0) + c } }
            t
        }
        val total = totals.values.sum()
        val blocked = totals[Do.BLOCK] ?: 0
        LazyColumn(state = statsList, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp + LocalBottomBarSpace.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                    // Tapping an action tile refines the whole page to that action (tap again to clear), as on the dashboard.
                    fun toggle(act: Int) {
                        refine = if (refine == act) null else act
                        if (refine != null) actionTab = actionTabs.indexOfFirst { it.first == act }
                    }
                    StatTile("Blocked", blocked, total, "stat-blocked", StatBlocked, Modifier.weight(1f), refine == Do.BLOCK) { toggle(Do.BLOCK) }
                    StatTile("Bypassed", totals[Do.BYPASS] ?: 0, total, "stat-bypassed", StatBypassed, Modifier.weight(1f), refine == Do.BYPASS) { toggle(Do.BYPASS) }
                    StatTile("Redirected", (totals[Do.REDIRECT] ?: 0) + (totals[Do.SPOOF] ?: 0), total, "stat-redirected", StatRedirected, Modifier.weight(1f), refine == Do.REDIRECT) { toggle(Do.REDIRECT) }
                    StatTile("Total", total, null, "stat-total", StatTotal, Modifier.weight(1f))
                }
            }
            item {
                CdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("SECURITY OVERVIEW", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, letterSpacing = 1.sp)
                        Spacer(Modifier.height(10.dp))
                        SecurityRow(
                            "sec-encrypted", null, "Encrypted DNS", o.encrypted, total,
                            "Share of queries sent over an encrypted protocol (DoH, DoT, DoQ, DoH3).",
                            menu = { close ->
                                protocols.forEach { (key, label, icon) ->
                                    CdMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = { StatGlyph(icon, 18, if (protocol == key) Palette.Teal else Palette.Muted) },
                                        trailingIcon = { if (protocol == key) Icon(Solar.Check, null, tint = Palette.Teal) },
                                        onClick = { protocol = if (protocol == key) null else key; close() },
                                    )
                                }
                            },
                        )
                        HorizontalDivider(color = Palette.Outline, modifier = Modifier.padding(vertical = 10.dp))
                        SecurityRow("sec-benign", null, "Benign Blocks", blocked - o.malware, blocked, "Share of blocked queries that were not malware.")
                        HorizontalDivider(color = Palette.Outline, modifier = Modifier.padding(vertical = 10.dp))
                        SecurityRow(
                            null, o.homeCountry, "Home Country Traffic", o.home, total,
                            "Share of queries coming from ${o.homeCountry?.let { countryName(it) } ?: "your country"}.",
                        )
                    }
                }
            }
            item {
                CdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DNS Queries", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            SlideSelector(
                                listOf(SlideOption("Line", Solar.ShowChart), SlideOption("Bars", Solar.BarChart)),
                                if (bars) 1 else 0, { bars = it == 1 }, Modifier.width(190.dp), height = 38.dp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (o.series.isEmpty()) Text("No data for this period", color = Palette.Muted)
                        else QueriesChart(o.series, range, bars)
                    }
                }
            }
            item {
                EndpointsCard(
                    endpointMode, { endpointMode = it }, endpointsData, activity.data ?: devices.data.orEmpty(),
                    filterNames.data, endpoint?.pk,
                ) { endpoint = it }
            }
            item {
                TabbedCard(actionTabs.map { it.second }, actionTab, { actionTab = it }, statColor(actionTabs[actionTab].first)) {
                    val color = statColor(actionTabs[actionTab].first)
                    val d = actionData.data
                    when {
                        d == null && actionData.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        d == null -> Text(actionData.error ?: "", color = Palette.Red)
                        else -> {
                            if (actionTabs[actionTab].first == Do.BLOCK) RankSection("Filters", d[0], color) { FilterLabel(it.value, filterNames.data) }
                            RankSection("Services", d[1], color) {
                                val name = services.data?.get(it.value)?.name ?: it.value
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ServiceIcon(it.value, name, Palette.Muted, size = 24)
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            RankSection("Domains", d[2], color) { DomainLabel(it.value) }
                            RankSection("Rules", d[3], color, empty = "No custom rules triggered") { DomainLabel(it.value) }
                        }
                    }
                }
            }
            item {
                TabbedCard(geoTabs, geoTab, { geoTab = it }, Palette.Blue) {
                    val d = geoData.data
                    when {
                        d == null && geoData.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        d == null -> Text(geoData.error ?: "", color = Palette.Red)
                        geoTab < 2 -> {
                            val list = d[0]
                            RankSection(if (geoTab == 0) "Sources" else "Destinations", list, Palette.Blue) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FlagIcon(it.value, 20)
                                    Spacer(Modifier.width(8.dp))
                                    Text(countryName(it.value), maxLines = 1)
                                }
                            }
                            if (list.isNotEmpty()) Text(
                                "${list.size}${if (list.size >= 10) "+" else ""} ${if (geoTab == 0) "source" else "destination"} countries in this range",
                                style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                            )
                        }
                        else -> {
                            RankSection("Networks", d[0], Palette.Blue) { DomainLabel(it.value.ifBlank { "Unknown" }) }
                            RankSection("ASNs", d.getOrElse(1) { emptyList() }, Palette.Blue) { DomainLabel("AS${it.value}") }
                        }
                    }
                }
            }
        }
    }
    }

    when (pick) {
        "endpoint" -> PickerDialog(
            "Endpoint", listOf<Device?>(null) + devices.data.orEmpty(),
            label = { it?.name ?: "All Endpoints" }, selected = { it?.pk == endpoint?.pk }, onDismiss = { pick = null },
        ) { endpoint = it }
        "profile" -> PickerDialog(
            "Profile", listOf<Profile?>(null) + profiles.data.orEmpty(),
            label = { it?.name ?: "All Profiles" }, selected = { it?.pk == profile?.pk }, onDismiss = { pick = null },
        ) { profile = it }
    }
}

private fun pct(part: Long, whole: Long): String =
    if (whole <= 0) "-" else "%.1f%%".format(part.coerceIn(0, whole) * 100.0 / whole).replace(".0%", "%")

/** Bundled icon (assets/device_icons), tinted. */
@Composable
private fun StatGlyph(name: String, size: Int, tint: Color) {
    coil3.compose.AsyncImage(
        model = "file:///android_asset/device_icons/$name.svg", contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint), modifier = Modifier.size(size.dp),
    )
}

/** Green when healthy, orange when middling, red when poor; grey when there's nothing to measure. */
private fun securityColor(share: Double?) = when {
    share == null || share <= 0.0 -> Palette.Muted
    share >= 80 -> StatBypassed
    share >= 50 -> StatRedirected
    else -> StatBlocked
}

/** One Security Overview line, as on the dashboard: icon, name, (menu), info, percentage and a progress bar. */
@Composable
private fun SecurityRow(
    icon: String?,
    flag: String?,
    label: String,
    part: Long,
    whole: Long,
    info: String,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val share = if (whole > 0) part.coerceIn(0, whole) * 100.0 / whole else null
    val color = securityColor(share)
    var open by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flag != null) FlagIcon(flag, 18) else if (icon != null) StatGlyph(icon, 18, Palette.Text)
            Spacer(Modifier.width(8.dp))
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).then(if (menu != null) Modifier.clickable { open = true } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    if (menu != null) Icon(if (open) Solar.ExpandLess else Solar.ExpandMore, "Refine by protocol", Modifier.size(20.dp))
                }
                if (menu != null) CdMenu(open, { open = false }) { menu { open = false } }
            }
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(28.dp)) {
                Icon(Solar.Info, "About $label", Modifier.size(15.dp), tint = Palette.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text(share?.let { "%.1f%%".format(it).replace(".0%", "%") } ?: "-", color = color, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(((share ?: 0.0) / 100).toFloat()).clip(RoundedCornerShape(50)).background(color))
        }
    }
    if (showInfo) CdDialog(
        onDismissRequest = { showInfo = false }, title = { Text(label) }, text = { Text(info) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
    )
}

/** Card with a row of tabs on top, like the dashboard's Blocked/Bypassed/Redirected and Sources/Destinations panels. */
@Composable
private fun TabbedCard(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    CdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, t ->
                    val on = i == selected
                    Text(
                        t,
                        color = if (on) Palette.Bg else Palette.Muted,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) accent else Palette.Bg)
                            .clickable { onSelect(i) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun RankSection(title: String, items: List<CountItem>, color: Color, empty: String = "Nothing in this period", label: @Composable (CountItem) -> Unit) {
    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    if (items.isEmpty()) Text(empty, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
    val top = items.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    items.take(10).forEach { item ->
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(34.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(item.count.toFloat() / top).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.16f)))
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { label(item) }
                Text(compact(item.count), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** One row of the Endpoints panel. */
private data class EndpointStat(val id: String, val count: Long, val trend: TrendItem?, val topFilter: String?)

/**
 * Dashboard's Endpoints panel: Trending (vs previous period), Top and Bottom by query count.
 * Each row: activity icon, name, clients, top blocking filter's icon, change. Tap a row to Refine (filter by it).
 */
@Composable
private fun EndpointsCard(
    mode: Int,
    onMode: (Int) -> Unit,
    data: Loader<List<EndpointStat>>,
    devices: List<Device>,
    filterNames: Map<String, String>?,
    selected: String?,
    onRefine: (Device?) -> Unit,
) {
    var hint by remember { mutableStateOf<String?>(null) }
    TabbedCard(listOf("Trending", "Top", "Bottom"), mode, onMode, Palette.Teal) {
        val rows = data.data
        when {
            rows == null && data.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            rows == null -> Text(data.error ?: "", color = Palette.Red)
            else -> {
                val sorted = if (mode == 0) rows.sortedByDescending { r -> r.trend?.let { change(it) } ?: 0.0 } else rows
                sorted.forEach { r ->
                    val d = devices.firstOrNull { it.pk == r.id }
                    val isSel = selected == r.id
                    Row(
                        Modifier.fillMaxWidth().clip(CdShape)
                            .background(if (isSel) Palette.Teal.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { onRefine(if (isSel) null else d) }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (d != null) DeviceIconWithActivity(d, 22) else Icon(Solar.Devices, null, tint = Palette.Muted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d?.name ?: r.id, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (isSel) "Refined · tap to clear" else "${compact(r.count)} queries",
                                style = MaterialTheme.typography.labelSmall, color = if (isSel) Palette.Teal else Palette.Muted,
                            )
                        }
                        // Clients
                        Row(Modifier.width(44.dp), verticalAlignment = Alignment.CenterVertically) {
                            coil3.compose.AsyncImage(
                                model = "file:///android_asset/device_icons/clients.svg", contentDescription = "Clients",
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Palette.Muted), modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("${d?.clients ?: 0}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        // Top blocking filter
                        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                            val tf = r.topFilter
                            if (tf == null) Text("-", color = Palette.Muted)
                            else {
                                val name = filterNames?.get(tf) ?: tf
                                Box(Modifier.clip(CircleShape).clickable { hint = "${d?.name ?: r.id}: most blocked by $name" }.padding(4.dp)) {
                                    Icon(filterIconFor(tf, filterNames), null, Modifier.size(18.dp), tint = Palette.Muted)
                                }
                            }
                        }
                        Box(Modifier.width(74.dp), contentAlignment = Alignment.CenterEnd) {
                            val t = r.trend
                            if (t != null) TrendValue(t) else Text(compact(r.count), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                val active = rows.count { it.count > 0 }
                Text(
                    "$active of ${devices.size.coerceAtLeast(active)} endpoints active in this range · tap one to refine",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    hint?.let { CdDialog(onDismissRequest = { hint = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { hint = null }) { Text("OK") } }) }
}

/** Dashboard style: ↗ green when up (no previous traffic counts as +100%), ↘ red when down. */
@Composable
private fun TrendValue(t: TrendItem) {
    val c = change(t)
    val up = c >= 0
    // Dashboard's colours; the down arrow is the same glyph flipped.
    val color = if (up) StatBypassed else StatBlocked
    Row(verticalAlignment = Alignment.CenterVertically) {
        coil3.compose.AsyncImage(
            model = "file:///android_asset/device_icons/trend.svg", contentDescription = if (up) "Up" else "Down",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
            modifier = Modifier.size(8.dp).then(if (up) Modifier else Modifier.scale(1f, -1f)),
        )
        Spacer(Modifier.width(4.dp))
        Text("%.1f%%".format(kotlin.math.abs(c)).replace(".0%", "%"), color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Percent change vs the previous period; an endpoint with no earlier traffic is +100%, like the dashboard. */
private fun change(t: TrendItem) = if (t.baseline > 0) (t.current - t.baseline) * 100.0 / t.baseline else if (t.current > 0) 100.0 else 0.0

/** Icon for a filter id seen in analytics: mode ids (ads_medium) use their parent's icon. */
private fun filterIconFor(id: String, names: Map<String, String>? = null): ImageVector {
    val base = filterParentCache[id] ?: id
    return filterIcon(base, names?.get(base) ?: names?.get(id).orEmpty())
}

/** Filled from Session.filterParent while loading; mode id -> native filter id. */
private val filterParentCache = java.util.concurrent.ConcurrentHashMap<String, String>()

@Composable
private fun StatTile(
    label: String, value: Long, total: Long?, icon: String, color: Color, modifier: Modifier,
    selected: Boolean = false, onClick: (() -> Unit)? = null,
) {
    val m = if (selected) modifier.border(1.5.dp, color, CdShape) else modifier
    CdCard(m, onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                StatGlyph(icon, 20, color)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                val share = if (total != null && total > 0) " (%.1f%%)".format(value * 100.0 / total) else ""
                Text(compact(value) + share, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Line chart of queries per action, with a touch crosshair that reads out every series at that time. */
@Composable
private fun QueriesChart(points: List<SeriesPoint>, range: TimeRange, bars: Boolean = false) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Palette.Muted, fontSize = 10.sp)
    val maxY = remember(points, bars) {
        niceMax(points.maxOfOrNull { p -> if (bars) chartSeries.sumOf { p.counts[it.action] ?: 0 } else chartSeries.maxOf { p.counts[it.action] ?: 0 } } ?: 0)
    }
    val fmt = remember(range) { SimpleDateFormat(if (range.seconds <= 86_400) "HH:mm" else "d MMM", Locale.getDefault()) }
    val fullFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    // Readout: hovered bucket, or the whole period when nothing is selected.
    val sel = selected?.let { points.getOrNull(it) }
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            sel?.let { fullFmt.format(Date(it.epochMs)) } ?: "Touch the chart for details",
            style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f),
        )
        if (sel != null) chartSeries.forEach { d ->
            Box(Modifier.size(8.dp).clip(CircleShape).background(d.color))
            Spacer(Modifier.width(4.dp))
            Text(compact(sel.counts[d.action] ?: 0), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(10.dp))
        }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(points) {
                detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), 36.dp.toPx(), points.size) }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(onDragEnd = {}) { change, _ ->
                    selected = indexAt(change.position.x, size.width.toFloat(), 36.dp.toPx(), points.size)
                }
            },
    ) {
        val left = 36.dp.toPx()
        val bottom = 18.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        val stepX = if (points.size > 1) w / (points.size - 1) else 0f
        fun x(i: Int) = if (bars) left + (i + 0.5f) * (w / points.size) else left + i * stepX
        fun y(v: Long) = h - (v.toFloat() / maxY) * h

        // Recessive grid + y labels
        for (g in 0..3) {
            val v = maxY * g / 3
            val yy = y(v)
            drawLine(Palette.Outline.copy(alpha = 0.6f), Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
            val t = measurer.measure(compact(v), axisStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
        }
        // x labels: start, middle, end
        listOf(0, points.size / 2, points.size - 1).distinct().forEach { i ->
            val t = measurer.measure(fmt.format(Date(points[i].epochMs)), axisStyle)
            val tx = (x(i) - t.size.width / 2).coerceIn(left, size.width - t.size.width)
            drawText(t, topLeft = Offset(tx, h + 4.dp.toPx()))
        }
        if (bars) {
            // Stacked bars per bucket (blocked at the bottom), with a 2px gap between bars.
            val slot = w / points.size
            val bw = (slot - 2.dp.toPx()).coerceAtLeast(1f)
            points.forEachIndexed { i, p ->
                var top = h
                val bx = left + i * slot + (slot - bw) / 2
                chartSeries.forEach { d ->
                    val v = p.counts[d.action] ?: 0
                    if (v > 0) {
                        val bh = (v.toFloat() / maxY) * h
                        drawRect(d.color.copy(alpha = if (selected == null || selected == i) 1f else 0.45f), Offset(bx, top - bh), androidx.compose.ui.geometry.Size(bw, bh))
                        top -= bh
                    }
                }
            }
        } else chartSeries.forEach { d ->
            val path = Path()
            points.forEachIndexed { i, p ->
                val px = x(i); val py = y(p.counts[d.action] ?: 0)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, d.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Crosshair
        if (!bars) selected?.let { i ->
            drawLine(Palette.Muted.copy(alpha = 0.7f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.dp.toPx())
            chartSeries.forEach { d ->
                val c = Offset(x(i), y(points[i].counts[d.action] ?: 0))
                drawCircle(Palette.Card, radius = 6.dp.toPx(), center = c)
                drawCircle(d.color, radius = 4.dp.toPx(), center = c)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        chartSeries.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 12.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(d.color))
                Spacer(Modifier.width(6.dp))
                Text(d.label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
        }
    }
}

/** Maps a touch x to the nearest bucket; [left] is the y-axis gutter in px. */
private fun indexAt(x: Float, width: Float, left: Float, n: Int): Int? {
    if (n == 0) return null
    val frac = ((x - left) / (width - left)).coerceIn(0f, 1f)
    return (frac * (n - 1)).roundToInt()
}

/** Rounds up to a 3-gridline axis max whose step is 1, 2 or 5 x 10^k. */
private fun niceMax(v: Long): Long {
    if (v <= 0) return 3
    val raw = v / 3.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return max(3L, (step * 3).toLong())
}
fun compact(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    // One decimal like the dashboard ("67.2K"), dropping a trailing ".0".
    v >= 1_000 -> "%.1fK".format(v / 1000.0).replace(".0K", "K")
    else -> v.toString()
}

@Composable
private fun RankCard(title: String, items: List<CountItem>, color: Color, label: @Composable (CountItem) -> Unit) {
    CdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) Text("Nothing in this period", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            val top = items.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
            items.take(10).forEach { item ->
                Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(34.dp)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(item.count.toFloat() / top)
                            .clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.16f)),
                    )
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { label(item) }
                        Text(compact(item.count), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainLabel(d: String) =
    Text(d.trimEnd('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)

private val filterNames = mapOf(
    "ads" to "Ads & Trackers", "ads_small" to "Ads & Trackers (Relaxed)", "ads_medium" to "Ads & Trackers (Balanced)",
    "porn" to "Adult Content", "porn_strict" to "Adult Content (Strict)", "ai_malware" to "AI Malware",
    "fakenews" to "Clickbait", "cryptominers" to "Crypto", "dating" to "Dating", "drugs" to "Drugs",
    "ddns" to "Dynamic DNS", "filehost" to "File Hosting", "gambling" to "Gambling", "games" to "Games",
    "gov" to "Government", "iot" to "IoT Telemetry", "malware" to "Malware", "ip_malware" to "Malware (Balanced)",
    "nrd" to "New Domains", "typo" to "Phishing", "social" to "Social", "torrents" to "Torrents",
    "urlshort" to "URL Shorteners", "dnsvpn" to "VPN & DNS",
)

@Composable
private fun FilterLabel(pk: String, names: Map<String, String>? = null) = Text(
    names?.get(pk) ?: filterNames[pk] ?: pk.removePrefix("x-").split('-', '_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
    maxLines = 1, overflow = TextOverflow.Ellipsis,
)

private fun countryName(cc: String) =
    if (cc.length == 2) Locale("", cc).displayCountry.ifBlank { cc } else cc.ifBlank { "Unknown" }

// ---------- Activity log (mirrors dashboard/activity-log) ----------

private val logActions = listOf(Do.BLOCK to "Blocked", Do.BYPASS to "Bypassed", Do.REDIRECT to "Redirected", -1 to "Failed")
private val logRcodes = listOf(0 to "NOERROR", 2 to "SERVFAIL", 3 to "NXDOMAIN", 5 to "REFUSED")
private val logRrTypes = listOf("A", "AAAA", "CNAME", "HTTPS", "MX", "NS", "PTR", "SOA", "SRV", "SVCB", "TXT")

/** One choice in a filter sheet; [group] puts it under a header (Locations are grouped by country). */
private data class LogOpt(
    val key: String?,
    val label: String,
    val sub: String? = null,
    val group: String? = null,
    val groupIcon: (@Composable () -> Unit)? = null,
    val icon: (@Composable () -> Unit)? = null,
)

@Composable
private fun ActivityLogView(
    nav: NavHostController, region: String, range: TimeRange, onRefresh: () -> Unit,
    setHeaderActions: ((@Composable () -> Unit)?) -> Unit,
) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val runner = rememberRunner()
    var query by remember { mutableStateOf(LogQuery()) }
    val search = rememberBottomSearch()
    val q = search.query
    BottomSearch(search, "Search queries")
    var entries by remember(region, range, query) { mutableStateOf<List<LogEntry>>(emptyList()) }
    var page by remember(region, range, query) { mutableIntStateOf(0) }
    var done by remember(region, range, query) { mutableStateOf(false) }
    var loading by remember(region, range, query) { mutableStateOf(false) }
    var error by remember(region, range, query) { mutableStateOf<String?>(null) }
    var selected by remember(region, range, query) { mutableStateOf<Set<Int>>(emptySet()) }
    var open by remember { mutableStateOf<LogEntry?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var ruleFor by remember { mutableStateOf<Pair<List<String>, String?>?>(null) }
    val devices = rememberLoader { runCatching { session.api.devices() }.getOrDefault(emptyList()) }
    val activity = rememberLoader { runCatching { session.devicesWithActivity(regionOverride(ctx)) }.getOrDefault(emptyList()) }
    val services = rememberLoader { runCatching { session.allServices() }.getOrDefault(emptyMap()) }
    val filterNames = rememberLoader { runCatching { session.filterNames() }.getOrDefault(emptyMap()) }
    val proxies = rememberLoader { runCatching { session.proxies() }.getOrDefault(emptyList()) }
    val current = rememberLoader(devices.data) { devices.data?.let { runCatching { session.currentEndpointId(ctx, it) }.getOrNull() } }
    val allDevices = activity.data?.takeIf { it.isNotEmpty() } ?: devices.data.orEmpty()
    val deviceOf = { id: String -> allDevices.firstOrNull { it.pk == id } }
    val endpointName = { e: LogEntry -> e.endpointName.ifBlank { deviceOf(e.endpointId)?.name ?: e.endpointId } }
    val clientName = { e: LogEntry ->
        e.clientId.takeIf { it.isNotBlank() }?.let { id -> deviceOf(e.endpointId)?.clientList?.firstOrNull { it.id == id }?.label ?: id }
    }
    val listState = rememberLazyListState()

    // Search runs on the server (searchQuestion), shortly after typing stops.
    LaunchedEffect(q) {
        if (q.trim() != query.search) {
            kotlinx.coroutines.delay(500)
            query = query.copy(search = q.trim())
        }
    }

    // Pages load in a screen-wide scope: the effects that trigger them restart on every scroll,
    // which would otherwise cancel a page mid-request.
    val loadScope = rememberCoroutineScope()
    fun loadNext() {
        if (loading || done) return
        loading = true; error = null
        loadScope.launch { try {
            val next = session.api.activityLog(region, range, page, query)
            // Mode ids (ads_small) are drawn with their parent filter's icon.
            next.filter { it.trigger == "filter" && !filterParentCache.containsKey(it.triggerValue) }.map { it.triggerValue }.distinct().forEach { id ->
                runCatching { session.filterParent(id) }.getOrNull()?.let { filterParentCache[id] = it }
            }
            entries = entries + next
            page++
            if (next.isEmpty()) done = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        } }
    }

    // A new filter, search or time range starts the list from the top (not on first show or coming back).
    val firstLoad = remember { booleanArrayOf(true) }
    LaunchedEffect(region, range, query) {
        if (firstLoad[0]) firstLoad[0] = false else listState.scrollToItem(0)
        loadNext()
    }
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(nearEnd, entries.size) {
        if (entries.isNotEmpty() && nearEnd >= entries.size - 5) loadNext()
    }

    val csvSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) runner.run("Exported ${entries.size} queries") {
            val csv = logCsv(entries, endpointName, clientName)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: throw IllegalStateException("Couldn't write the file")
        }
    }

    // Search, export CSV and copy sit in the Analytics header, all in the same style.
    val currentEndpointName by rememberUpdatedState(endpointName)
    val currentClientName by rememberUpdatedState(clientName)
    // entries is re-created when the filters change, so read it through an updated reference.
    val currentEntries by rememberUpdatedState(entries)
    DisposableEffect(Unit) {
        setHeaderActions {
            val has = currentEntries.isNotEmpty()
            SearchButton(search)
            IconButton(onClick = { csvSaver.launch("activity-log.csv") }, enabled = has) {
                StatGlyph("log-csv", 22, if (has) Palette.Text else Palette.Muted.copy(alpha = 0.5f))
            }
            IconButton(onClick = {
                copyToClipboard(ctx, "Activity log", logCsv(currentEntries, currentEndpointName, currentClientName))
            }, enabled = has) { StatGlyph("log-copy", 22, if (has) Palette.Text else Palette.Muted.copy(alpha = 0.5f)) }
        }
        onDispose { setHeaderActions(null) }
    }

    Column {
        if (selected.isNotEmpty()) {
            // Selection mode (long-press a row), like the dashboard's checkboxes.
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Solar.Close, "Close selection mode") }
                Text("${selected.size} selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                CdButton("Create Rule", {
                    val picked = selected.sorted().mapNotNull { entries.getOrNull(it) }
                    ruleFor = picked.map { it.domain }.distinct() to picked.firstOrNull { it.profileId.isNotBlank() }?.profileId
                }, iconContent = { StatGlyph("log-edit", 18, Palette.Teal) }, tint = Palette.Teal, height = 44.dp, fontSize = 15.sp)
            }
        }

        // Filter bar, same order as the dashboard.
        Row(
            Modifier.horizontalScrollFaded().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            val ep = query.endpointId?.let(deviceOf)
            LogFilterChip(ep?.name ?: "All Endpoints", query.endpointId != null, icon = ep?.let { { DeviceGlyph(it.icon, 16) } }) { sheet = "endpoint" }
            val client = query.clientId?.let { id -> ep?.clientList?.firstOrNull { it.id == id }?.label ?: id }
            LogFilterChip(client ?: "Clients", query.clientId != null, enabled = query.endpointId != null) { sheet = "client" }
            val act = logActions.firstOrNull { it.first == query.action }
            LogFilterChip(act?.second ?: "All Actions", act != null, icon = act?.let { { LogActionGlyph(it.first, 16) } }) { sheet = "action" }
            val proto = protocols.firstOrNull { it.first == query.protocol }
            LogFilterChip(proto?.second ?: "All Protocols", proto != null, icon = proto?.let { { StatGlyph(it.third, 16, Palette.Text) } }) { sheet = "protocol" }
            LogFilterChip(logRcodes.firstOrNull { it.first == query.statusCode }?.second ?: "RCODEs", query.statusCode != null) { sheet = "rcode" }
            LogFilterChip(query.rrType ?: "RTYPEs", query.rrType != null) { sheet = "rrtype" }
            val trig = query.trigger
            val filterId = trig?.takeIf { it.first == "filter" }?.second
            LogFilterChip(
                filterId?.let { filterDisplayName(it, filterNames.data) } ?: "Filters", filterId != null,
                icon = filterId?.let { { Icon(filterIconFor(it), null, Modifier.size(16.dp), tint = Palette.Text) } },
            ) { sheet = "filter" }
            val serviceId = trig?.takeIf { it.first == "service" }?.second
            LogFilterChip(
                serviceId?.let { services.data?.get(it)?.name ?: it } ?: "Services", serviceId != null,
                icon = serviceId?.let { { ServiceIcon(it, it, Palette.Text, size = 16) } },
            ) { sheet = "service" }
            val loc = query.spoofTarget?.let { pk -> proxies.data?.firstOrNull { it.pk == pk } }
            LogFilterChip(
                loc?.city?.ifBlank { null } ?: query.spoofTarget ?: "Locations", query.spoofTarget != null,
                icon = loc?.let { { FlagIcon(it.country, 16) } },
            ) { sheet = "location" }
            if (query.anyFilter) {
                IconButton(onClick = { query = LogQuery(search = query.search) }, modifier = Modifier.size(36.dp)) {
                    StatGlyph("log-clear", 20, Palette.Muted)
                }
            }
        }

        when {
            entries.isEmpty() && error != null -> ErrorState(error!!) { loadNext() }
            entries.isEmpty() && !done -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            // Pull down to fetch the latest queries (the range moves to "now", which restarts the list).
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = loading && entries.isEmpty(), onRefresh = onRefresh, modifier = Modifier.fillMaxSize(),
            ) {
                TopScrollFade(isScrolled = { listState.isScrolledDown() }) {
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp + LocalBottomBarSpace.current), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entries.isEmpty()) item { EmptyState(Solar.Sad, "No queries match your search criteria") }
                    items(entries.size) { i ->
                        val e = entries[i]
                        val isSel = i in selected
                        LogRow(
                            e, deviceOf(e.endpointId), endpointName(e), clientName(e), services.data,
                            selecting = selected.isNotEmpty(), selected = isSel,
                            onClick = { if (selected.isNotEmpty()) selected = if (isSel) selected - i else selected + i else open = e },
                            onLongClick = { selected = if (isSel) selected - i else selected + i },
                            onInfo = { open = e },
                            redirect = proxies.data?.firstOrNull { it.pk == e.spoofTarget },
                        )
                    }
                    if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                    error?.let { item { Text(it, color = Palette.Red, style = MaterialTheme.typography.bodySmall) } }
                }
                }
            }
        }
    }

    // ----- Filter sheets -----
    when (sheet) {
        "endpoint" -> LogOptionSheet(
            "Endpoints", listOf(LogOpt(null, "All Endpoints", icon = { Icon(Solar.Devices, null, Modifier.size(22.dp)) })) +
                allDevices.sortedBy { it.name.lowercase() }.map { d ->
                    val sub = listOfNotNull(
                        "Current Device".takeIf { d.pk == current.data },
                        d.clientList.size.takeIf { it > 0 }?.let { "$it Client" + if (it == 1) "" else "s" },
                    ).joinToString(" · ").ifBlank { null }
                    LogOpt(d.pk, d.name, sub, icon = { DeviceGlyph(d.icon, 22) })
                },
            query.endpointId, searchable = true, onDismiss = { sheet = null },
        ) { query = query.copy(endpointId = it, clientId = null) }
        "client" -> LogOptionSheet(
            "Clients", listOf(LogOpt(null, "All Clients")) +
                query.endpointId?.let(deviceOf)?.clientList.orEmpty().sortedBy { it.label.lowercase() }.map { c ->
                    LogOpt(c.id, c.label, c.ip.ifBlank { null }, icon = { Icon(Solar.Cpu, null, Modifier.size(22.dp), tint = Palette.Muted) })
                },
            query.clientId, searchable = true, onDismiss = { sheet = null },
        ) { query = query.copy(clientId = it) }
        "action" -> LogOptionSheet(
            "Actions", listOf(LogOpt(null, "All Actions")) + logActions.map { (a, l) -> LogOpt("$a", l, icon = { LogActionGlyph(a, 20) }) },
            query.action?.toString(), onDismiss = { sheet = null },
        ) { query = query.copy(action = it?.toInt()) }
        "protocol" -> LogOptionSheet(
            "Protocols", listOf(LogOpt(null, "All Protocols")) + protocols.map { (k, l, icon) -> LogOpt(k, l, icon = { StatGlyph(icon, 20, Palette.Muted) }) },
            query.protocol, onDismiss = { sheet = null },
        ) { query = query.copy(protocol = it) }
        "rcode" -> LogOptionSheet(
            "RCODEs", listOf(LogOpt(null, "All RCODEs")) + logRcodes.map { (c, l) -> LogOpt("$c", l) },
            query.statusCode?.toString(), onDismiss = { sheet = null },
        ) { query = query.copy(statusCode = it?.toInt()) }
        "rrtype" -> LogOptionSheet(
            "RTYPEs", listOf(LogOpt(null, "All RTYPEs")) + logRrTypes.map { LogOpt(it, it) },
            query.rrType, onDismiss = { sheet = null },
        ) { query = query.copy(rrType = it) }
        "filter" -> {
            val names = filterNames.data.orEmpty()
            LaunchedEffect(names) { names.keys.forEach { id -> runCatching { session.filterParent(id) }.getOrNull()?.let { filterParentCache[id] = it } } }
            LogOptionSheet(
                "Filters", listOf(LogOpt(null, "All Filters")) + names.entries.sortedBy { it.value.lowercase() }.map { (id, name) ->
                    LogOpt(id, name, icon = { Icon(filterIcon(id, name), null, Modifier.size(20.dp), tint = Palette.Muted) })
                },
                query.trigger?.takeIf { it.first == "filter" }?.second, searchable = true, onDismiss = { sheet = null },
            ) { query = query.copy(trigger = it?.let { id -> "filter" to id }) }
        }
        "service" -> LogOptionSheet(
            "Services", listOf(LogOpt(null, "All Services")) + services.data.orEmpty().values.sortedBy { it.name.lowercase() }.map { s ->
                LogOpt(s.pk, s.name, icon = { ServiceIcon(s.pk, s.name, Palette.Muted, size = 22) })
            },
            query.trigger?.takeIf { it.first == "service" }?.second, searchable = true, onDismiss = { sheet = null },
        ) { query = query.copy(trigger = it?.let { id -> "service" to id }) }
        "location" -> LogOptionSheet(
            "Locations", listOf(LogOpt(null, "All Locations")) +
                proxies.data.orEmpty().sortedWith(compareBy({ it.countryName.ifBlank { it.country } }, { it.city })).map { p ->
                    LogOpt(p.pk, p.city.ifBlank { p.label }, group = p.countryName.ifBlank { p.country }, groupIcon = { FlagIcon(p.country, 20) })
                },
            query.spoofTarget, searchable = true, onDismiss = { sheet = null },
        ) { query = query.copy(spoofTarget = it) }
    }

    open?.let { e ->
        LogDetailSheet(
            e, deviceOf(e.endpointId), endpointName(e), clientName(e), services.data, filterNames.data,
            onDismiss = { open = null },
            onCreateRule = { open = null; ruleFor = listOf(e.domain) to e.profileId.ifBlank { null } },
            redirect = proxies.data?.firstOrNull { it.pk == e.spoofTarget },
        )
    }
    ruleFor?.let { (domains, pid) ->
        CreateRuleFromLogDialog(domains, pid, runner, onDismiss = { ruleFor = null }, onCreated = { selected = emptySet() })
    }
}

/** Dashboard-style filter dropdown: plain label with a chevron; bright when set. */
@Composable
private fun LogFilterChip(label: String, active: Boolean, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    CdChip(
        selected = active, onClick = onClick, enabled = enabled,
        label = { Text(label, maxLines = 1) },
        leadingIcon = icon,
        trailingIcon = { Icon(Solar.ExpandMore, null, Modifier.size(16.dp)) },
    )
}

@Composable
private fun LogOptionSheet(
    title: String, options: List<LogOpt>, selected: String?, searchable: Boolean = false,
    onDismiss: () -> Unit, onPick: (String?) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val shown = if (q.isBlank()) options else options.filter { it.key != null && (it.label.contains(q, true) || it.group?.contains(q, true) == true) }
    CdSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (searchable) { Spacer(Modifier.height(8.dp)); SearchField(q, "Search") { q = it } }
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            shown.forEachIndexed { i, o ->
                val prev = shown.getOrNull(i - 1)
                val next = shown.getOrNull(i + 1)
                // A group is a run of options with the same category; "All …" (no category) stands alone.
                val first = prev == null || prev.group != o.group || o.group == null && prev.key == null
                val last = next == null || next.group != o.group || o.key == null
                if (o.group != null && first) item(key = "g_${o.group}") { GroupHeader(o.group, o.groupIcon) }
                else if (first && i > 0) item(key = "s_$i") { Spacer(Modifier.height(10.dp)) }
                item(key = "o_${o.key}_$i") {
                    GroupRow(
                        o.label, first = first, last = last, subtitle = o.sub,
                        subtitleColor = if (o.sub?.startsWith("Current") == true) Palette.Teal else Palette.Muted,
                        icon = o.icon, selected = o.key == selected,
                    ) { onPick(o.key); onDismiss() }
                }
            }
        }
    }
}

private val stampFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())

/** Action mark as on the dashboard rows; Failed (-1) is the grey cross. */
@Composable
private fun LogActionGlyph(a: Int, size: Int) = when (a) {
    Do.BLOCK -> StatGlyph("stat-blocked", size, StatBlocked)
    Do.BYPASS -> StatGlyph("log-bypassed", size, StatBypassed)
    Do.REDIRECT, Do.SPOOF -> StatGlyph("stat-redirected", size, StatRedirected)
    else -> StatGlyph("log-failed", size, Palette.Muted)
}

private fun actionName(a: Int) = when (a) {
    Do.BLOCK -> "Blocked"; Do.BYPASS -> "Bypassed"; Do.SPOOF, Do.REDIRECT -> "Redirected"; -1 -> "Failed"; else -> "Resolved"
}

private fun protocolAsset(p: String) = protocols.firstOrNull { it.first == p.lowercase() }?.third ?: "proto-legacy"
private fun protocolName(p: String) = protocols.firstOrNull { it.first == p.lowercase() }?.second ?: p.uppercase()

private fun filterDisplayName(id: String, names: Map<String, String>?) =
    names?.get(id) ?: filterNames[id] ?: id.removePrefix("x-").split('-', '_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** What triggered the action: default rule flag, the filter's or service's icon, or a custom rule. */
@Composable
private fun TriggerGlyph(e: LogEntry, services: Map<String, Service>?, size: Int = 18) {
    when (e.trigger) {
        "default" -> StatGlyph("log-default", size, Palette.Muted)
        "filter" -> Icon(filterIconFor(e.triggerValue), null, Modifier.size(size.dp), tint = Palette.Muted)
        "service" -> ServiceIcon(e.triggerValue, services?.get(e.triggerValue)?.name ?: e.triggerValue, Palette.Muted, size = size)
        "custom" -> Icon(Solar.Rules, "Custom rule", tint = Palette.Muted, modifier = Modifier.size(size.dp))
        else -> {}
    }
}

private fun triggerLabel(e: LogEntry, services: Map<String, Service>?, filters: Map<String, String>?) = when (e.trigger) {
    "default" -> "Default Rule"
    "filter" -> "Filter: " + filterDisplayName(e.triggerValue, filters)
    "service" -> "Service: " + (services?.get(e.triggerValue)?.name ?: e.triggerValue)
    "custom" -> "Custom Rule" + e.triggerValue.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    else -> null
}

/** Registrable part of a hostname for favicon.controld.com (p.controld.com -> controld.com, a.b.co.uk -> b.co.uk). */
private fun faviconHost(d: String): String? {
    val parts = d.trimEnd('.').lowercase().split('.').filter { it.isNotEmpty() }
    if (parts.size < 2 || parts.last() in setOf("arpa", "lan", "local", "home", "internal", "localdomain")) return null
    val n = if (parts.size >= 3 && parts.last().length == 2 && parts[parts.size - 2].length <= 3) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

/** Site icon as the dashboard shows it, or the first letter in a grey circle when there is none. */
@Composable
private fun Favicon(domain: String, size: Int = 20) {
    val host = remember(domain) { faviconHost(domain) }
    // 0 loading, 1 loaded, 2 none: the letter shows until (or unless) the icon arrives.
    var state by remember(host) { mutableIntStateOf(if (host == null) 2 else 0) }
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (state != 1) Box(Modifier.fillMaxSize().clip(CircleShape).background(Palette.Muted.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Text(domain.firstOrNull()?.uppercase() ?: "?", fontSize = (size * 0.55f).sp, color = Palette.Text, fontWeight = FontWeight.SemiBold)
        }
        if (state != 2) coil3.compose.AsyncImage(
            model = "https://favicon.controld.com/$host", contentDescription = null,
            onSuccess = { state = 1 }, onError = { state = 2 },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    e: LogEntry, device: Device?, endpoint: String, client: String?, services: Map<String, Service>?,
    selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onInfo: () -> Unit,
    /** Where a redirected query was sent; its flag replaces the redirect globe, as on the dashboard. */
    redirect: Proxy? = null,
) {
    val shape = CdShape
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Palette.Teal.copy(alpha = 0.10f) else Palette.Card)
            .then(if (selected) Modifier.border(1.dp, Palette.Teal, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
    ) {
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(selected, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            StatGlyph(protocolAsset(e.protocol), 18, Palette.Muted)
            Spacer(Modifier.width(6.dp))
            if (e.rrType.isNotBlank()) Text(
                e.rrType, fontSize = 10.sp, color = Palette.Muted, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(44.dp).clip(RoundedCornerShape(50)).background(FieldFill).padding(vertical = 1.dp),
            )
            Box(Modifier.padding(horizontal = 7.dp).width(1.dp).height(14.dp).background(Palette.Outline))
            if (redirect != null && (e.action == Do.REDIRECT || e.action == Do.SPOOF)) FlagIcon(redirect.country, 17)
            else LogActionGlyph(e.action, 17)
            if (e.trigger in setOf("default", "filter", "service", "custom")) {
                Spacer(Modifier.width(7.dp))
                TriggerGlyph(e, services, 17)
            }
            Spacer(Modifier.width(8.dp))
            Favicon(e.domain, 18)
            Spacer(Modifier.width(7.dp))
            Text(e.domain, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stampFmt.format(Date(e.epochMs)), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                device?.let { DeviceGlyph(it.icon, 14, Palette.Muted); Spacer(Modifier.width(4.dp)) }
                Text(endpoint, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                client?.let {
                    Text("  •  ", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (e.country.isNotBlank()) {
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(12.dp).background(Palette.Outline))
                FlagIcon(e.country, 14)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
                StatGlyph("log-info", 15, Palette.Muted)
            }
        }
    }
}

private fun rcode(c: Int) = when (c) {
    0 -> "NOERROR"; 1 -> "FORMERR"; 2 -> "SERVFAIL"; 3 -> "NXDOMAIN"; 4 -> "NOTIMP"; 5 -> "REFUSED"; else -> "RCODE $c"
}

/** Loaded queries as CSV (Export / Copy), columns as the dashboard's export. */
private fun logCsv(entries: List<LogEntry>, endpoint: (LogEntry) -> String, client: (LogEntry) -> String?): String {
    fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    val head = listOf("Time", "Domain", "Type", "Action", "Trigger", "Trigger Value", "Protocol", "Response", "Endpoint", "Client", "Source IP", "Country", "City", "ISP")
    return buildString {
        appendLine(head.joinToString(","))
        entries.forEach { e ->
            appendLine(
                listOf(
                    e.timestamp, e.domain, e.rrType, actionName(e.action), e.trigger, e.triggerValue, protocolName(e.protocol), rcode(e.statusCode),
                    endpoint(e), client(e).orEmpty(), e.sourceIp, e.country, e.city, e.isp,
                ).joinToString(",") { cell(it) }
            )
        }
    }
}

@Composable
private fun LogDetailSheet(
    e: LogEntry, device: Device?, endpoint: String, client: String?,
    services: Map<String, Service>?, filters: Map<String, String>?,
    onDismiss: () -> Unit, onCreateRule: () -> Unit,
    redirect: Proxy? = null,
) {
    val ctx = LocalContext.current
    CdSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Favicon(e.domain, 24)
                Spacer(Modifier.width(10.dp))
                Text(e.domain, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { copyToClipboard(ctx, "Domain", e.domain) }) { Icon(Solar.ContentCopy, "Copy", tint = Palette.Muted) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogActionGlyph(e.action, 18)
                Spacer(Modifier.width(6.dp))
                Text(actionName(e.action), color = if (e.action == -1) Palette.Muted else statColor(e.action), fontWeight = FontWeight.SemiBold)
                triggerLabel(e, services, filters)?.let {
                    Spacer(Modifier.width(10.dp))
                    TriggerGlyph(e, services, 16)
                    Spacer(Modifier.width(4.dp))
                    Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (e.spoofTarget.isNotBlank() && (e.action == Do.REDIRECT || e.action == Do.SPOOF)) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlagIcon(redirect?.country, 18); Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Redirected to", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        Text(redirect?.label ?: e.spoofTarget, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Time", dateTimeFmt.format(Date(e.epochMs)))
            KeyValueRow("Type · Protocol · Response", listOf(e.rrType, protocolName(e.protocol), rcode(e.statusCode)).filter { it.isNotBlank() }.joinToString(" · "))
            if (e.answers.isNotEmpty()) {
                // The records Control D returned for this query (the IPs the device was told to connect to).
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Answers (${e.answers.size})", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        e.answers.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                    }
                    IconButton(onClick = { copyToClipboard(ctx, "Answers", e.answers.joinToString("\n")) }) {
                        Icon(Solar.ContentCopy, "Copy", tint = Palette.Muted)
                    }
                }
            }
            if (e.dstCountry.isNotBlank() || e.dstIsp.isNotBlank()) Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.dstCountry.isNotBlank()) { FlagIcon(e.dstCountry, 18); Spacer(Modifier.width(8.dp)) }
                Column {
                    Text("Destination", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text(
                        listOf(countryName(e.dstCountry).takeIf { e.dstCountry.isNotBlank() }.orEmpty(), e.dstIsp + (if (e.dstAsn > 0) " (AS ${e.dstAsn})" else ""))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("ENDPOINT", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, letterSpacing = 1.sp)
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                device?.let { DeviceGlyph(it.icon, 18); Spacer(Modifier.width(8.dp)) }
                Column {
                    Text("Name", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text(endpoint, style = MaterialTheme.typography.bodyMedium)
                }
            }
            client?.let { KeyValueRow("Client", it) }

            Spacer(Modifier.height(10.dp))
            Text("SOURCE", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, letterSpacing = 1.sp)
            if (e.sourceIp.isNotBlank()) KeyValueRow("IP", e.sourceIp) { copyToClipboard(ctx, "IP", e.sourceIp) }
            val loc = listOf(e.city, e.country).filter { it.isNotBlank() }.joinToString(", ")
            if (loc.isNotBlank()) Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FlagIcon(e.country, 18); Spacer(Modifier.width(8.dp))
                Column {
                    Text("Location", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text(loc, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (e.isp.isNotBlank()) KeyValueRow("ISP", e.isp + (if (e.asn > 0) " (AS ${e.asn})" else ""))

            Spacer(Modifier.height(16.dp))
            CdButton("Create Rule", onCreateRule, Modifier.fillMaxWidth(), iconContent = { StatGlyph("log-edit", 20, Palette.Teal) }, tint = Palette.Teal)
        }
    }
}
