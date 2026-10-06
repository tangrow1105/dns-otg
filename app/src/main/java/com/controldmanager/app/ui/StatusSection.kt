package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One "Your current IP Address" row: address, country and network. */
private data class SeenIp(val ip: String, val country: String, val org: String)

/** The DNS block of controld.com/status. [resolver] is null when this device isn't using Control D. */
private data class DnsStatus(val resolver: String?, val protocol: String?, val latencyMs: Int?, val host: String?, val ip: String?)

private data class Status(val v4: SeenIp?, val v6: SeenIp?, val dns: DnsStatus)

private val statusHttp = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

private fun getBody(url: String): JSONObject? = runCatching {
    statusHttp.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
        JSONObject(r.body?.string().orEmpty()).optJSONObject("body")
    }
}.getOrNull()

/** The same checks the status page runs; the random subdomain forces a fresh DNS lookup. */
private suspend fun loadStatus(): Status = withContext(Dispatchers.IO) {
    coroutineScope {
        fun seen(o: JSONObject?) = o?.optString("ip")?.takeIf { it.isNotBlank() }?.let { SeenIp(it, o.optString("country"), o.optString("org")) }
        val tag = "cd" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val v4 = async { seen(getBody("https://api-v4.controld.com/ip")) }
        val v6 = async { seen(getBody("https://api-v6.controld.com/ip")) }
        val detect = async { getBody("https://$tag.verify.controld.com/detect") }
        val latency = async { getBody("https://dns-latency.controld.com/info") }
        val d = detect.await()
        val l = latency.await()
        Status(
            v4.await(), v6.await(),
            DnsStatus(
                resolver = d?.optString("uid")?.takeIf { it.isNotBlank() },
                protocol = d?.optString("protocol")?.takeIf { it.isNotBlank() },
                latencyMs = l?.optString("rtt")?.toIntOrNull(),
                host = l?.optString("host")?.takeIf { it.isNotBlank() },
                ip = l?.optString("ip")?.takeIf { it.isNotBlank() },
            ),
        )
    }
}

private fun protocolName(p: String?) = when (p?.lowercase()) {
    "doh" -> "DNS-over-HTTPS"
    "doh3" -> "DNS-over-HTTPS/3"
    "dot" -> "DNS-over-TLS"
    "doq" -> "DNS-over-QUIC"
    "legacy", "dns", "udp" -> "Legacy DNS"
    null, "" -> "-"
    else -> p.uppercase()
}

/** "Your current IP Address" and "DNS" from controld.com/status, for the device the app runs on. */
@Composable
fun StatusSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val status = rememberLoader(tick) { loadStatus() }
    val s = status.data

    SectionHeader("Your current IP Address") {
        IconButton(onClick = { tick++ }, enabled = !status.loading) {
            if (status.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Solar.Refresh, "Refresh", tint = Palette.Muted)
        }
    }
    if (s == null) {
        CdCard(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(16.dp)) {
                if (status.error != null) Text(status.error!!, color = Palette.Red) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }
    listOf("IPv4" to s.v4, "IPv6" to s.v6).forEachIndexed { i, (label, ip) ->
        GroupRow(
            ip?.ip ?: "Not available", first = i == 0, last = i == 1, fill = Palette.Card,
            subtitle = label + (ip?.let { listOf(it.country, it.org).filter(String::isNotBlank).joinToString(", ").let { t -> if (t.isBlank()) "" else " · $t" } } ?: ""),
            icon = { FlagIcon(ip?.country?.takeIf { it.length == 2 }, 22) },
            onClick = ip?.let { { copyToClipboard(ctx, label, it.ip) } },
        )
    }

    val using = s.dns.resolver != null
    SectionHeader("DNS") {
        val c = if (using) Palette.Teal else Palette.Red
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (using) "Using Control D" else "Not using Control D", color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(if (using) Solar.CheckCircle else Solar.CloseCircle, null, Modifier.size(16.dp), tint = c)
        }
    }
    val rows = buildList {
        add("Resolver" to (s.dns.resolver ?: "-"))
        add("Protocol" to protocolName(s.dns.protocol))
        add("Approximate Latency" to (s.dns.latencyMs?.let { "${it}ms" } ?: "-"))
        add("Host" to (s.dns.host ?: "-"))
        add("IP" to (s.dns.ip ?: "-"))
    }
    rows.forEachIndexed { i, (k, v) ->
        GroupRow(
            k, first = i == 0, last = i == rows.lastIndex, fill = Palette.Card,
            trailing = {
                if (k == "Approximate Latency" && s.dns.latencyMs != null) LatencyValue(s.dns.latencyMs)
                else Text(
                    v, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            },
            onClick = if (k == "Resolver" || k == "IP") ({ if (v != "-") copyToClipboard(ctx, k, v) }) else null,
        )
    }
}

/** Latency in green / orange / red with signal bars, like the status page. */
@Composable
private fun LatencyValue(ms: Int) {
    val (color, bars) = when {
        ms < 50 -> Palette.Teal to 3
        ms < 120 -> Palette.Orange to 2
        else -> Palette.Red to 1
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${ms}ms", color = color, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        listOf(6.dp, 10.dp, 14.dp).forEachIndexed { i, h ->
            Box(Modifier.padding(start = 2.dp).width(4.dp).height(h).clip(RoundedCornerShape(1.dp)).background(if (i < bars) color else Palette.Ink.copy(alpha = 0.18f)))
        }
    }
}
