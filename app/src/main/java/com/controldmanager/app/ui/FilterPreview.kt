package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controldmanager.app.api.ApiException
import com.controldmanager.app.api.Filter
import com.controldmanager.app.api.FilterChange
import com.controldmanager.app.api.FilterLevel
import com.controldmanager.app.api.TimeRange

/** Filters the analytics server can preview: every id the filter uses must be on its list (Malware's can't). */
internal fun canPreview(f: Filter, previewable: Set<String>): Boolean {
    if (previewable.isEmpty() || f.pk == "malware") return false
    val ids = if (f.levels.isEmpty()) listOf(f.pk) else f.levels.map { it.name }
    return ids.all { it in previewable }
}

/** The change from the filter as it is to [target] (null = off), as the dashboard builds it; null when nothing changes. */
internal fun filterChange(f: Filter, target: FilterLevel?, targetOn: Boolean): FilterChange? {
    val current = if (!f.enabled) null else f.activeLevel?.name ?: f.pk
    val wanted = if (!targetOn) null else target?.name ?: f.pk
    return when {
        current == wanted -> null
        current == null -> FilterChange(listOf(wanted!!), emptyList(), blocksMore = true)
        wanted == null -> FilterChange(emptyList(), listOf(current), blocksMore = false)
        else -> FilterChange(
            listOf(wanted), listOf(current),
            blocksMore = f.levels.indexOfFirst { it.name == wanted } > f.levels.indexOfFirst { it.name == current },
        )
    }
}

private val previewRanges = listOf("Last day" to 86_400L, "Last week" to 7 * 86_400L, "Last month" to 30 * 86_400L)

/**
 * The dashboard's filter Preview: what turning a filter on or off, or switching its mode, would have done to this
 * profile's real traffic (Full Analytics logs), before applying it. [onApply] makes the change (on, and the level).
 */
@Composable
internal fun FilterPreviewSheet(pid: String, f: Filter, onDismiss: () -> Unit, onApply: (Boolean, FilterLevel?) -> Unit) {
    val session = LocalSession.current
    // Starts on the opposite of now: off for a filter that's on (as the dashboard), its first mode for one that's off.
    var targetOn by remember { mutableStateOf(!f.enabled) }
    var target by remember { mutableStateOf(if (f.enabled) null else f.levels.firstOrNull()) }
    var range by remember { mutableStateOf(previewRanges[1]) }
    var picking by remember { mutableStateOf(false) }
    val change = filterChange(f, target, targetOn)
    val result = rememberLoader(change, range) {
        if (change == null) null
        else try {
            session.api.previewFilterChange(session.analyticsRegion(null), pid, change, TimeRange(range.first, range.second))
        } catch (e: ApiException) {
            throw ApiException(
                when (e.code) {
                    429 -> "Control D is busy right now. Try again in a moment."
                    401, 403 -> "Control D doesn't allow previews with this API token."
                    else -> e.message ?: "Couldn't load the preview"
                }, e.code,
            )
        }
    }
    val now = if (!f.enabled) "Allowed" else "Blocked" + (f.activeLevel?.let { " · ${it.title}" } ?: "")
    val after = if (!targetOn) "Allowed" else "Blocked" + (target?.let { " · ${it.title}" } ?: "")
    val title = when {
        change == null -> "No change"
        !f.enabled -> "Block ${f.name}"
        !targetOn -> "Unblock ${f.name}"
        else -> "Switch to ${target?.title}"
    }
    val good = change != null && !change.blocksMore

    CdSheet(onDismissRequest = onDismiss) {
        // The list scrolls; Cancel / Apply stay pinned at the bottom, with the list fading out behind them, and at the
        // top it fades under the sheet's edge once scrolled.
        val scroll = rememberScrollState()
        val topFade = Palette.Card
        Column(
            Modifier.weight(1f, fill = false)
                .drawWithContent {
                    drawContent()
                    if (scroll.value > 0) drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(topFade, Color.Transparent), endY = 28.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width, 28.dp.toPx()),
                    )
                }
                .verticalScroll(scroll).padding(horizontal = 20.dp),
        ) {
            Text("Preview: $title", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            SelectBox(range.first) { picking = true }
            if (f.levels.isNotEmpty()) {
                SectionHeader("Filter mode")
                val options = listOf<FilterLevel?>(null) + f.levels
                SlideSelector(
                    options.map { SlideOption(it?.title ?: "Off") },
                    if (!targetOn) 0 else options.indexOf(target).coerceAtLeast(0),
                    { i -> targetOn = i > 0; target = options[i] },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NOW", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                Spacer(Modifier.width(6.dp))
                StatePill(now, if (f.enabled) Palette.Red else Palette.Teal)
                Text("  ›  ", color = Palette.Muted)
                Text("AFTER", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                Spacer(Modifier.width(6.dp))
                StatePill(after, if (targetOn) Palette.Red else Palette.Teal)
            }
            Spacer(Modifier.height(12.dp))
            val r = result.data
            when {
                change == null -> Text("Pick a different mode to see what would change.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                r == null && result.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                r == null -> Text(result.error ?: "", color = Palette.Red, style = MaterialTheme.typography.bodySmall)
                r.actual == 0L -> Text("No recorded traffic in this period. Previews use endpoints with Full Analytics.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox(if (good) "Would have been allowed" else "Would have been blocked", "~${"%,d".format(r.impact)}", if (good) Palette.Teal else Palette.Red, Modifier.weight(1f))
                        val share = r.impact * 100.0 / r.actual
                        StatBox("Share of traffic", if (share > 0 && share < 0.1) "<0.1%" else "%.1f%%".format(share), Palette.Text, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "An estimate: today's ${f.name} list matched against your Full Analytics traffic. Queries your Rules and Services " +
                            "already handle are left out" + if (good) ", and another enabled filter may still block some of these." else ".",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                    )
                    SectionHeader("Domains · ${if (r.domains.size >= 100) "100+" else r.domains.size}")
                    if (r.domains.isEmpty()) Text("No domains affected", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    r.domains.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("%,d".format(d.count), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                            Spacer(Modifier.width(10.dp))
                            Text(if (good) "✓ Allowed" else "⊘ Blocked", style = MaterialTheme.typography.labelMedium, color = if (good) Palette.Teal else Palette.Red)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        val apply = when {
            change == null -> "Apply"
            !f.enabled -> "Block"
            !targetOn -> "Unblock"
            else -> "Switch to ${target?.title}"
        }
        // The fade is drawn just above the buttons, over the end of the list.
        val fade = Palette.Card
        Row(
            Modifier.fillMaxWidth().drawBehind {
                val h = 28.dp.toPx()
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, fade), startY = -h, endY = 0f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, -h), size = androidx.compose.ui.geometry.Size(size.width, h),
                )
            }.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CdButton("Cancel", onDismiss, Modifier.weight(1f))
            CdButton(apply, { onApply(targetOn, target); onDismiss() }, Modifier.weight(1f), enabled = change != null, tint = Palette.Teal)
        }
    }
    if (picking) PickerDialog("Period", previewRanges, label = { it.first }, selected = { it == range }, onDismiss = { picking = false }) { range = it }
}

@Composable
private fun StatePill(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatBox(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(CdShape).background(Palette.CardHigh).padding(12.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    }
}
