@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*

private val statusLabels = mapOf(0 to "Not configured", 1 to "Active", 2 to "Soft disabled", 3 to "Hard disabled")
private val analyticsLabels = listOf(0 to "Off", 1 to "Basic", 2 to "Full")

fun deviceIcon(icon: String): ImageVector = when {
    icon.startsWith("mobile-ios") -> Solar.PhoneIphone
    icon.startsWith("mobile") -> Solar.PhoneAndroid
    icon.startsWith("desktop-windows") -> Solar.Monitor
    icon.startsWith("desktop-mac") -> Solar.LaptopMac
    icon.startsWith("desktop") -> Solar.Monitor
    icon.startsWith("browser") -> Solar.Global
    icon.startsWith("tv") -> Solar.Tv
    icon.startsWith("router") -> Solar.Router
    else -> Solar.Devices
}

fun statusColor(s: Int) = when (s) {
    1 -> Palette.Teal
    0 -> Palette.Muted
    else -> Palette.Red
}

/** Last-activity badge colours, same as the web dashboard: seconds green, minutes/hours orange, days red. */
private val ActivityFresh get() = Palette.mix(Color(0xFF1BE3AD), Color(0xFF0FA678))
private val ActivityRecent = Color(0xFFE69201)
private val ActivityStale = Color(0xFFE93349)
private val ActivityInk get() = Palette.mix(Color(0xFF12131C), Color.White)

private fun activityColor(lastActivity: Long, nowSec: Long = System.currentTimeMillis() / 1000): Color {
    val s = nowSec - lastActivity
    return when {
        // Green up to and including 5 minutes; orange from 6 minutes up to a day; red after.
        s < 6 * 60 -> ActivityFresh
        s < 86_400 -> ActivityRecent
        else -> ActivityStale
    }
}

@Composable
fun EndpointsScreen(nav: NavHostController) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val ctx = LocalContext.current
    val loader = rememberLoader { session.devicesWithActivity(regionOverride(ctx)) }
    LiveRefresh(loader)
    // Which endpoint is this phone: work it out once the list first loads, not on every live refresh.
    val loaded = loader.data != null
    val current = rememberLoader(loaded) { loader.data?.let { session.currentEndpointId(ctx, it) } }
    val search = rememberBottomSearch()
    val q = search.query
    BottomSearch(search, "Search endpoints")

    NavFabButton("endpoints", Solar.Add, "New endpoint") { nav.navigate("endpoint/$NEW_ENDPOINT") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Endpoints", fontWeight = FontWeight.SemiBold)
                        Text("DNS resolvers for your devices and networks", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                },
                actions = { SearchButton(search) },
                colors = cdTopBarColors(),
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { devices ->
            val list = devices.filter { q.isBlank() || it.name.contains(q, true) || it.profile?.name?.contains(q, true) == true }
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp + LocalBottomBarSpace.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) item { EmptyState(Solar.Devices, "No endpoints") }
                items(list, key = { it.pk }) { d ->
                    val isCurrent = d.pk == current.data
                    CdCard(
                        Modifier.fillMaxWidth().then(
                            // Same highlight as the profile card used by this device.
                            if (isCurrent) Modifier.border(1.5.dp, Palette.Teal, CdShape) else Modifier
                        ),
                        // Tapping the card is read-only (resolvers); editing is the pencil icon.
                        onClick = { nav.navigate("resolvers/${d.pk}") },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeviceIconWithActivity(d)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(d.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    // Card tap opens Resolvers; editing is this small icon, so the card stays calm.
                                    IconButton(onClick = { nav.navigate("endpoint/${d.pk}") }, modifier = Modifier.size(32.dp)) {
                                        Icon(Solar.Edit, "Edit endpoint", tint = Palette.Muted, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Text(
                                    listOfNotNull(d.profile?.name, d.profile2?.name).joinToString(" + ").ifBlank { "No profile" },
                                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                                )
                                Spacer(Modifier.height(4.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (d.pk == current.data) Pill("This device", Palette.Teal)
                                    if (d.notConfigured) Pill("Not configured", Palette.Muted)
                                    else if (d.status != 1) Pill(statusLabels[d.status] ?: "Status ${d.status}", statusColor(d.status))
                                    Pill("Analytics: ${analyticsLabels.firstOrNull { it.first == d.stats }?.second ?: d.stats}", Palette.Muted)
                                    if (d.restricted) Pill("Restricted", Palette.Orange)
                                    d.ctrldVersion?.let { Pill("ctrld $it", Palette.Muted) }
                                    FeatureBadge(d)
                                }
                                Spacer(Modifier.height(8.dp))
                                // Dashboard-style counters; both open the Clients & IPs screen.
                                AccessChip(d.clients, d.ipCount) { nav.navigate("access/${d.pk}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "11m", "6h", "3d" since the endpoint's last query. */
fun activityAge(lastActivity: Long, nowSec: Long = System.currentTimeMillis() / 1000): String? {
    if (lastActivity <= 0) return null
    val s = (nowSec - lastActivity).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

/** Bundled endpoint-type icon (assets/device_icons): brand logos from Simple Icons, the rest from Solar. */
private fun deviceIconAsset(icon: String): String = when {
    icon == "mobile-ios" -> "ios"
    icon.startsWith("mobile") -> "android"
    icon == "desktop-windows" -> "windows"
    icon == "desktop-mac" -> "mac"
    icon.contains("server") || (icon.startsWith("router") && icon.contains("windows")) -> "windows-server"
    // "Generic Linux" in the router group has its own artwork; desktop Linux uses the Tux face.
    icon.startsWith("router") && icon.contains("linux") -> "generic-linux"
    icon.contains("linux") -> "linux"
    icon == "browser-chrome" -> "chrome"
    icon == "browser-edge" -> "edge"
    icon.contains("firefox") -> "firefox"
    icon.contains("brave") -> "brave"
    icon.startsWith("browser") -> "other-browser"
    icon == "tv-apple" -> "apple-tv"
    icon == "tv-android" -> "android-tv"
    icon == "tv-samsung" -> "samsung-tv"
    icon.contains("fire") && icon.startsWith("tv") -> "fire-tv"
    icon.startsWith("tv") -> "other-tv"
    icon.contains("asus") || icon.contains("merlin") -> "asus-merlin"
    icon.contains("ddwrt") || icon.contains("dd-wrt") -> "dd-wrt"
    icon.contains("firewalla") -> "firewalla"
    icon.contains("tomato") -> "fresh-tomato"
    icon.contains("glinet") || icon.contains("gl-inet") || icon.contains("gl.inet") -> "gl-inet"
    icon.contains("opnsense") -> "opnsense"
    icon.contains("pfsense") -> "pfsense"
    icon.contains("synology") -> "synology"
    icon.contains("openwrt") -> "openwrt"
    icon.contains("ubiquiti") || icon.contains("unifi") -> "ubiquiti"
    icon.contains("tailscale") -> "tailscale"
    icon.startsWith("router") -> "other-router"
    else -> "other-endpoint"
}

/** Endpoint-type icon (white, greyed when inactive). */
@Composable
fun DeviceGlyph(icon: String, size: Int = 28, tint: Color = Palette.Text) {
    coil3.compose.AsyncImage(
        model = "file:///android_asset/device_icons/${deviceIconAsset(icon)}.svg",
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier.size(size.dp),
    )
}

fun glyphTint(d: Device) = if (d.notConfigured || d.status >= 2) Palette.Muted else Palette.Text

/** Device icon with the dashboard's small last-activity badge underneath. */
@Composable
fun DeviceIconWithActivity(d: Device, size: Int = 28) {
    Column(Modifier.width((size + 8).dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DeviceGlyph(d.icon, size, glyphTint(d))
        val now = LocalNowSec.current
        if (!d.notConfigured) activityAge(d.lastActivity, now)?.let { age ->
            Text(
                age,
                fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = ActivityInk,
                modifier = Modifier
                    .offset(y = (-3).dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(activityColor(d.lastActivity, now))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun CounterChip(icon: ImageVector, count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(Palette.CardHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text("$count", color = Palette.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/** One chip for both dashboard counters (clients · IPs), opens Clients & IPs. */
@Composable
private fun AccessChip(clients: Int, ips: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(Palette.CardHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetGlyph("clients", 16, Palette.Muted)
        Spacer(Modifier.width(5.dp))
        Text("$clients", color = Palette.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(10.dp))
        AssetGlyph("ips", 16, Palette.Muted)
        Spacer(Modifier.width(5.dp))
        Text("$ips", color = Palette.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AssetGlyph(name: String, size: Int, tint: Color) {
    coil3.compose.AsyncImage(
        model = "file:///android_asset/device_icons/$name.svg",
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
        modifier = Modifier.size(size.dp),
    )
}

/** Settings the dashboard's row status icon reports (only these two), in its order: (asset icon, label). */
fun enabledFeatures(d: Device): List<Pair<String, String>> = buildList {
    if (d.learnIp) add("feat_secure" to "Authorize by Secure DNS")
    if (d.legacyIpv4Enabled) add("feat_legacy" to "Legacy DNS")
}

/** Dashboard's status icon as a badge (like the Analytics / ctrld pills): first setting's icon plus "+N". */
@Composable
private fun FeatureBadge(d: Device) {
    val features = enabledFeatures(d)
    if (features.isEmpty()) return
    Row(
        Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(Palette.Muted.copy(alpha = 0.14f))
            // Same padding as Pill, so it lines up with the Analytics / ctrld labels.
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Invisible text with Pill's style gives the badge exactly the same height as the labels.
        Text("​", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        // Every enabled setting's icon side by side, as the dashboard shows when there's room.
        features.forEachIndexed { i, (icon, _) ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            AssetGlyph(icon, 15, Palette.Muted)
        }
    }
}

/** Setup guide on docs.controld.com for the endpoint's device type (what the dashboard's Help Me Configure opens). */
fun setupGuideUrl(icon: String): String {
    val slug = when {
        icon == "mobile-ios" || icon == "tv-apple" -> "ios-platform"
        icon.startsWith("mobile") || icon == "tv-android" -> "android-platform"
        icon.contains("server") || (icon.startsWith("router") && icon.contains("windows")) -> "windows-server"
        icon == "desktop-windows" -> "platform-windows"
        icon == "desktop-mac" -> "macos-platform"
        icon.contains("linux") -> "linux-platform"
        icon.startsWith("browser") -> "browsers-platform"
        icon.startsWith("router") -> "routers-platform"
        else -> "supported-platforms"
    }
    return "https://docs.controld.com/docs/$slug"
}