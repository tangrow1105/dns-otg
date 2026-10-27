package com.controldmanager.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.controldmanager.app.MainActivity
import com.controldmanager.app.R
import com.controldmanager.app.api.ControlDApi
import com.controldmanager.app.api.TokenStore
import com.controldmanager.app.ui.CdButton
import com.controldmanager.app.ui.CdDialog
import com.controldmanager.app.ui.CdSwitch
import com.controldmanager.app.ui.CdTheme
import com.controldmanager.app.ui.GroupRow
import com.controldmanager.app.ui.Palette
import com.controldmanager.app.ui.PickerDialog
import com.controldmanager.app.ui.SectionHeader
import com.controldmanager.app.ui.Solar
import com.controldmanager.app.ui.compact
import kotlinx.coroutines.launch

/**
 * A widget's settings, with a live preview over the home screen wallpaper (the window shows the wallpaper
 * through, so no permission is needed). Opens when a widget is added (Android 11 and older) and from the
 * widget's pencil (long-press → edit) on Android 12 and newer.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out without saving leaves a newly added widget unplaced, as Android expects.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        val manager = AppWidgetManager.getInstance(this)
        // A widget removed meanwhile (or an id that was never one) has nothing to set up.
        val info = manager.getAppWidgetInfo(widgetId) ?: run { finish(); return }
        // The preview takes the widget's shape: wide when it is the activity widget or has been stretched wide.
        val wide = manager.getAppWidgetOptions(widgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) >= 250 ||
            info.provider.className.endsWith("WideReceiver")
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(widgetId)
        lifecycleScope.launch {
            val state = getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
            val saved = state.widgetConfig()
            val shown = parseStats(state[WidgetKeys.data])
            setContent {
                CdTheme {
                    ConfigScreen(saved, shown, wide, signedIn = TokenStore.load(this@WidgetConfigActivity) != null) { cfg ->
                        lifecycleScope.launch {
                            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { p ->
                                p[WidgetKeys.metric] = cfg.metric
                                p[WidgetKeys.range] = cfg.range
                                p[WidgetKeys.profileId] = cfg.profileId.orEmpty()
                                p[WidgetKeys.profileName] = cfg.profileName.orEmpty()
                                p[WidgetKeys.endpointId] = cfg.endpointId.orEmpty()
                                p[WidgetKeys.endpointName] = cfg.endpointName.orEmpty()
                                p[WidgetKeys.colours] = cfg.colours
                                p[WidgetKeys.opacity] = cfg.opacity
                                p[WidgetKeys.background] = cfg.background
                                p[WidgetKeys.bgColor] = cfg.bgColor
                                p[WidgetKeys.radius] = cfg.radius
                                p[WidgetKeys.padH] = cfg.padH
                                p[WidgetKeys.padV] = cfg.padV
                                p[WidgetKeys.configVersion] = System.currentTimeMillis()
                                p.remove(WidgetKeys.data); p.remove(WidgetKeys.error); p.remove(WidgetKeys.updated)
                            }
                            StatsWidget().update(this@WidgetConfigActivity, glanceId)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigScreen(initial: WidgetConfig, shown: WidgetStats?, wide: Boolean, signedIn: Boolean, onSave: (WidgetConfig) -> Unit) {
        var cfg by remember { mutableStateOf(initial) }
        var pick by remember { mutableStateOf<String?>(null) }
        val api = remember { TokenStore.load(this)?.let { (t, org) -> ControlDApi(t, org) } }
        var profiles by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        var endpoints by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        LaunchedEffect(api) {
            if (api == null) return@LaunchedEffect
            profiles = runCatching { api.profiles().map { it.pk to it.name } }.getOrDefault(emptyList())
            endpoints = runCatching { api.devices().map { it.pk to it.name } }.getOrDefault(emptyList())
        }
        // The preview shows the real numbers for the chosen period and traffic (sample ones until they arrive).
        var stats by remember { mutableStateOf(shown) }
        LaunchedEffect(cfg.range, cfg.profileId, cfg.endpointId) {
            if (signedIn) runCatching { loadStats(this@WidgetConfigActivity, cfg) }.onSuccess { stats = it }
        }
        val dark = widgetDark(this, cfg.colours) ?: isSystemInDarkTheme()
        // The screen is the app's background with one rounded window cut out, where the home screen wallpaper
        // shows through behind the preview (the launcher's own widget screens look the same).
        var hole by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val haze = remember { dev.chrisbanes.haze.HazeState() }
        val bg = Palette.Bg
        val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    drawRect(bg)
                    hole?.let { r -> drawRoundRect(Color.Black, r.topLeft, r.size, CornerRadius(radius), blendMode = BlendMode.Clear) }
                },
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(start = 24.dp, top = 24.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Widget settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = Palette.Teal)
                    Spacer(Modifier.width(10.dp))
                    // Widgets are new and still changing.
                    Text(
                        "BETA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Teal,
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Teal, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Box(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(260.dp).onGloballyPositioned { hole = it.boundsInRoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    // A margin around the widget, so it sits on the wallpaper rather than filling the card.
                    Box(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                        WidgetPreview(cfg, stats ?: SampleStats, wide, WidgetTheme(dark))
                    }
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().weight(1f).hazeSource(haze).background(Palette.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    if (!signedIn) {
                        Text("Sign in to DNS OTG first, then come back to set up this widget.", color = Palette.Muted, modifier = Modifier.padding(12.dp))
                        // Its own task: this screen runs in the launcher's, where the app would vanish on Home.
                        CdButton("Open DNS OTG", {
                            startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }, Modifier.fillMaxWidth())
                        return@Column
                    }
                    // What and when: each row names the setting, with its value in green beneath, as the launcher's lists.
                    GroupRow(
                        "Show", first = true, last = false, subtitle = WidgetMetrics.first { it.first == cfg.metric }.second,
                        subtitleColor = Palette.Teal, fill = Palette.Card,
                    ) { pick = "metric" }
                    GroupRow("Time range", first = false, last = false, subtitle = rangeName(cfg.range), subtitleColor = Palette.Teal, fill = Palette.Card) { pick = "range" }
                    GroupRow("Profile", first = false, last = false, subtitle = cfg.profileName ?: "All profiles", subtitleColor = Palette.Teal, fill = Palette.Card) { pick = "profile" }
                    GroupRow("Endpoint", first = false, last = false, subtitle = cfg.endpointName ?: "All endpoints", subtitleColor = Palette.Teal, fill = Palette.Card) { pick = "endpoint" }
                    GroupRow(
                        "Colours", first = false, last = true, subtitle = colourNames.first { it.first == cfg.colours }.second,
                        subtitleColor = Palette.Teal, fill = Palette.Card,
                    ) { pick = "colours" }
                    // Background: on or off, then its effect and its shape (as the launcher's own background settings).
                    SectionHeader("Background")
                    GroupRow(
                        if (cfg.background) "On" else "Off", first = true, last = true, fill = Palette.Card,
                        titleColor = if (cfg.background) Palette.Teal else Palette.Text,
                        trailing = { CdSwitch(cfg.background, { cfg = cfg.copy(background = it) }) },
                    ) { cfg = cfg.copy(background = !cfg.background) }
                    val on = cfg.background
                    SectionHeader("Configuration")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Card)) {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = on) { pick = "bgcolor" }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Background colour", color = if (on) Palette.Text else Palette.Muted, modifier = Modifier.weight(1f))
                            ColourSwatch(cfg.bgColor, dark, Modifier.size(width = 52.dp, height = 30.dp))
                        }
                        SliderRow("Alpha", cfg.opacity, 0..100, on) { cfg = cfg.copy(opacity = it) }
                        SliderRow("Corner radius", cfg.radius, 0..40, on) { cfg = cfg.copy(radius = it) }
                        SliderRow("Horizontal padding", cfg.padH, 4..28, on) { cfg = cfg.copy(padH = it) }
                        SliderRow("Vertical padding", cfg.padV, 4..28, on, last = true) { cfg = cfg.copy(padV = it) }
                    }
                    // Room for the floating Cancel / Save bar.
                    Spacer(Modifier.height(110.dp))
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(50)).hazeEffect(haze, com.controldmanager.app.ui.Glass)
                    .border(1.dp, com.controldmanager.app.ui.GlassEdge, RoundedCornerShape(50)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { finish() }) { Text("Cancel", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
                Box(Modifier.width(1.dp).height(22.dp).background(Palette.Outline))
                TextButton(onClick = { onSave(cfg) }, enabled = signedIn) {
                    Text("Save", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
        when (pick) {
            "metric" -> PickerDialog("Show", WidgetMetrics, label = { it.second }, selected = { it.first == cfg.metric }, onDismiss = { pick = null }) {
                cfg = cfg.copy(metric = it.first)
            }
            "range" -> PickerDialog("Time range", WidgetRanges.map { it.first }, label = { rangeName(it) }, selected = { it == cfg.range }, onDismiss = { pick = null }) {
                cfg = cfg.copy(range = it)
            }
            "bgcolor" -> ColourDialog(cfg.bgColor, dark, onDismiss = { pick = null }) { cfg = cfg.copy(bgColor = it) }
            "colours" -> PickerDialog("Colours", colourNames, label = { it.second }, selected = { it.first == cfg.colours }, onDismiss = { pick = null }) {
                cfg = cfg.copy(colours = it.first)
            }
            "profile" -> PickerDialog(
                "Profile", listOf<Pair<String, String>?>(null) + profiles.orEmpty(), label = { it?.second ?: "All profiles" },
                selected = { it?.first == cfg.profileId }, onDismiss = { pick = null },
            ) { cfg = cfg.copy(profileId = it?.first, profileName = it?.second) }
            "endpoint" -> PickerDialog(
                "Endpoint", listOf<Pair<String, String>?>(null) + endpoints.orEmpty(), label = { it?.second ?: "All endpoints" },
                selected = { it?.first == cfg.endpointId }, onDismiss = { pick = null },
            ) { cfg = cfg.copy(endpointId = it?.first, endpointName = it?.second) }
        }
    }
}

private val colourNames = listOf("app" to "Match app setting", "light" to "Light", "dark" to "Dark")

/** Background colours to pick from; "auto" is the theme's own card colour. */
private val BgColours = listOf("auto", "#111628", "#000000", "#2B2D31", "#1D3557", "#0E3B2E", "#2A1F4D", "#4A1F2A", "#FFFFFF", "#E8F5EE")

private fun swatchColour(key: String, dark: Boolean): Color =
    if (key == "auto") WidgetTheme(dark).bg else runCatching { Color(android.graphics.Color.parseColor(key)) }.getOrDefault(Color.Black)

@Composable
private fun ColourSwatch(key: String, dark: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(swatchColour(key, dark)).border(1.5.dp, Palette.Muted, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        if (key == "auto") Text("Auto", fontSize = 10.sp, color = WidgetTheme(dark).muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourDialog(selected: String, dark: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background colour") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BgColours.forEach { key ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(swatchColour(key, dark))
                            .border(if (key == selected) 3.dp else 1.dp, if (key == selected) Palette.Teal else Palette.Muted, CircleShape)
                            .clickable { onPick(key); onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { if (key == "auto") Text("Auto", fontSize = 11.sp, color = WidgetTheme(dark).muted) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A label above a slider, in the Configuration list; [last] drops the divider under it. */
@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, enabled: Boolean, last: Boolean = false, onChange: (Int) -> Unit) {
    androidx.compose.material3.HorizontalDivider(color = Palette.Outline, modifier = Modifier.padding(horizontal = 16.dp))
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (last) 8.dp else 4.dp)) {
        Text(label, color = if (enabled) Palette.Text else Palette.Muted)
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Palette.Teal, activeTrackColor = Palette.Teal, inactiveTrackColor = Palette.Outline),
        )
    }
}

private fun rangeName(key: String) = when (key) {
    "1h" -> "Hour"; "24h" -> "Day"; "7d" -> "Week"; "30d" -> "Month"; "1y" -> "Year"; else -> key
}

/** Numbers for the preview until the real ones load. */
private val SampleStats = WidgetStats(
    206, 838, 6, 1050,
    listOf(8, 5, 9, 3, 1, 7, 2, 10, 4, 6, 3, 9, 5, 2, 8).map { it.toLong() },
    listOf(20, 12, 30, 6, 10, 26, 4, 34, 12, 18, 9, 40, 8, 30, 2).map { it.toLong() },
    listOf(0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0).map { it.toLong() },
)

/** The widget drawn as it will look (same layout, colours and numbers as on the home screen). */
@Composable
private fun WidgetPreview(cfg: WidgetConfig, s: WidgetStats, wide: Boolean, t: WidgetTheme) {
    val shape = RoundedCornerShape(cfg.radius.dp)
    val bg = when {
        !cfg.background -> Color.Transparent
        else -> cfg.customBg() ?: t.bg.copy(alpha = cfg.opacity / 100f)
    }
    Box(
        (if (wide) Modifier.fillMaxWidth().widthIn(max = 380.dp).height(170.dp) else Modifier.size(170.dp))
            .clip(shape).background(bg).padding(horizontal = cfg.padH.dp, vertical = cfg.padV.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(if (wide) Modifier.width(118.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.app_logo), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(cfg.endpointName ?: cfg.profileName ?: "DNS OTG", color = t.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(t.pill).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text(cfg.range, color = t.muted, fontSize = 11.sp)
                    }
                    if (!wide) { Spacer(Modifier.width(6.dp)); Icon(painterResource(R.drawable.ic_widget_refresh), null, Modifier.size(15.dp), tint = t.muted) }
                }
                Spacer(Modifier.weight(1f))
                val (big, label) = headline(cfg, s)
                Text(big, color = t.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, lineHeight = 38.sp)
                Text(label, color = t.muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cfg.metric == "total") {
                        Text(compact(s.blocked), color = t.red, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(" blocked", color = t.muted, fontSize = 11.sp)
                    } else {
                        val color = when (cfg.metric) { "bypassed" -> t.green; "redirected" -> t.orange; else -> t.red }
                        Text(compact(metricCount(cfg, s)), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(compact(s.total), color = t.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(" total", color = t.muted, fontSize = 11.sp)
                    }
                }
            }
            if (wide) {
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activity", color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_widget_refresh), null, Modifier.size(15.dp), tint = t.muted)
                    }
                    PreviewChart(s, Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(s.blocked to t.red, s.bypassed to t.green, s.redirected to t.orange).forEachIndexed { i, (n, c) ->
                            if (i > 0) Spacer(Modifier.width(8.dp))
                            Text("●", color = c, fontSize = 9.sp)
                            Text(" ${compact(n)}", color = t.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewChart(s: WidgetStats, modifier: Modifier) {
    Canvas(modifier) {
        val top = maxOf(1L, (s.seriesBlocked + s.seriesBypassed + s.seriesRedirected).maxOrNull() ?: 1L).toFloat()
        val stroke = 2.dp.toPx()
        fun line(values: List<Long>, color: Color) {
            if (values.size < 2) return
            val path = Path()
            values.forEachIndexed { i, v ->
                val p = Offset(stroke + i * (size.width - 2 * stroke) / (values.size - 1), size.height - stroke - v / top * (size.height - 2 * stroke))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color, style = Stroke(stroke, join = StrokeJoin.Round))
        }
        line(s.seriesRedirected, RedirectedColor)
        line(s.seriesBlocked, BlockedColor)
        line(s.seriesBypassed, BypassedColor)
    }
}
