package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controldmanager.app.api.DomainIntel
import com.controldmanager.app.api.IntelCategory
import kotlin.math.roundToInt

/** What Dragonfly answered; [intel] is null when it hasn't classified the domain. */
private class DragonflyLookup(val intel: DomainIntel?)

/** A small in-memory cache: entries live [ttlMs], and past [maxEntries] the one used longest ago makes room. */
internal class TtlCache<K, V : Any>(private val maxEntries: Int, private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = object : LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>) = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val (storedAt, value) = entries[key] ?: return null
        if (now() - storedAt < ttlMs) return value
        entries.remove(key)
        return null
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = now() to value
    }
}

/** A report Dragonfly is still collecting isn't kept, so the finished one shows the next time. */
internal fun keepInCache(intel: DomainIntel?): Boolean = intel?.status != "in_progress"

/**
 * Reports change every few days at most, so a domain opened again soon isn't looked up twice. Kept well under an
 * hour: that's how long the signed screenshot link in a report works.
 */
private val dragonflyCache = TtlCache<String, DragonflyLookup>(maxEntries = 200, ttlMs = 30 * 60_000L)

private val intelDateFmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")

private fun intelDate(iso: String) = runCatching { java.time.OffsetDateTime.parse(iso).format(intelDateFmt) }.getOrNull()

/** A line above a report Dragonfly didn't finish: "in_progress" while it is still collecting, "error" when a check failed. */
internal fun intelStatusNote(status: String): String? = when (status) {
    "in_progress" -> "Dragonfly is still looking at this domain, so some of it may be missing."
    "error" -> "Some of Dragonfly's checks on this domain failed, so parts may be missing."
    else -> null
}

/** Dragonfly's own page for the domain, as the dashboard's "View full report on Dragonfly" opens it. */
private fun reportUrl(domain: String, at: String) =
    android.net.Uri.parse("https://controld.com/tools/dragonfly-url-filtering").buildUpon()
        .appendPath(domain).appendQueryParameter("at", at).build().toString()

/**
 * Dragonfly, Control D's domain intelligence, for the domain of a query (the dashboard's "More info"): what the
 * site is, how sure Dragonfly is and why, and how the site is set up.
 */
@Composable
fun DragonflySection(domain: String, atMs: Long) {
    val session = LocalSession.current
    val uri = LocalUriHandler.current
    // As of the query's time, as the dashboard asks; without a time Dragonfly answers with a scan still in progress.
    val at = remember(atMs) { java.time.Instant.ofEpochMilli(atMs.takeIf { it > 0 } ?: System.currentTimeMillis()).toString() }
    // Per domain and day of the query: an older query can have an older report (failed lookups aren't kept).
    val key = "$domain ${at.take(10)}"
    val lookup = rememberLoader(domain, at) {
        dragonflyCache.get(key) ?: DragonflyLookup(session.api.dragonfly(domain, at)).also { if (keepInCache(it.intel)) dragonflyCache.put(key, it) }
    }
    val result = lookup.data

    Spacer(Modifier.height(10.dp))
    Text("DRAGONFLY", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, letterSpacing = 1.sp)
    when {
        lookup.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Couldn't reach Dragonfly", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { lookup.reload() }) { Text("Retry") }
        }
        result == null -> Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(shimmerBrush()))
        result.intel == null -> Text(
            "Dragonfly has no report on this domain.",
            style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(vertical = 6.dp),
        )
        else -> {
            DragonflyReport(result.intel)
            TextButton(onClick = { uri.openUri(reportUrl(domain, at)) }) { Text("View full report on Dragonfly") }
        }
    }
}

@Composable
private fun DragonflyReport(intel: DomainIntel) {
    intelStatusNote(intel.status)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(vertical = 4.dp))
    }
    intel.categories.forEach { IntelCategoryRow(it) }
    intel.screenshotUrl?.let { SiteScreenshot(it) }
    intel.http?.let { h ->
        KeyValueRow("HTTP", listOf("${h.statusCode}", h.contentType.substringBefore(';'), h.finalUrl).filter { it.isNotBlank() }.joinToString(" · "))
    }
    intel.tls?.let { t ->
        KeyValueRow("TLS", listOfNotNull(t.protocols, t.issuerName, intelDate(t.validUntil)?.let { "expires $it" }).filter { it.isNotBlank() }.joinToString(" · "))
    }
    intel.whois?.let { w ->
        // Two rows: a long owner and registrar would otherwise push the dates out of a two-line row.
        listOf(w.organization, w.registrar).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { KeyValueRow("WHOIS", it) }
        listOfNotNull(intelDate(w.created), intelDate(w.expires)?.let { "expires $it" }).joinToString(" · ").takeIf { it.isNotBlank() }
            ?.let { KeyValueRow("Registered", it) }
    }
    intel.netlog?.let { n ->
        KeyValueRow("Network log", "${n.total} requests" + (n.topDomain.takeIf { it.isNotBlank() }?.let { " · most to $it" } ?: ""))
    }
}

/** Dragonfly's screenshot of the site (taken at 1920 × 1080); left out when it doesn't load. */
@Composable
private fun SiteScreenshot(url: String) {
    val ctx = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    // The link is signed anew with every lookup, so the image is kept under the link without its signature, and in
    // memory only: the shared disk cache holds the site icons, which a few of these would push out.
    val request = remember(url) {
        coil3.request.ImageRequest.Builder(ctx).data(url)
            .memoryCacheKey(url.substringBefore('?'))
            .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
            .build()
    }
    coil3.compose.AsyncImage(
        model = request, contentDescription = "Screenshot of the site",
        contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, onError = { failed = true },
        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Palette.CardHigh),
    )
}

/** One category as the dashboard shows it: name, confidence label and percentage, a bar, and the reasoning. */
@Composable
private fun IntelCategoryRow(c: IntelCategory) {
    val color = when {
        c.dangerous -> Palette.Red
        c.label == "high" -> Palette.Teal
        c.label == "medium" -> Palette.Orange
        else -> Palette.Muted
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, fontWeight = FontWeight.SemiBold, color = if (c.dangerous) Palette.Red else Palette.Text, modifier = Modifier.weight(1f))
            if (c.label.isNotBlank()) Text(c.label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.width(6.dp))
            Text("${(c.confidence * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Palette.Outline)) {
            Box(Modifier.fillMaxWidth(c.confidence.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
        if (c.reasoning.isNotBlank()) Text(c.reasoning, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}
