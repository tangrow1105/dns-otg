@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controldmanager.app.api.Action
import com.controldmanager.app.api.Do
import com.controldmanager.app.api.Proxy
import com.controldmanager.app.api.Session
import coil3.svg.css
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------- Theme ----------

/**
 * App colours. Every value follows [light], so screens recompose when the theme changes.
 * Light values follow the dashboard's light theme (background #EBECF3, white cards, black text, green #18C13E).
 */
object Palette {
    /** 0 = dark, 1 = light. Animated on theme change, so every colour blends from one theme to the other. */
    var t by mutableFloatStateOf(0f)
    val light: Boolean get() = t >= 0.5f
    /** [dark] → [lightColor] by the current theme blend. */
    fun mix(dark: Color, lightColor: Color): Color = if (t <= 0f) dark else if (t >= 1f) lightColor else androidx.compose.ui.graphics.lerp(dark, lightColor, t)
    val Bg get() = mix(Color(0xFF080B17), Color(0xFFEBECF3))
    val Card get() = mix(Color(0xFF111628), Color(0xFFFFFFFF))
    val CardHigh get() = mix(Color(0xFF1A2038), Color(0xFFF2F3F8))
    val Outline get() = mix(Color(0xFF262D4A), Color(0xFFD8DBE6))
    /** The one accent: Control D green-teal (deeper green in light mode, as the dashboard). */
    val Teal get() = mix(Color(0xFF2FD8A2), Color(0xFF18C13E))
    val Purple get() = mix(Color(0xFF7B61FF), Color(0xFF593EA5))
    val Orange get() = mix(Color(0xFFF5A524), Color(0xFFD98200))
    val Red get() = mix(Color(0xFFF2545B), Color(0xFFE93349))
    val Blue get() = mix(Color(0xFF4EA8FF), Color(0xFF2F7FE0))
    val Text get() = mix(Color(0xFFE8EBF7), Color(0xFF0E1018))
    val Muted get() = mix(Color(0xFF8B93B3), Color(0xFF656A7A))
    /** Foreground "ink" for translucent overlays: white on dark, black on light. */
    val Ink get() = mix(Color.White, Color.Black)
    /** Text/icon colour on top of a [Teal] fill. */
    val OnAccent get() = mix(Color(0xFF002A1C), Color.White)
}

private fun colorScheme() = if (Palette.light) lightColorScheme(
    primary = Palette.Teal,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.Teal.copy(alpha = 0.16f),
    onPrimaryContainer = Color(0xFF0C6B22),
    secondary = Palette.Teal,
    onSecondary = Color.White,
    secondaryContainer = Palette.Teal.copy(alpha = 0.16f),
    onSecondaryContainer = Color(0xFF0C6B22),
    tertiary = Palette.Orange,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.CardHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FB),
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.CardHigh,
    surfaceContainerHighest = Color(0xFFE6E8F0),
    outline = Palette.Outline,
    outlineVariant = Color(0xFFE3E5EE),
    error = Palette.Red,
) else darkColorScheme(
    primary = Palette.Teal,
    onPrimary = Palette.OnAccent,
    primaryContainer = Color(0xFF0F3B30),
    onPrimaryContainer = Palette.Teal,
    // Selected chips and other "secondary" accents use the same teal, so there's one accent colour.
    secondary = Palette.Teal,
    onSecondary = Palette.OnAccent,
    secondaryContainer = Color(0xFF0F3B30),
    onSecondaryContainer = Palette.Teal,
    tertiary = Palette.Orange,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.CardHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Color(0xFF0D1120),
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.CardHigh,
    surfaceContainerHighest = Color(0xFF212844),
    outline = Palette.Outline,
    outlineVariant = Color(0xFF1C2238),
    error = Palette.Red,
)

/** One corner radius for every box, field, card, dialog and button (floating pills stay fully round). */
val CdCorner = 20.dp
val CdShape = RoundedCornerShape(CdCorner)

private val shapes = Shapes(
    extraSmall = CdShape, small = CdShape, medium = CdShape, large = CdShape, extraLarge = CdShape,
)

/** "system", "light" or "dark" (saved on this device). */
object ThemePref {
    private const val KEY = "theme"
    var mode by mutableStateOf("system")
    fun load(ctx: Context) { mode = ctx.getSharedPreferences("controldmanager_ui", Context.MODE_PRIVATE).getString(KEY, "system") ?: "system" }
    fun set(ctx: Context, m: String) {
        mode = m
        ctx.getSharedPreferences("controldmanager_ui", Context.MODE_PRIVATE).edit().putString(KEY, m).apply()
    }
}

@Composable
fun CdTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    remember { ThemePref.load(ctx) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val target = when (ThemePref.mode) { "light" -> true; "dark" -> false; else -> !systemDark }
    // First frame: apply straight away. Later changes blend every colour over ~0.45s.
    remember { Palette.t = if (target) 1f else 0f; true }
    val anim = remember { androidx.compose.animation.core.Animatable(Palette.t) }
    LaunchedEffect(target) {
        anim.animateTo(if (target) 1f else 0f, androidx.compose.animation.core.tween(450)) { Palette.t = value }
    }
    val light = Palette.light
    // Status / navigation bar icons follow the app theme, not just the system one.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme(), shapes = shapes, content = content)
}

fun actionColor(d: Int) = when (d) {
    Do.BLOCK -> Palette.Red
    Do.BYPASS -> Palette.Teal
    Do.SPOOF -> Palette.Orange
    Do.REDIRECT -> Palette.Purple
    else -> Palette.Muted
}

// ---------- Session / feedback locals ----------

val LocalSession = staticCompositionLocalOf<Session> { error("No session") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar") }

// ---------- Async helpers ----------

/** Holds the result of a suspend load; keeps old data visible while refreshing. */
class Loader<T>(private val scope: CoroutineScope, private val block: suspend () -> T) {
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    private var job: Job? = null
    private var generation = 0

    fun reload() {
        job?.cancel()
        val gen = ++generation
        loading = true
        error = null
        job = scope.launch {
            try {
                data = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    /** Background refresh: no spinner, and a failure keeps the data already shown. */
    suspend fun refreshQuietly() {
        if (loading) return
        try {
            data = block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/**
 * Fade at the top edge (under the header) once the content inside has scrolled, so it fades out as it
 * goes up instead of being cut off. Pass [isScrolled] when the list state is at hand; otherwise the
 * scroll amount comes from the content's nested scroll, saved so it survives leaving and coming back.
 */
@Composable
fun TopScrollFade(modifier: Modifier = Modifier, isScrolled: (() -> Boolean)? = null, content: @Composable () -> Unit) {
    var scrolled by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(0f) }
    val track = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // Scroll left over in the downward direction means the content is already at its top edge,
                // so reset outright: the running total can drift (e.g. after coming back to a screen).
                scrolled = if (available.y > 0f) 0f else (scrolled - consumed.y).coerceAtLeast(0f)
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (available.y > 0f) scrolled = 0f   // a fling that ran into the top edge
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val show = if (isScrolled != null) isScrolled() else scrolled > 4f
    val fade by androidx.compose.animation.core.animateFloatAsState(if (show) 1f else 0f, label = "topFade")
    Box(modifier.fillMaxSize().nestedScroll(track)) {
        content()
        if (fade > 0f) Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(32.dp).alpha(fade)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.Bg, Palette.Bg.copy(alpha = 0f)))),
        )
    }
}

/** True once the list has moved away from its very top. */
fun androidx.compose.foundation.lazy.LazyListState.isScrolledDown() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** Current time in seconds, ticking every second, so "7s"/"3m" ages count up live. */
val LocalNowSec = compositionLocalOf { System.currentTimeMillis() / 1000 }

@Composable
fun ProvideTickingClock(content: @Composable () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000 - System.currentTimeMillis() % 1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    CompositionLocalProvider(LocalNowSec provides now, content = content)
}

/** Re-fetch [loader] every [periodMs] while the screen is shown (and the app is in the foreground). */
@Composable
fun LiveRefresh(loader: Loader<*>, periodMs: Long = 15_000) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(loader, lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            // Coming back to the screen (e.g. after creating or editing): refresh right away.
            if (!first) loader.refreshQuietly()
            first = false
            while (true) {
                kotlinx.coroutines.delay(periodMs)
                loader.refreshQuietly()
            }
        }
    }
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val loader = remember(*keys) { Loader(scope, block) }
    LaunchedEffect(loader) { loader.reload() }
    return loader
}

/** Runs mutations with a busy flag and reports errors/success in the snackbar. */
class Runner(private val scope: CoroutineScope, private val snack: SnackbarHostState) {
    var busy by mutableStateOf(false)

    fun run(success: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
                success?.let { scope.launch { snack.showSnackbar(it) } }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch { snack.showSnackbar(e.message ?: "Something went wrong") }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    return remember { Runner(scope, snack) }
}

@Composable
fun <T> LoaderBox(
    loader: Loader<T>,
    modifier: Modifier = Modifier,
    /** Pull-to-refresh action; defaults to reloading [loader]. */
    onRefresh: (() -> Unit)? = null,
    /** Exact "content is scrolled" check for the top fade, when the screen has its list state. */
    isScrolled: (() -> Boolean)? = null,
    content: @Composable (T) -> Unit,
) {
    val data = loader.data
    when {
        data != null -> PullToRefreshBox(
            isRefreshing = loader.loading,
            onRefresh = { onRefresh?.invoke() ?: loader.reload() },
            modifier = modifier.fillMaxSize(),
        ) {
            TopScrollFade(isScrolled = isScrolled) { content(data) }
        }

        loader.error != null -> ErrorState(loader.error!!, modifier) { loader.reload() }
        else -> ShimmerList(modifier)
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Solar.CloudOff, null, tint = Palette.Muted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        CdButton("Retry", onRetry, icon = Solar.Refresh)
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) =
    NiceEmptyState(icon, text, modifier)

// ---------- Small building blocks ----------

@Composable
fun CdCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = CdShape
    val c = CardDefaults.cardColors(containerColor = Palette.Card)
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = c, content = content)
    else Card(modifier = modifier, shape = shape, colors = c, content = content)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Muted,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun ActionPill(action: Action?, proxies: List<Proxy> = emptyList()) {
    if (action == null || action.doType < 0) {
        Pill("No rule", Palette.Muted); return
    }
    val color = if (action.enabled) actionColor(action.doType) else Palette.Muted
    if (action.doType == Do.REDIRECT && action.via != null) {
        val p = proxies.firstOrNull { it.pk == action.via }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
                .padding(start = 3.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.alpha(if (action.enabled) 1f else 0.5f)) { FlagIcon(p?.country, 15) }
            Spacer(Modifier.width(5.dp))
            Text(
                "Redirect → ${p?.city?.ifBlank { null } ?: action.via}",
                color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        return
    }
    val label = Do.label(action.doType) + (if (action.via != null && action.doType == Do.SPOOF) " → ${action.via}" else "")
    Pill(label, color)
}

@Composable
fun LetterAvatar(text: String, color: Color = Palette.Teal, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.take(1).uppercase(), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onCopy != null) IconButton(onClick = onCopy) { Icon(Solar.ContentCopy, "Copy", tint = Palette.Muted) }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted) }
        }
        CdSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled, busy = busy)
    }
}

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    showToast(ctx, "$label copied")
}

/** The app's glass toast host, set while the main screen is shown. */
object AppToast {
    var host: SnackbarHostState? = null
    val scope = kotlinx.coroutines.MainScope()
}

/** Shows [text] as the app's glass toast (falls back to a system toast outside the main screen). */
fun showToast(ctx: Context, text: String) {
    val h = AppToast.host
    if (h == null) { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show(); return }
    AppToast.scope.launch { h.currentSnackbarData?.dismiss(); h.showSnackbar(text) }
}

// ---------- Dialogs ----------

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Delete",
    destructive: Boolean = true,
    /**
     * Set by callers that close the dialog themselves once the action finishes: the dialog then stays
     * open with a spinner in the confirm button while [busy].
     */
    busy: Boolean? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val waiting = busy == true
    CdDialog(
        onDismissRequest = { if (!waiting) onDismiss() },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(enabled = !waiting, onClick = { onConfirm(); if (busy == null) onDismiss() }) {
                BusyLabel(confirmLabel, waiting, if (destructive) Palette.Red else Palette.Teal)
            }
        },
        dismissButton = { TextButton(enabled = !waiting, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Button label with a small spinner on its left while [busy]. */
@Composable
fun BusyLabel(text: String, busy: Boolean, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedVisibility(busy) {
            Row {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = if (color == Color.Unspecified) LocalContentColor.current else color)
                Spacer(Modifier.width(10.dp))
            }
        }
        Text(text, color = color)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Save",
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            CdTextField(value, { value = it }, label = { Text(label) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picker for the action of a rule/service/folder/default rule, including redirect location. */
@Composable
fun ActionDialog(
    title: String,
    initial: Action?,
    proxies: List<Proxy>,
    allowSpoof: Boolean = true,
    allowNone: Boolean = false,
    defaultLocation: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    extraValid: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
) {
    var doType by remember { mutableIntStateOf(initial?.doType?.takeIf { it >= 0 } ?: if (allowNone) -1 else Do.BLOCK) }
    var via by remember { mutableStateOf(initial?.via.orEmpty()) }
    var viaV6 by remember { mutableStateOf(initial?.viaV6.orEmpty()) }
    var redirectVia by remember {
        mutableStateOf(initial?.via?.takeIf { initial.doType == Do.REDIRECT } ?: defaultLocation ?: proxies.firstOrNull()?.pk.orEmpty())
    }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var pickLocation by remember { mutableStateOf(false) }

    val types = buildList {
        if (allowNone) add(-1)
        add(Do.BLOCK); add(Do.BYPASS)
        if (allowSpoof) add(Do.SPOOF)
        add(Do.REDIRECT)
    }
    val valid = when (doType) {
        Do.SPOOF -> via.isNotBlank()
        Do.REDIRECT -> redirectVia.isNotBlank()
        else -> true
    }

    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                extra?.invoke(this)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    types.forEach { t ->
                        FilterChip(
                            selected = doType == t,
                            onClick = { doType = t },
                            label = { Text(if (t == -1) "None" else Do.label(t)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = actionColor(t).copy(alpha = 0.2f),
                                selectedLabelColor = actionColor(t),
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (doType) {
                        Do.BLOCK -> "Queries are blocked."
                        Do.BYPASS -> "Always resolved normally, even if a filter would block it."
                        Do.SPOOF -> "Resolve to an IP or hostname of your choice."
                        Do.REDIRECT -> "Route traffic through a Control D proxy location."
                        else -> "Use whatever the profile decides."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                when (doType) {
                    Do.SPOOF -> {
                        Spacer(Modifier.height(8.dp))
                        CdTextField(via, { via = it }, label = { Text("IPv4 or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        CdTextField(viaV6, { viaV6 = it }, label = { Text("IPv6 (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }

                    Do.REDIRECT -> {
                        Spacer(Modifier.height(8.dp))
                        val p = proxies.firstOrNull { it.pk == redirectVia }
                        CdFieldCard(onClick = { pickLocation = true }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                FlagIcon(p?.country, 24)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Location", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                                    Text(p?.label ?: redirectVia.ifBlank { "Choose…" })
                                }
                                Icon(Solar.ChevronRight, null)
                            }
                        }
                    }
                }
                if (doType != -1) {
                    Spacer(Modifier.height(4.dp))
                    SwitchRow("Enabled", checked = enabled) { enabled = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && extraValid, onClick = {
                val v = when (doType) {
                    Do.SPOOF -> via.trim()
                    Do.REDIRECT -> redirectVia
                    else -> null
                }
                onConfirm(Action(doType, v, viaV6.trim().takeIf { doType == Do.SPOOF && it.isNotBlank() }, if (enabled) 1 else 0))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickLocation) {
        LocationPicker(proxies, redirectVia, onDismiss = { pickLocation = false }) { redirectVia = it }
    }
}

@Composable
fun LocationPicker(proxies: List<Proxy>, selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text("Redirect location") },
        edgeToEdge = true,
        text = {
            Column(Modifier.padding(horizontal = 16.dp)) {
                CdTextField(
                    q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) }, placeholder = { Text("Search city or country") },
                )
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    LocationGroups(proxies, q, selected) { onPick(it.pk); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun <T> PickerDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(items) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(item); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected(item), onClick = { onPick(item); onDismiss() })
                        Spacer(Modifier.width(8.dp))
                        Text(label(item))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun rememberToast(): (String) -> Unit {
    val ctx = LocalContext.current
    return remember { { msg: String -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
}

/**
 * Dashboard-style switch drawn by hand: fixed track, same-size dark thumb with identical padding in both
 * states (M3's Switch insets the off thumb by its border and grows it on press, which reads as "offset").
 */
@Composable
fun CdSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** True while the screen's request is running; the toggled switch shows a spinner until it ends. */
    busy: Boolean = false,
) {
    // Flip immediately to the requested state and spin until the change lands (or the request ends/fails).
    var target by remember { mutableStateOf<Boolean?>(null) }
    val currentBusy by rememberUpdatedState(busy)
    LaunchedEffect(checked) { if (target == checked) target = null }
    LaunchedEffect(busy) { if (!busy) target = null }
    LaunchedEffect(target) {
        // Some toggles open a dialog first; if no request starts, don't spin forever.
        if (target != null) {
            kotlinx.coroutines.delay(1500)
            if (!currentBusy) target = null
        }
    }
    val loading = target != null
    val on = target ?: checked
    val interactive = enabled && !loading && onCheckedChange != null

    val trackW = 46.dp
    val trackH = 26.dp
    val pad = 3.dp
    val thumb = trackH - pad * 2
    // Springy thumb and a smooth colour fade, matching the sliders and nav bar.
    val thumbX by androidx.compose.animation.core.animateDpAsState(
        if (on) trackW - thumb - pad * 2 else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "thumb",
    )
    val track = animateCdColor(
        if (on) Palette.Teal else SwitchOffTrack, androidx.compose.animation.core.tween(260), label = "track",
    )

    Box(
        modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = on,
                enabled = interactive,
                role = androidx.compose.ui.semantics.Role.Switch,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { v -> target = v; onCheckedChange?.invoke(v) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(trackW, trackH)
                .clip(RoundedCornerShape(50))
                .background(if (enabled) track else track.copy(alpha = 0.4f))
                .padding(pad),
        ) {
            Box(
                Modifier.offset(x = thumbX).size(thumb).clip(CircleShape).background(SwitchThumb),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(thumb - 6.dp),
                        strokeWidth = 2.dp,
                        color = if (on) Palette.Teal else Palette.Muted,
                    )
                }
            }
        }
    }
}

/** Same look as the dashboard: light track when off, teal when on, dark thumb in both. */
private val SwitchOffTrack get() = Palette.mix(Color(0xFFD3D7E2), Color(0xFFC6CAD6))
private val SwitchThumb get() = Palette.mix(Color(0xFF12131C), Color.White)

/** Service logo from Control D's asset CDN (SVG); shows a letter avatar while loading or when there's none. */
@Composable
fun ServiceIcon(pk: String, name: String, tint: Color, size: Int = 36) {
    // 0 = loading, 1 = loaded, 2 = failed
    var state by remember(pk) { mutableIntStateOf(0) }
    Box(contentAlignment = Alignment.Center) {
        if (state != 1) LetterAvatar(name, tint, size)
        if (state != 2) {
            Box(
                Modifier.size(size.dp).clip(CircleShape).background(if (state == 1) Palette.CardHigh else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                val ctx = LocalContext.current
                val light = Palette.light
                val request = remember(pk, light) {
                    coil3.request.ImageRequest.Builder(ctx)
                        .data("https://assets.controld.com/icons/services/svg/$pk.svg")
                        // Logos draw with currentColor: light on dark, dark on light (like the web dashboard).
                        // (CSS instead of a tint so logos that embed a bitmap keep their colours.)
                        .css(if (light) "svg { color: #0E1018; }" else "svg { color: #E8EBF7; }")
                        .memoryCacheKey("svc-$pk-${if (light) "l" else "d"}")
                        .build()
                }
                coil3.compose.AsyncImage(
                    model = request,
                    contentDescription = name,
                    onSuccess = { state = 1 },
                    onError = { state = 2 },
                    modifier = Modifier.size((size * 0.6f).dp),
                )
            }
        }
    }
}

/** Round country flag from Control D's asset CDN; falls back to a globe. */
@Composable
fun FlagIcon(country: String?, size: Int = 18) {
    val code = country?.trim()?.uppercase()?.takeIf { it.length == 2 }
    // No country: a themed globe (the CDN's globe.svg is drawn in black, which disappears in dark mode).
    if (code == null) { Icon(Solar.Global, null, Modifier.size(size.dp), tint = Palette.Muted); return }
    coil3.compose.AsyncImage(
        model = "https://assets.controld.com/icons/flags/round/svg/$code.svg",
        contentDescription = code,
        error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Solar.Global),
        modifier = Modifier.size(size.dp).clip(CircleShape),
    )
}

/**
 * Picker shown when tapping an active Redirect/Spoof: a location list (with flags) or a spoof target.
 * Block/Bypass aren't here; those are one tap on the selector itself.
 */
@Composable
fun RedirectSpoofDialog(
    title: String,
    current: Action?,
    proxies: List<Proxy>,
    suggested: String?,
    note: String? = null,
    onDismiss: () -> Unit,
    onSave: (Action) -> Unit,
) {
    var tab by remember { mutableIntStateOf(if (current?.doType == Do.SPOOF) 1 else 0) }
    var q by remember { mutableStateOf("") }
    var via by remember { mutableStateOf(current?.via?.takeIf { current.doType == Do.SPOOF }.orEmpty()) }
    var via6 by remember { mutableStateOf(current?.viaV6.orEmpty()) }
    val selectedLoc = current?.via?.takeIf { current.doType == Do.REDIRECT }
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        edgeToEdge = true,
        text = {
            Column(Modifier.padding(horizontal = 16.dp)) {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
                    Spacer(Modifier.height(10.dp))
                }
                SlideSelector(
                    listOf(SlideOption("Redirect", Solar.Global), SlideOption("Spoof", Solar.Routing)),
                    tab, { tab = it },
                )
                Spacer(Modifier.height(10.dp))
                // Same height on both tabs, so the sheet doesn't shrink when switching to Spoof.
                Column(Modifier.fillMaxWidth().height(470.dp)) {
                if (tab == 0) {
                    CdTextField(
                        q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) }, placeholder = { Text("Search city or country") },
                    )
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        LocationGroups(proxies, q, selectedLoc, suggested = suggested) { p ->
                            onSave(Action(Do.REDIRECT, p.pk, status = 1)); onDismiss()
                        }
                    }
                } else {
                    Text("Resolve to an IP or hostname of your choice.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.height(8.dp))
                    CdTextField(via, { via = it }, label = { Text("IPv4 or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    CdTextField(via6, { via6 = it }, label = { Text("IPv6 (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                }
            }
        },
        confirmButton = {
            if (tab == 1) {
                TextButton(enabled = via.isNotBlank(), onClick = {
                    onSave(Action(Do.SPOOF, via.trim(), via6.trim().ifBlank { null }, status = 1)); onDismiss()
                }) { Text("Save") }
            } else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (tab == 1) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null,
    )
}

/**
 * Top bar colours: transparent over the page background. (Material's bar animates its own container colour
 * with a delay, so with a solid colour it lagged behind the rest of the screen when the theme changes.)
 */
@Composable
fun cdTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
    titleContentColor = Palette.Text, navigationIconContentColor = Palette.Text, actionIconContentColor = Palette.Text,
)

/**
 * animateColorAsState for themed colours: animates a change of state (selected / not), but while the theme
 * is blending between dark and light it follows the blend directly instead of trailing behind it.
 */
@Composable
fun animateCdColor(
    target: Color,
    animationSpec: androidx.compose.animation.core.AnimationSpec<Color> = androidx.compose.animation.core.spring(),
    label: String = "",
): Color {
    val anim = remember { androidx.compose.animation.Animatable(target) }
    val blending = Palette.t > 0f && Palette.t < 1f
    LaunchedEffect(target) {
        if (blending || Palette.t != lastThemeT) anim.snapTo(target) else anim.animateTo(target, animationSpec)
    }
    SideEffect { lastThemeT = Palette.t }
    return if (blending) target else anim.value
}

private var lastThemeT = -1f
