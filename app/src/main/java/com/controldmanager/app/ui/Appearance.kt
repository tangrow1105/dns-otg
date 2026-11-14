package com.controldmanager.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** An accent colour: its dark and light theme values, the ink drawn on a fill of it, and its tinted containers. */
class Accent(
    val key: String, val label: String, val dark: Color, val light: Color, val onDark: Color,
    val darkContainer: Color = lerp(Color(0xFF0B0C12), dark, 0.24f),
    val lightOnContainer: Color = lerp(light, Color.Black, 0.35f),
)

/** The app's backgrounds, cards, outlines and text, for the dark and the light theme. */
class AppSurface(
    val key: String, val label: String,
    val bg: Pair<Color, Color>, val card: Pair<Color, Color>, val cardHigh: Pair<Color, Color>, val outline: Pair<Color, Color>,
    val text: Pair<Color, Color>, val muted: Pair<Color, Color>, val button: Color,
    val darkLow: Color, val darkHighest: Color, val darkOutlineVariant: Color,
    val lightLow: Color = Color(0xFFF7F8FB), val lightHighest: Color = Color(0xFFE6E8F0), val lightOutlineVariant: Color = Color(0xFFE3E5EE),
)

/** Teal first: the default, Control D's own green-teal. */
val Accents = listOf(
    Accent("teal", "Teal", Color(0xFF2FD8A2), Color(0xFF18C13E), Color(0xFF002A1C), Color(0xFF0F3B30), Color(0xFF0C6B22)),
    Accent("violet", "Violet", Color(0xFFA69BFF), Color(0xFF6252CC), Color(0xFF17122C)),
    Accent("indigo", "Indigo", Color(0xFF8C9EFF), Color(0xFF3F51B5), Color(0xFF0A1033)),
    Accent("blue", "Blue", Color(0xFF6AB7FF), Color(0xFF1F6FD1), Color(0xFF001A33)),
    Accent("cyan", "Cyan", Color(0xFF5EE0F0), Color(0xFF00838F), Color(0xFF002A2E)),
    Accent("lime", "Lime", Color(0xFFB5E86B), Color(0xFF5E9B1A), Color(0xFF1E2E00)),
    Accent("amber", "Amber", Color(0xFFFFD166), Color(0xFFB7791F), Color(0xFF332300)),
    Accent("orange", "Orange", Color(0xFFFFB86B), Color(0xFFD9730D), Color(0xFF3A1F00)),
    Accent("pink", "Pink", Color(0xFFFF8AC8), Color(0xFFC2185B), Color(0xFF3A0A22)),
    Accent("red", "Red", Color(0xFFFF8A80), Color(0xFFC62828), Color(0xFF3A0B08)),
)

/** Navy first: the default, as the Control D dashboard. */
val Surfaces = listOf(
    AppSurface(
        "navy", "Navy",
        bg = Color(0xFF080B17) to Color(0xFFEBECF3), card = Color(0xFF111628) to Color.White,
        cardHigh = Color(0xFF1A2038) to Color(0xFFF2F3F8), outline = Color(0xFF262D4A) to Color(0xFFD8DBE6),
        text = Color(0xFFE8EBF7) to Color(0xFF0E1018), muted = Color(0xFF8B93B3) to Color(0xFF656A7A),
        button = Color(0xFF1C2131), darkLow = Color(0xFF0D1120), darkHighest = Color(0xFF212844), darkOutlineVariant = Color(0xFF1C2238),
    ),
    AppSurface(
        "graphite", "Graphite",
        bg = Color(0xFF101116) to Color(0xFFF7F8FB), card = Color(0xFF191A21) to Color.White,
        cardHigh = Color(0xFF22232D) to Color(0xFFF0F1F7), outline = Color(0xFF343642) to Color(0xFFD8DAE4),
        text = Color(0xFFF3F4F7) to Color(0xFF20212B), muted = Color(0xFFA5A8B8) to Color(0xFF5C6172),
        button = Color(0xFF26272F), darkLow = Color(0xFF14151B), darkHighest = Color(0xFF2B2C37), darkOutlineVariant = Color(0xFF24252E),
        lightLow = Color(0xFFF3F4F8), lightHighest = Color(0xFFE8EAF3),
    ),
    AppSurface(
        "black", "Black",
        bg = Color.Black to Color(0xFFF7F8FB), card = Color(0xFF0F0F12) to Color.White,
        cardHigh = Color(0xFF1A1A1F) to Color(0xFFF0F1F7), outline = Color(0xFF2A2A31) to Color(0xFFD8DAE4),
        text = Color(0xFFF3F4F7) to Color(0xFF20212B), muted = Color(0xFFA0A3B1) to Color(0xFF5C6172),
        button = Color(0xFF1A1A1F), darkLow = Color(0xFF08080A), darkHighest = Color(0xFF24242A), darkOutlineVariant = Color(0xFF1C1C21),
        lightLow = Color(0xFFF3F4F8), lightHighest = Color(0xFFE8EAF3),
    ),
)

/** Accent, background and corner radius (saved on this device, next to the light / dark choice). */
object Appearance {
    private const val PREFS = "controldmanager_ui"
    const val DEFAULT_CORNER = 20
    const val MAX_CORNER = 28

    var accent by mutableStateOf(Accents.first())
        private set
    var surface by mutableStateOf(Surfaces.first())
        private set
    var corner by mutableIntStateOf(DEFAULT_CORNER)
        private set

    val isDefault get() = accent === Accents.first() && surface === Surfaces.first() && corner == DEFAULT_CORNER

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        accent = Accents.firstOrNull { it.key == p.getString("accent", null) } ?: Accents.first()
        surface = Surfaces.firstOrNull { it.key == p.getString("surface", null) } ?: Surfaces.first()
        corner = p.getInt("corner", DEFAULT_CORNER).coerceIn(0, MAX_CORNER)
    }

    fun setAccent(ctx: Context, a: Accent) { accent = a; save(ctx) }
    fun setSurface(ctx: Context, s: AppSurface) { surface = s; save(ctx) }
    /** Live while the slider moves; [persist] once it is let go. */
    fun setCorner(ctx: Context, dp: Int, persist: Boolean) { corner = dp.coerceIn(0, MAX_CORNER); if (persist) save(ctx) }
    fun reset(ctx: Context) { accent = Accents.first(); surface = Surfaces.first(); corner = DEFAULT_CORNER; save(ctx) }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("accent", accent.key).putString("surface", surface.key).putInt("corner", corner).apply()
    }
}

/** Background, accent colour and corner radius, under Preferences → Appearance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceOptions() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        OptionLabel("Background")
        SlideSelector(
            Surfaces.map { SlideOption(it.label) },
            Surfaces.indexOf(Appearance.surface),
            { Appearance.setSurface(ctx, Surfaces[it]) },
        )

        Spacer(Modifier.height(14.dp))
        OptionLabel("Accent colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Accents.forEach { a ->
                val chosen = a === Appearance.accent
                val fill = if (Palette.light) a.light else a.dark
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(fill)
                        .border(2.dp, if (chosen) Palette.Text else Color.Transparent, CircleShape)
                        .clickable { Appearance.setAccent(ctx, a) }
                        .semantics { contentDescription = a.label; selected = chosen },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Icon(Solar.Check, null, Modifier.size(18.dp), tint = if (Palette.light) Color.White else a.onDark)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptionLabel("Corner radius", Modifier.weight(1f))
            Text("${Appearance.corner} dp", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Slider(
            value = Appearance.corner.toFloat(),
            onValueChange = { Appearance.setCorner(ctx, (it / 2).toInt() * 2, persist = false) },
            onValueChangeFinished = { Appearance.setCorner(ctx, Appearance.corner, persist = true) },
            valueRange = 0f..Appearance.MAX_CORNER.toFloat(),
            steps = Appearance.MAX_CORNER / 2 - 1,
        )

        if (!Appearance.isDefault) {
            TextButton(onClick = { Appearance.reset(ctx) }, modifier = Modifier.align(Alignment.End)) {
                Text("Reset to default", color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
