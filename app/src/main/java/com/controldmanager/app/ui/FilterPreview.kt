package com.controldmanager.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controldmanager.app.api.Action
import com.controldmanager.app.api.CountItem
import com.controldmanager.app.api.Do
import com.controldmanager.app.api.Filter
import com.controldmanager.app.api.FilterLevel
import com.controldmanager.app.api.TimeRange
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.NumberFormat

/** The ranges the dashboard's preview offers. */
private val previewRanges = listOf(SlideOption("Day") to 86_400L, SlideOption("Week") to 604_800L, SlideOption("Month") to 2_592_000L)

private class PreviewResult(val blocked: Long, val total: Long, val domains: List<CountItem>)

/**
 * Filter Preview, as the dashboard's: what blocking [f] (at the chosen mode, when it has modes) would have done to
 * this profile's recent queries, the domains behind it with a one-tap Bypass rule each, and a button to turn it on.
 * Only reads until Allow or Block is tapped. The counts are Control D's estimate: today's list against past queries.
 */
@Composable
fun FilterPreview(pid: String, f: Filter, busy: Boolean, onBlock: (FilterLevel?) -> Unit) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val active = f.activeLevel?.let { f.levels.indexOf(it) }?.takeIf { f.enabled && it >= 0 }
    // A filter that's on previews the next mode up; one that's off starts at its first mode.
    var levelIdx by remember(f.pk) { mutableIntStateOf(((active ?: -1) + 1).coerceAtMost(f.levels.lastIndex).coerceAtLeast(0)) }
    var rangeIdx by remember { mutableIntStateOf(1) }
    var allowed by remember(f.pk) { mutableStateOf(emptySet<String>()) }
    var showAll by remember(f.pk) { mutableStateOf(false) }

    val level = f.levels.getOrNull(levelIdx)
    // What the preview turns on. Malware's modes stack, so a mode means it and the ones below it.
    val enable = when {
        level == null -> listOf(f.pk)
        f.pk == "malware" -> f.levels.take(levelIdx + 1).filter { it.type != "option" }.map { it.name }
        else -> listOf(level.name)
    }
    val isCurrent = f.enabled && (level == null || levelIdx == active)

    Column(Modifier.fillMaxWidth()) {
        if (f.levels.size > 1) {
            SlideSelector(f.levels.map { SlideOption(it.title) }, levelIdx, { levelIdx = it }, height = 40.dp)
            Spacer(Modifier.height(8.dp))
        }
        SlideSelector(previewRanges.map { it.first }, rangeIdx, { rangeIdx = it }, height = 40.dp)
        Spacer(Modifier.height(12.dp))

        if (isCurrent) {
            Text(
                "This is what's on now. Pick another mode to see what it would change.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        val loader = rememberLoader(pid, enable, rangeIdx) {
            val region = session.analyticsRegion(null)
            val (label, seconds) = previewRanges[rangeIdx].let { it.first.label to it.second }
            val r = TimeRange("Last $label", seconds)
            coroutineScope {
                val blocked = async { session.api.simulationCount(region, r, pid, enable) }
                val total = async { session.api.simulationCount(region, r, pid, null) }
                val domains = async { session.api.simulationDomains(region, r, pid, enable) }
                PreviewResult(blocked.await(), total.await(), domains.await())
            }
        }
        val result = loader.data
        when {
            loader.error != null && result == null -> Text(loader.error!!, color = Palette.Red, style = MaterialTheme.typography.bodySmall)
            result == null -> Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            else -> {
                val n = NumberFormat.getIntegerInstance()
                val share = if (result.total > 0) result.blocked * 100.0 / result.total else 0.0
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewStat("~${n.format(result.blocked)}", "queries would have been blocked", Modifier.weight(1f))
                    PreviewStat(
                        when { result.blocked == 0L -> "0%"; share < 0.1 -> "<0.1%"; else -> "%.1f%%".format(share) },
                        "of this profile's traffic", Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (result.domains.isEmpty()) {
                    Text("Nothing you visited would have been blocked.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "Domains it would block. Tap Allow to keep one working (adds a Bypass rule).",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    val shown = if (showAll) result.domains else result.domains.take(10)
                    shown.forEach { d ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(n.format(d.count), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(4.dp))
                            if (d.value in allowed) {
                                Text("Allowed", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = actionColor(Do.BYPASS), style = MaterialTheme.typography.labelLarge)
                            } else {
                                TextButton(enabled = !runner.busy, onClick = {
                                    runner.run("Bypass rule added for ${d.value}") {
                                        session.api.createRules(pid, listOf(d.value), Action(Do.BYPASS), 0, null)
                                        allowed = allowed + d.value
                                    }
                                }) { Text("Allow") }
                            }
                        }
                    }
                    if (!showAll && result.domains.size > 10) {
                        TextButton(onClick = { showAll = true }) { Text("Show ${result.domains.size - 10} more") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CdButton(
            "Block" + (level?.let { " · ${it.title}" } ?: ""),
            onClick = { onBlock(level) },
            modifier = Modifier.fillMaxWidth(),
            busy = busy,
            enabled = !busy,
            tint = Palette.Red,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Estimate: today's list matched against your past queries. Nothing changes until you tap Block or Allow.",
            color = Palette.Muted, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PreviewStat(value: String, label: String, modifier: Modifier = Modifier) {
    CdCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
