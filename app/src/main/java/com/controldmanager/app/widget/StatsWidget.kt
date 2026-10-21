package com.controldmanager.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.controldmanager.app.MainActivity
import com.controldmanager.app.R
import com.controldmanager.app.api.ControlDApi
import com.controldmanager.app.api.Do
import com.controldmanager.app.api.StatScope
import com.controldmanager.app.api.TimeRange
import com.controldmanager.app.api.TokenStore
import com.controldmanager.app.ui.OpenRequest
import com.controldmanager.app.ui.compact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import androidx.glance.color.ColorProvider as DayNight

/** What a widget shows, chosen in [WidgetConfigActivity]; kept in the widget's own state. */
internal data class WidgetConfig(
    val metric: String = "blocked",
    val range: String = "24h",
    val profileId: String? = null,
    val profileName: String? = null,
    val endpointId: String? = null,
    val endpointName: String? = null,
    /** "app" follows the app's Appearance setting; "light" or "dark" fixes it. */
    val colours: String = "app",
    /** Whether the widget has a background at all, and how opaque it is (0 to 100). */
    val background: Boolean = true,
    val opacity: Int = 100,
    /** "auto" uses the theme's card colour; otherwise a colour as #RRGGBB. */
    val bgColor: String = "auto",
    val radius: Int = 22,
    val padH: Int = 12,
    val padV: Int = 12,
) {
    val rangeSeconds: Long get() = WidgetRanges.firstOrNull { it.first == range }?.second ?: 86_400
}

internal val WidgetMetrics = listOf("blocked" to "Blocked", "bypassed" to "Bypassed", "redirected" to "Redirected", "total" to "Total")
internal val WidgetRanges = listOf("1h" to 3600L, "24h" to 86_400L, "7d" to 7 * 86_400L, "30d" to 30 * 86_400L, "1y" to 365 * 86_400L)

/** Totals for the period, and per time bucket for the chart. */
internal data class WidgetStats(
    val blocked: Long, val bypassed: Long, val redirected: Long, val total: Long,
    val seriesBlocked: List<Long>, val seriesBypassed: List<Long>, val seriesRedirected: List<Long>,
)

internal object WidgetKeys {
    val metric = stringPreferencesKey("metric")
    val range = stringPreferencesKey("range")
    val profileId = stringPreferencesKey("profile_id")
    val profileName = stringPreferencesKey("profile_name")
    val endpointId = stringPreferencesKey("endpoint_id")
    val endpointName = stringPreferencesKey("endpoint_name")
    val colours = stringPreferencesKey("colours")
    val opacity = intPreferencesKey("opacity")
    val background = booleanPreferencesKey("background")
    val bgColor = stringPreferencesKey("bg_color")
    val radius = intPreferencesKey("radius")
    val padH = intPreferencesKey("pad_h")
    val padV = intPreferencesKey("pad_v")
    /** Bumped on every settings change, so the widget loads again for the new settings. */
    val configVersion = longPreferencesKey("config_version")
    val data = stringPreferencesKey("data")
    val updated = longPreferencesKey("updated")
    val error = stringPreferencesKey("error")
    val refreshing = booleanPreferencesKey("refreshing")
}

internal fun Preferences.widgetConfig() = WidgetConfig(
    metric = this[WidgetKeys.metric] ?: "blocked",
    range = this[WidgetKeys.range] ?: "24h",
    profileId = this[WidgetKeys.profileId]?.ifBlank { null },
    profileName = this[WidgetKeys.profileName]?.ifBlank { null },
    endpointId = this[WidgetKeys.endpointId]?.ifBlank { null },
    endpointName = this[WidgetKeys.endpointName]?.ifBlank { null },
    colours = this[WidgetKeys.colours] ?: "app",
    opacity = this[WidgetKeys.opacity] ?: 100,
    background = this[WidgetKeys.background] ?: true,
    bgColor = this[WidgetKeys.bgColor] ?: "auto",
    radius = this[WidgetKeys.radius] ?: 22,
    padH = this[WidgetKeys.padH] ?: 12,
    padV = this[WidgetKeys.padV] ?: 12,
)

/** The background colour as set, at the chosen alpha; null for "auto" (the theme's own). */
internal fun WidgetConfig.customBg(): Color? = bgColor.takeIf { it != "auto" }
    ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    ?.copy(alpha = opacity.coerceIn(0, 100) / 100f)

private fun WidgetStats.toJson() = JSONObject()
    .put("b", blocked).put("p", bypassed).put("r", redirected).put("t", total)
    .put("sb", JSONArray(seriesBlocked)).put("sp", JSONArray(seriesBypassed)).put("sr", JSONArray(seriesRedirected))
    .toString()

internal fun parseStats(s: String?): WidgetStats? = s?.let {
    runCatching {
        val o = JSONObject(it)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.optLong(i) } }.orEmpty()
        WidgetStats(o.optLong("b"), o.optLong("p"), o.optLong("r"), o.optLong("t"), list("sb"), list("sp"), list("sr"))
    }.getOrNull()
}

/** Older than this, a shown widget loads again (Android wakes widgets about every 30 minutes). */
private const val STALE_MS = 25 * 60_000L

private class NotSignedIn : Exception("Sign in to DNS OTG to see your stats")

/** The analytics region, kept so a widget update is a single request. Cleared on sign-in/out and region changes. */
private fun regionPrefs(ctx: Context) = ctx.getSharedPreferences("dnsotg_widget", Context.MODE_PRIVATE)

/** The same query-over-time call as Statistics' chart: one request gives the totals and the chart. */
internal suspend fun loadStats(ctx: Context, cfg: WidgetConfig): WidgetStats {
    val (token, org) = TokenStore.load(ctx) ?: throw NotSignedIn()
    val api = ControlDApi(token, org)
    val region = regionPrefs(ctx).getString("region", null)
        ?: (api.user().statsRegion ?: "america").also { regionPrefs(ctx).edit().putString("region", it).apply() }
    val series = api.statSeries(region, TimeRange(cfg.range, cfg.rangeSeconds), StatScope(cfg.endpointId, cfg.profileId), buckets = 30)
    fun at(p: com.controldmanager.app.api.SeriesPoint, vararg actions: Int) = actions.sumOf { p.counts[it] ?: 0L }
    val blocked = series.map { at(it, Do.BLOCK) }
    val bypassed = series.map { at(it, Do.BYPASS) }
    val redirected = series.map { at(it, Do.SPOOF, Do.REDIRECT) }
    return WidgetStats(blocked.sum(), bypassed.sum(), redirected.sum(), series.sumOf { p -> p.counts.values.sum() }, blocked, bypassed, redirected)
}

/** Widgets with a load running: a widget is drawn once for each of its sizes, and each would start its own. */
private val loadingWidgets = java.util.Collections.synchronizedSet(mutableSetOf<GlanceId>())

/** Bumped on sign-in/out and region changes, so a load that started before one of them runs again. */
private val widgetEpoch = java.util.concurrent.atomic.AtomicInteger()

/** Loads the widget's numbers and redraws it; failures keep the last numbers and say why. */
internal suspend fun refreshWidget(ctx: Context, id: GlanceId) {
    if (!loadingWidgets.add(id)) return
    var done = false
    try {
        updateAppWidgetState(ctx, id) { it[WidgetKeys.refreshing] = true }
        StatsWidget().update(ctx, id)
        while (!done) {
            val prefs = getAppWidgetState(ctx, PreferencesGlanceStateDefinition, id)
            val epoch = widgetEpoch.get()
            val result = runCatching { loadStats(ctx, prefs.widgetConfig()) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            updateAppWidgetState(ctx, id) { p ->
                // New settings or another account while it loaded: these numbers are for the old ones, so load again.
                if (p[WidgetKeys.configVersion] != prefs[WidgetKeys.configVersion] || epoch != widgetEpoch.get()) return@updateAppWidgetState
                p[WidgetKeys.refreshing] = false
                p[WidgetKeys.updated] = System.currentTimeMillis()
                result.onSuccess { p[WidgetKeys.data] = it.toJson(); p.remove(WidgetKeys.error) }
                result.onFailure { p[WidgetKeys.error] = if (it is NotSignedIn) it.message.orEmpty() else "Couldn't update" }
                if (result.exceptionOrNull() is NotSignedIn) p.remove(WidgetKeys.data)
                done = true
            }
        }
        StatsWidget().update(ctx, id)
    } finally {
        // Stopped mid-load (the widget's session timed out): say so rather than "Updating…", and count it as a try,
        // so the widget doesn't start loading again straight away. The widget may be gone, hence runCatching.
        if (!done) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching {
                updateAppWidgetState(ctx, id) { p ->
                    p[WidgetKeys.refreshing] = false
                    p[WidgetKeys.updated] = System.currentTimeMillis()
                    p[WidgetKeys.error] = "Couldn't update"
                }
                StatsWidget().update(ctx, id)
            }
        }
        loadingWidgets.remove(id)
    }
}

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** After signing in or out, or a storage region change: every widget loads again (and forgets the old region). */
fun refreshAllWidgets(ctx: Context) {
    val app = ctx.applicationContext
    regionPrefs(app).edit().clear().apply()
    widgetEpoch.incrementAndGet()
    widgetScope.launch {
        runCatching {
            GlanceAppWidgetManager(app).getGlanceIds(StatsWidget::class.java).forEach { refreshWidget(app, it) }
            StatsWidget().updateAll(app)
        }
    }
}

/** Asks the launcher to place a widget (one tap); false when it can't, so the caller can explain the long-press way. */
fun pinWidget(ctx: Context, wide: Boolean): Boolean {
    val m = AppWidgetManager.getInstance(ctx)
    if (!m.isRequestPinAppWidgetSupported) return false
    val receiver = if (wide) StatsWidgetWideReceiver::class.java else StatsWidgetSmallReceiver::class.java
    return runCatching { m.requestPinAppWidget(ComponentName(ctx, receiver), null, null) }.getOrDefault(false)
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = refreshWidget(context, glanceId)
}

/** The 2×2 (blocked %) widget; the same widget grows into the chart layout when made wide enough. */
class StatsWidgetSmallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}

/** The 4×2 widget with the activity chart. */
class StatsWidgetWideReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}

private val Wide = DpSize(250.dp, 110.dp)

class StatsWidget : GlanceAppWidget() {
    // The real size (not the nearest of a few), so the chart can be drawn to fit instead of stretched.
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val stats = remember(prefs[WidgetKeys.data]) { parseStats(prefs[WidgetKeys.data]) }
            // Loads when first shown, after a settings change, and when Android wakes a widget whose numbers are old.
            LaunchedEffect(prefs[WidgetKeys.configVersion], stats == null) {
                val age = System.currentTimeMillis() - (prefs[WidgetKeys.updated] ?: 0L)
                if (stats == null && prefs[WidgetKeys.error] == null || age > STALE_MS) refreshWidget(context, id)
            }
            val cfg = prefs.widgetConfig()
            // Only while a load really runs: one cut off when the app was closed leaves the flag behind.
            val refreshing = prefs[WidgetKeys.refreshing] == true && id in loadingWidgets
            WidgetContent(context, cfg, stats, prefs[WidgetKeys.error], refreshing, WidgetColors(widgetDark(context, cfg.colours), cfg.opacity))
        }
    }
}

/** The widget's colours in one theme: the app's own palette. Shared with the preview in the settings screen. */
internal class WidgetTheme(dark: Boolean) {
    val bg = if (dark) Color(0xFF111628) else Color(0xFFFFFFFF)
    val pill = if (dark) Color(0xFF1A2038) else Color(0xFFF2F3F8)
    val text = if (dark) Color(0xFFE8EBF7) else Color(0xFF0E1018)
    val muted = if (dark) Color(0xFF8B93B3) else Color(0xFF656A7A)
    val teal = if (dark) Color(0xFF2FD8A2) else Color(0xFF18C13E)
    val green = if (dark) Color(0xFF1BE3AD) else Color(0xFF0FA678)
    val orange = if (dark) Color(0xFFE69201) else Color(0xFFD08400)
    val red = BlockedColor
}

/** True or false for a fixed theme; null follows the phone (the app's System setting). */
internal fun widgetDark(ctx: Context, colours: String): Boolean? {
    val mode = if (colours == "app") ctx.getSharedPreferences("controldmanager_ui", Context.MODE_PRIVATE).getString("theme", "system") else colours
    return when (mode) { "light" -> false; "dark" -> true; else -> null }
}

private class WidgetColors(private val dark: Boolean?, opacity: Int) {
    private val d = WidgetTheme(true)
    private val l = WidgetTheme(false)
    private fun pick(f: (WidgetTheme) -> Color): ColorProvider = when (dark) {
        true -> ColorProvider(f(d))
        false -> ColorProvider(f(l))
        null -> DayNight(day = f(l), night = f(d))
    }
    val bg = pick { it.bg.copy(alpha = opacity.coerceIn(0, 100) / 100f) }
    val pill = pick { it.pill }
    val text = pick { it.text }
    val muted = pick { it.muted }
    val teal = pick { it.teal }
    val red = ColorProvider(BlockedColor)
    val green = pick { it.green }
    val orange = pick { it.orange }
}

internal val BlockedColor = Color(0xFFE93349)
internal val BypassedColor = Color(0xFF1BC99A)
internal val RedirectedColor = Color(0xFFE69201)

/** The big number: the chosen action's share of all queries, or the total itself; and the word under it. */
internal fun headline(cfg: WidgetConfig, s: WidgetStats): Pair<String, String> = when (cfg.metric) {
    "total" -> compact(s.total) to "Queries"
    else -> share(metricCount(cfg, s), s.total) to (WidgetMetrics.firstOrNull { it.first == cfg.metric }?.second ?: "Blocked")
}

internal fun metricCount(cfg: WidgetConfig, s: WidgetStats) = when (cfg.metric) {
    "bypassed" -> s.bypassed
    "redirected" -> s.redirected
    "total" -> s.total
    else -> s.blocked
}

@Composable
private fun WidgetContent(context: Context, cfg: WidgetConfig, stats: WidgetStats?, error: String?, refreshing: Boolean, c: WidgetColors) {
    val size = LocalSize.current
    val wide = size.width >= Wide.width
    // Tapping opens Statistics for the widget's period.
    val open = remember(cfg.range) {
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OpenRequest.EXTRA_RANGE, cfg.range).putExtra(OpenRequest.EXTRA_SECONDS, cfg.rangeSeconds)
    }
    val bg: ColorProvider = when {
        !cfg.background -> ColorProvider(Color.Transparent)
        else -> cfg.customBg()?.let { ColorProvider(it) } ?: c.bg
    }
    Box(
        GlanceModifier.fillMaxSize().cornerRadius(cfg.radius.dp).background(bg).clickable(actionStartActivity(open))
            .padding(horizontal = cfg.padH.dp, vertical = cfg.padV.dp),
    ) {
        Row(GlanceModifier.fillMaxSize()) {
            Column(if (wide) GlanceModifier.width(118.dp).fillMaxHeight() else GlanceModifier.fillMaxSize()) {
                Header(cfg, refreshing, showRefresh = !wide, c)
                Spacer(GlanceModifier.defaultWeight())
                when {
                    stats != null -> Headline(cfg, stats, c)
                    error != null -> Text(error, style = TextStyle(color = c.muted, fontSize = 12.sp), maxLines = 3)
                    else -> Text("Loading…", style = TextStyle(color = c.muted, fontSize = 12.sp))
                }
            }
            if (wide) {
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (refreshing) "Updating…" else if (error != null && stats != null) "Couldn't update" else "Activity",
                            style = TextStyle(color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        RefreshButton(c)
                    }
                    if (stats != null) {
                        // The chart's own area: the widget minus padding, the number column, the title row and the legend.
                        val chartW = (size.width - (cfg.padH * 2).dp - 118.dp - 12.dp).value.coerceAtLeast(40f)
                        val chartH = (size.height - (cfg.padV * 2).dp - 26.dp - 18.dp - 8.dp).value.coerceAtLeast(30f)
                        Chart(context, stats, chartW, chartH, GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 4.dp))
                        // Labels only when they fit; the colours already say which is which.
                        val labels = size.width >= 320.dp
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Legend("●", compact(stats.blocked), "Block".takeIf { labels }, c.red, c)
                            Spacer(GlanceModifier.width(8.dp))
                            Legend("●", compact(stats.bypassed), "Allow".takeIf { labels }, c.green, c)
                            Spacer(GlanceModifier.width(8.dp))
                            Legend("●", compact(stats.redirected), "Redirect".takeIf { labels }, c.orange, c)
                        }
                    } else Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}


/** App icon and name, the time range (and what it's limited to), and on the small widget the refresh button. */
@Composable
private fun Header(cfg: WidgetConfig, refreshing: Boolean, showRefresh: Boolean, c: WidgetColors) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.app_logo), null, GlanceModifier.size(16.dp))
        Spacer(GlanceModifier.width(5.dp))
        Text(
            cfg.endpointName ?: cfg.profileName ?: "DNS OTG", maxLines = 1,
            style = TextStyle(color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight(),
        )
        Box(GlanceModifier.cornerRadius(8.dp).background(c.pill).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(if (refreshing && showRefresh) "…" else cfg.range, style = TextStyle(color = c.muted, fontSize = 11.sp))
        }
        if (showRefresh) { Spacer(GlanceModifier.width(2.dp)); RefreshButton(c) }
    }
}

@Composable
private fun RefreshButton(c: WidgetColors) {
    Box(GlanceModifier.size(26.dp).cornerRadius(13.dp).clickable(actionRunCallback<RefreshWidgetAction>()), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_widget_refresh), "Refresh", GlanceModifier.size(15.dp), colorFilter = ColorFilter.tint(c.muted))
    }
}

@Composable
private fun Headline(cfg: WidgetConfig, s: WidgetStats, c: WidgetColors) {
    val (big, label) = headline(cfg, s)
    val color = when (cfg.metric) { "bypassed" -> c.green; "redirected" -> c.orange; "total" -> c.teal; else -> c.red }
    Text(big, style = TextStyle(color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    Text(label, style = TextStyle(color = c.muted, fontSize = 12.sp))
    Spacer(GlanceModifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cfg.metric == "total") {
            Text(compact(s.blocked), style = TextStyle(color = c.red, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Text(" blocked", style = TextStyle(color = c.muted, fontSize = 11.sp))
        } else {
            Text(compact(metricCount(cfg, s)), style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.width(8.dp))
            Text(compact(s.total), style = TextStyle(color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Text(" total", style = TextStyle(color = c.muted, fontSize = 11.sp))
        }
    }
}

@Composable
private fun Legend(dot: String, value: String, label: String?, color: ColorProvider, c: WidgetColors) {
    Text(dot, style = TextStyle(color = color, fontSize = 9.sp))
    Text(" $value", style = TextStyle(color = c.text, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    label?.let { Text(" $it", style = TextStyle(color = c.muted, fontSize = 10.sp), maxLines = 1) }
}

internal fun share(part: Long, whole: Long): String {
    if (whole <= 0) return "0%"
    val p = part * 100.0 / whole
    return if (p > 0 && p < 1) "<1%" else "${p.roundToInt()}%"
}

/**
 * Widgets can't draw, so the three lines are drawn into a picture at the chart area's own size ([wDp] × [hDp]),
 * so they stay sharp and thin however the widget is sized. Capped so a huge widget stays within the memory widgets get.
 */
@Composable
private fun Chart(context: Context, s: WidgetStats, wDp: Float, hDp: Float, modifier: GlanceModifier) {
    val density = context.resources.displayMetrics.density
    val scale = minOf(density, 900f / wDp, 900f / hDp)
    val bitmap = remember(s, wDp, hDp) { chartBitmap(s, (wDp * scale).toInt(), (hDp * scale).toInt(), 2f * scale) }
    Image(ImageProvider(bitmap), "Queries over time", modifier, contentScale = ContentScale.FillBounds)
}

private fun chartBitmap(s: WidgetStats, w: Int, h: Int, stroke: Float): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val top = maxOf(1L, (s.seriesBlocked + s.seriesBypassed + s.seriesRedirected).maxOrNull() ?: 1L).toFloat()
    val pad = stroke
    fun line(values: List<Long>, color: Color) {
        if (values.size < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = pad + i * (w - 2 * pad) / (values.size - 1)
            val y = h - pad - v / top * (h - 2 * pad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
    line(s.seriesRedirected, RedirectedColor)
    line(s.seriesBlocked, BlockedColor)
    line(s.seriesBypassed, BypassedColor)
    return bmp
}
